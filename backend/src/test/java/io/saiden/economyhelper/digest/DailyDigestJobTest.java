package io.saiden.economyhelper.digest;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.CryptoResolver;
import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.fmp.FmpApi;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.telegram.TelegramClient;
import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 발송 잡의 분기를 Redis 없이 고정한다.
 *
 * <p>실제 분산 환경에서 한 번만 나가는지는 {@link DigestIntegrationTest}가 진짜 Redis로 본다.
 */
class DailyDigestJobTest {

    /** UTC 00:00 = KST 09:00 — 정기 발송 시각 중 하나다. */
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final String SLOT = "2026-08-11";
    /** 국내 전일 종가의 기준 시각 — KST 자정이다. */
    private static final Instant BASIS = LocalDate.of(2026, 8, 11)
            .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();

    @Test
    @DisplayName("슬롯 키는 KST 날짜다 — 시각이 들어가면 재시도가 중복 발송이 된다")
    void sendsAndKeysSlotInSeoulTime() {
        RecordingClient telegram = new RecordingClient();
        InMemoryHistory history = new InMemoryHistory();

        DigestResult result = job(telegram, history, List.of(item("유가 상승"))).run(false);

        assertThat(result.sent()).isTrue();
        assertThat(result.slot()).isEqualTo(SLOT);
        assertThat(result.delivered()).containsExactly("뉴스");
        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.sent.get(0)).contains("유가 상승");
    }

    @Test
    @DisplayName("이미 발송된 슬롯이면 수집조차 하지 않는다")
    void skipsAlreadySentSlot() {
        RecordingClient telegram = new RecordingClient();
        InMemoryHistory history = new InMemoryHistory();
        history.claim(SLOT);

        CountingFacade facade = new CountingFacade(List.of(item("기사")));
        DigestResult result = job(telegram, history, facade).run(false);

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).contains("이미 발송");
        assertThat(telegram.sent).isEmpty();
        assertThat(facade.calls)
                .as("건너뛸 슬롯인데 수집·번역 비용을 치르면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("force면 이미 보낸 슬롯도 다시 보낸다 — 수동 점검용")
    void forceResendsSentSlot() {
        RecordingClient telegram = new RecordingClient();
        InMemoryHistory history = new InMemoryHistory();
        history.claim(SLOT);

        assertThat(job(telegram, history, List.of(item("기사"))).run(true).sent()).isTrue();
        assertThat(telegram.sent).hasSize(1);
    }

    @Test
    @DisplayName("네 종류가 모두 실패하면 발송도 안 하고 슬롯 선점도 되돌린다")
    void releasesSlotWhenNothingCollected() {
        RecordingClient telegram = new RecordingClient();
        InMemoryHistory history = new InMemoryHistory();

        DigestResult result = job(telegram, history, List.of()).run(false);

        assertThat(result.sent()).isFalse();
        assertThat(telegram.sent).isEmpty();
        assertThat(history.claimed)
                .as("전 매체 수집 실패를 '발송함'으로 남기면 이 시간대는 복구 후에도 영영 비어 있다")
                .isEmpty();
    }

    @Test
    @DisplayName("텔레그램이 실패하면 슬롯을 되돌리고, 예외 대신 실패 결과를 돌려준다")
    void releasesSlotWhenSendFails() {
        InMemoryHistory history = new InMemoryHistory();
        TelegramClient exploding = new RecordingClient() {
            @Override
            public void send(String text) {
                throw new IllegalStateException("Bot API 502");
            }
        };

        DigestResult result = job(exploding, history, List.of(item("기사"))).run(false);

        assertThat(result.sent()).isFalse();
        assertThat(result.failed()).extracting(DigestResult.Failure::section).contains("뉴스");
        assertThat(history.claimed).isEmpty();
    }

    @Test
    @DisplayName("force로 남의 선점을 지나쳤다면 실패해도 그 선점을 지우지 않는다")
    void forceDoesNotReleaseSomeoneElsesClaim() {
        InMemoryHistory history = new InMemoryHistory();
        history.claim(SLOT);
        TelegramClient exploding = new RecordingClient() {
            @Override
            public void send(String text) {
                throw new IllegalStateException("Bot API 502");
            }
        };

        job(exploding, history, List.of(item("기사"))).run(true);

        assertThat(history.claimed)
                .as("내가 잡지 않은 선점을 지우면 다른 인스턴스가 같은 슬롯을 또 보낸다")
                .containsExactly(SLOT);
    }

    @Test
    @DisplayName("넷 중 하나가 죽어도 나머지는 나간다 — 환율이 안 된다고 뉴스까지 막을 이유가 없다")
    void partialFailureStillSends() {
        RecordingClient telegram = new RecordingClient();
        InMemoryHistory history = new InMemoryHistory();

        DigestResult result = job(telegram, history, new CountingFacade(List.of(item("유가 상승"))),
                fx(false), stock(true), crypto(true)).run(false);

        assertThat(result.sent()).isTrue();
        assertThat(result.delivered()).containsExactly("증시", "코인", "뉴스");
        assertThat(result.failed()).extracting(DigestResult.Failure::section).containsExactly("환율");
        assertThat(telegram.sent).hasSize(3);
    }

    @Test
    @DisplayName("텔레그램이 거절하면 그 사유가 결과에 실린다 — 이름만 남기면 로그를 뒤져야 한다")
    void carriesTelegramRejectionReason() {
        TelegramClient rejecting = new RecordingClient() {
            @Override
            public void send(String text) {
                throw new TelegramClient.TelegramException(
                        "텔레그램 sendMessage 거절: 400 Bad Request: chat not found");
            }
        };

        DigestResult result = job(rejecting, new InMemoryHistory(),
                new CountingFacade(List.of(item("유가 상승"))),
                fx(true), stock(true), crypto(true)).run(false);

        assertThat(result.sent()).isFalse();
        assertThat(result.failed()).hasSize(4)
                .allSatisfy(failure -> assertThat(failure.reason()).contains("chat not found"));
    }

    @Test
    @DisplayName("마지막 실행 결과를 들고 있는다 — 확인하려고 방송을 한 번 더 쏘지 않게")
    void remembersLastResult() {
        DailyDigestJob job = job(new RecordingClient(), new InMemoryHistory(),
                new CountingFacade(List.of()), fx(true), stock(true), crypto(true));

        assertThat(job.lastResult().slot()).as("실행 전에는 비어 있다").isNull();
        DigestResult result = job.run(false);

        assertThat(job.lastResult()).isEqualTo(result);
    }

    @Test
    @DisplayName("증시 통은 지수와 종목을 한 통에 담는다")
    void stockMessageCarriesIndicesAndStocks() {
        RecordingClient telegram = new RecordingClient();

        job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(true), crypto(false)).run(false);

        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.sent.get(0))
                .contains("📈 <b>증시</b>")
                .contains("코스피")
                .contains("삼성전자");
    }

    @Test
    @DisplayName("지수만 죽어도 종목은 나간다 — 조회 API가 달라 따로 실패한다")
    void stockMessageSurvivesIndexFailure() {
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(false, true), crypto(false)).run(false);

        assertThat(result.delivered()).containsExactly("증시");
        assertThat(telegram.sent.get(0)).contains("삼성전자").doesNotContain("코스피");
    }

    @Test
    @DisplayName("네 종류가 다 살아 있으면 네 통을 순서대로 보낸다")
    void sendsFourMessagesInOrder() {
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(),
                new CountingFacade(List.of(item("유가 상승"))),
                fx(true), stock(true), crypto(true)).run(false);

        assertThat(result.delivered()).containsExactly("환율", "증시", "코인", "뉴스");
        assertThat(telegram.sent).hasSize(4);
        assertThat(telegram.sent.get(0)).contains("💱 <b>환율</b>").contains("1 USD =");
        assertThat(telegram.sent.get(1)).contains("📈 <b>증시</b>").contains("삼성전자");
        assertThat(telegram.sent.get(2)).contains("🪙 <b>코인</b>").contains("비트코인");
        assertThat(telegram.sent.get(3)).contains("유가 상승");
    }

    @Test
    @DisplayName("같은 날이면 시각이 달라도 같은 슬롯이다 — 09시에 놓쳐 10시에 보내도 한 번뿐")
    void sameDayIsOneSlotRegardlessOfHour() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingClient telegram = new RecordingClient();

        // 09:00(KST)에 발송
        job(telegram, history, List.of(item("기사"))).run(false);
        assertThat(telegram.sent).hasSize(1);

        // 같은 날 10:30 — 스핀다운에서 늦게 깨어난 상황
        Clock later = Clock.fixed(NOW.plus(java.time.Duration.ofMinutes(90)), ZoneOffset.UTC);
        DigestResult second = new DailyDigestJob(new CountingFacade(List.of(item("기사"))),
                fx(false), stock(false), crypto(false), telegram, history, later, properties())
                .run(false);

        assertThat(second.sent()).isFalse();
        assertThat(telegram.sent).as("같은 날 두 번 나가면 구독자가 같은 브리핑을 두 번 받는다")
                .hasSize(1);
    }

    private static DailyDigestJob job(TelegramClient telegram, SendHistory history,
                                      List<NewsItem> items) {
        return job(telegram, history, new CountingFacade(items));
    }

    /** 뉴스만 살아 있고 시세 셋은 전부 죽은 상태 — 예전 동작(뉴스 한 통)과 같아진다. */
    private static DailyDigestJob job(TelegramClient telegram, SendHistory history,
                                      NewsFacade facade) {
        return job(telegram, history, facade, fx(false), stock(false), crypto(false));
    }

    private static DailyDigestJob job(TelegramClient telegram, SendHistory history, NewsFacade facade,
                                      FxService fx, StockService stock, CryptoService crypto) {
        return new DailyDigestJob(facade, fx, stock, crypto, telegram, history,
                Clock.fixed(NOW, ZoneOffset.UTC), properties());
    }

    private static FxService fx(boolean alive) {
        return new FxService(List.of()) {
            @Override
            public Optional<FxRate> usdToKrw() {
                return alive
                        ? Optional.of(new FxRate("USD", "KRW", new BigDecimal("1415"), FxSource.KEXIM, NOW))
                        : Optional.empty();
            }
        };
    }

    private static StockService stock(boolean alive) {
        return stock(alive, alive);
    }

    private static StockService stock(boolean indicesAlive, boolean stocksAlive) {
        return new StockService(
                new StockPriceApi(RestClient.builder(), "https://example.invalid", "k",
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                new MarketIndexApi(RestClient.builder(), "https://example.invalid", "k",
                        Clock.fixed(NOW, ZoneOffset.UTC)), noFmp(), null) {
            @Override
            public List<StockQuote> indicesOf(List<String> names) {
                return indicesAlive
                        ? List.of(new StockQuote(null, "코스피", "KOSPI시리즈", new BigDecimal("6345.53"),
                                StockQuote.Money.NONE, BASIS, false, true, BigDecimal.ZERO))
                        : List.of();
            }

            @Override
            public List<StockQuote> quotesOf(List<String> codes) {
                return stocksAlive
                        ? List.of(new StockQuote("005930", "삼성전자", "KOSPI", new BigDecimal("239500"),
                                StockQuote.Money.KRW, BASIS, false, false,
                                new BigDecimal("1400183726616000")))
                        : List.of();
            }
        };
    }

    @Test
    @DisplayName("브리핑 코인 통에 바이낸스와 원화 환산이 함께 나간다")
    void cryptoMessageCarriesBinanceWithKrw() {
        RecordingClient telegram = new RecordingClient();

        job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(false, false), cryptoWithBinance(new BigDecimal("1384"))).run(false);

        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.sent.get(0))
                .contains("🪙 <b>코인</b>")
                .contains("업비트").contains("89,848,000 KRW")
                .contains("바이낸스").contains("63,703.69 USDT")
                // 63,703.69 × 1,384 = 88,165,906.96 → 88,165,907
                .as("잡이 usdtKrw를 넘기지 않으면 USDT만 나오고 원화가 빠진다")
                .contains("88,165,907 KRW");
    }

    @Test
    @DisplayName("USDT 원화값을 못 가져오면 USDT만 나간다 — 환산 실패가 코인 통을 통째로 막지 않는다")
    void cryptoMessageSurvivesMissingUsdtRate() {
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(false, false), cryptoWithBinance(null)).run(false);

        assertThat(result.delivered()).containsExactly("코인");
        assertThat(telegram.sent.get(0))
                .contains("63,703.69 USDT")
                .doesNotContain("88,165,907");
    }

    @Test
    @DisplayName("KRW-USDT가 이미 목록에 있으면 다시 묻지 않는다 — 캐시 키가 달라 그대로 호출로 나간다")
    void reusesUsdtPriceAlreadyFetched() {
        RecordingClient telegram = new RecordingClient();
        CryptoService withUsdtInList = new CryptoService(
                new UpbitApi(RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(), "https://example.invalid"),
                noCryptoResolver()) {
            @Override
            public List<CryptoQuote> quotesOf(List<String> markets) {
                return List.of(btc(new BigDecimal("63703.69")),
                        new CryptoQuote("테더", "KRW-USDT", NOW,
                                Quote.of(new BigDecimal("1384")), Quote.NOT_LISTED));
            }

            @Override
            public Optional<BigDecimal> usdtKrw() {
                throw new AssertionError("이미 받아 온 KRW-USDT를 두고 업비트를 또 불렀습니다");
            }
        };

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(false, false), withUsdtInList).run(false);

        assertThat(result.delivered()).containsExactly("코인");
        assertThat(telegram.sent.get(0))
                .as("목록 안의 1,384원으로 환산한다").contains("88,165,907 KRW");
    }

    /** 바이낸스 값이 붙은 코인 하나. {@code usdtKrw}가 {@code null}이면 환산 실패 상황이다. */
    private static CryptoService cryptoWithBinance(BigDecimal usdtKrw) {
        return new CryptoService(new UpbitApi(RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(), "https://example.invalid"),
                noCryptoResolver()) {
            @Override
            public List<CryptoQuote> quotesOf(List<String> markets) {
                return List.of(btc(new BigDecimal("63703.69")));
            }

            @Override
            public Optional<BigDecimal> usdtKrw() {
                return Optional.ofNullable(usdtKrw);
            }
        };
    }

    private static CryptoService crypto(boolean alive) {
        return new CryptoService(new UpbitApi(RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(), "https://example.invalid"),
                noCryptoResolver()) {
            @Override
            public List<CryptoQuote> quotesOf(List<String> markets) {
                return alive
                        ? List.of(btc(null))
                        : List.of();
            }
        };
    }

    /** 업비트 값은 항상 있고, 바이낸스는 인자로 준다({@code null}이면 미상장). */
    private static CryptoQuote btc(BigDecimal binanceUsdt) {
        return new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("89848000")),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(binanceUsdt));
    }

    /** 브리핑은 마켓 코드로 조회하므로 LLM 경로를 타지 않는다 — 실수로 타면 여기서 드러난다. */
    private static CryptoResolver noCryptoResolver() {
        return new CryptoResolver(null, null) {
            @Override
            public java.util.Optional<ResolvedCoin> resolve(String query) {
                throw new AssertionError("브리핑이 LLM 해석을 불렀습니다: " + query);
            }
        };
    }

    /** FMP 스텁 — 미국 조회가 필요 없는 테스트에서 실수로 나가면 바로 드러나게 한다. */
    private static FmpApi noFmp() {
        return new FmpApi(RestClient.builder(), "https://example.invalid", "k", null) {
            @Override
            public FmpApi.FmpQuote quote(String symbol) {
                return null;
            }
        };
    }

    static EconomyHelperProperties properties() {
        return new EconomyHelperProperties(Map.of(), null,
                new EconomyHelperProperties.Digest(
                        "Asia/Seoul", "0 0 9 * * *", Duration.ofDays(3),
                        List.of("코스피"), List.of("005930"), List.of("KRW-BTC"), List.of()),
                null, null);
    }

    static NewsItem item(String title) {
        return new NewsItem(NewsSource.BLOOMBERG, "Bloomberg", title, "본문",
                "https://example.com/" + title.hashCode(), NOW, true, 0.9);
    }

    /** 수집 호출 횟수까지 세어 "건너뛸 때는 수집도 안 한다"를 확인한다. */
    private static final class CountingFacade extends NewsFacade {
        private final List<NewsItem> items;
        private int calls;

        private CountingFacade(List<NewsItem> items) {
            super(null, null, null);
            this.items = items;
        }

        @Override
        public List<NewsItem> digest() {
            calls++;
            return items;
        }
    }

    /** Redis 없이 슬롯 선점만 흉내 낸다. */
    static class InMemoryHistory extends SendHistory {
        final Set<String> claimed = ConcurrentHashMap.newKeySet();

        InMemoryHistory() {
            super(null, properties());
        }

        @Override
        public boolean claim(String slot) {
            return claimed.add(slot);
        }

        @Override
        public void release(String slot) {
            claimed.remove(slot);
        }
    }

    static class RecordingClient extends TelegramClient {
        final List<String> sent = new ArrayList<>();

        RecordingClient() {
            super(RestClient.builder(), "https://example.invalid", "token", "chat", "");
        }

        @Override
        public void send(String text) {
            sent.add(text);
        }
    }
}
