package io.saiden.economyhelper.digest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.saiden.economyhelper.market.StockListings;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.market.StockService.Answer;
import io.saiden.economyhelper.market.chart.DailyBar;
import io.saiden.economyhelper.market.data.DataGoStockClient;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.telegram.TelegramClient;
import io.saiden.economyhelper.support.TestProperties;
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
            public void send(String text, boolean preview) {
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
            public void send(String text, boolean preview) {
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
            public void send(String text, boolean preview) {
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
                .contains("<b>증시</b>")
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
        assertThat(telegram.sent.get(0)).contains("<b>환율</b>").contains("1 USD =");
        assertThat(telegram.sent.get(1)).contains("<b>증시</b>").contains("삼성전자");
        assertThat(telegram.sent.get(2)).contains("<b>코인</b>").contains("<b>BTC</b>");
        assertThat(telegram.sent.get(3)).contains("유가 상승");
    }

    @Test
    @DisplayName("뉴스는 기사마다 한 통이라 세 건이면 여섯 통이 나간다 — 카드가 제 기사에 붙는다")
    void splitsNewsIntoOneMessagePerArticle() {
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(),
                new CountingFacade(List.of(item("첫 번째"), item("두 번째"), item("세 번째"))),
                fx(true), stock(true), crypto(true)).run(false);

        assertThat(result.delivered())
                .as("통이 늘어도 결과는 이름 단위다 — '뉴스'가 세 번 남으면 무엇이 나갔는지 흐려진다")
                .containsExactly("환율", "증시", "코인", "뉴스");
        assertThat(telegram.sent).hasSize(6);
        assertThat(telegram.sent.get(3)).startsWith("<b>뉴스 1/3</b>").contains("첫 번째");
        assertThat(telegram.sent.get(5)).startsWith("<b>뉴스 3/3</b>").contains("세 번째");
        assertThat(telegram.previews).containsExactly(false, false, false, true, true, true);
    }

    @Test
    @DisplayName("뉴스 열 건이면 열세 통이다 — 클래스 주석이 말하는 그 상한을 값으로 못 박는다")
    void sendsThirteenMessagesForTheProductionNewsCount() {
        // 운영 기본값이 코인 5 + 경제 5이므로 시세 셋 + 뉴스 열이 실제 발송 모양이다.
        // 같은 방에 초당 한 통(TelegramClient가 발송 시작 사이 간격을 지킨다)이라 스물네 통이 ~25초인데
        // 발송 창이 두 시간이라 문제가 되지 않는다
        RecordingClient telegram = new RecordingClient();
        List<NewsItem> ten = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> item("기사 " + i))
                .toList();

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(ten),
                fx(true), stock(true), crypto(true)).run(false);

        assertThat(result.delivered()).containsExactly("환율", "증시", "코인", "뉴스");
        assertThat(telegram.sent).hasSize(13);
        assertThat(telegram.sent.get(3)).startsWith("<b>뉴스 1/10</b>").contains("기사 1");
        assertThat(telegram.sent.get(12)).startsWith("<b>뉴스 10/10</b>").contains("기사 10");
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

    @Test
    @DisplayName("수집이 예외로 새면 슬롯을 되돌린다 — 안 그러면 그날 창의 나머지 틱이 전부 「이미 보냈다」다")
    void releasesTheSlotWhenCollectionEscapes() {
        // section()은 RuntimeException을 값으로 바꾸지만 Error와 인터럽트는 그대로 샌다 — 배포 중 종료가 그 경로다
        InMemoryHistory history = new InMemoryHistory();
        NewsFacade exploding = new CountingFacade(List.of()) {
            @Override
            public List<NewsItem> digest() {
                throw new AssertionError("종료 신호");
            }
        };

        assertThatThrownBy(() -> job(new RecordingClient(), history, exploding).run(false))
                .isInstanceOf(AssertionError.class);

        assertThat(history.claimed).as("아무것도 못 보냈으니 다음 틱이 다시 시도할 수 있어야 한다").doesNotContain(SLOT);
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
        return new FxService(List.of(), null) {
            @Override
            public Optional<FxRate> usdToKrw() {
                return alive
                        ? Optional.of(new FxRate("USD", "KRW", new BigDecimal("1415"), FxSource.KEXIM, NOW))
                        : Optional.empty();
            }
        };
    }

    /** {@code answersOf}가 몇 번 불렸는지 센다 — 글과 차트가 조회를 나눠 쓰는지 보는 자리. */
    private static final class CountingStock extends StockService {
        private int answerCalls;

        private CountingStock() {
            super(List.of(), List.of(), noNames(), new StockListings(List::of), null,
                    code -> java.util.Optional.empty(), symbol -> java.util.Optional.empty(), null);
        }

        @Override
        public List<StockQuote> indicesOf(List<EconomyHelperProperties.Index> indices) {
            return List.of();
        }

        @Override
        public List<Answer> answersOf(List<String> codes) {
            answerCalls++;
            return List.of(Answer.of(new StockQuote("삼성전자", new BigDecimal("239500"), null,
                    StockQuote.Money.KRW, StockQuote.Market.DOMESTIC,
                    io.saiden.economyhelper.market.StockSource.DATA_GO, BASIS, false)));
        }

        @Override
        public List<DailyBar> dailyBarsOf(StockService.Series series) {
            return kospiBars();
        }
    }

    @Test
    @DisplayName("증시 통이 같은 조회를 두 번 하지 않는다 — 글이 쓴 답을 차트가 그대로 받는다")
    void asksForTheStockAnswersOnlyOnce() {
        // ⚠️ 예전에는 글이 answersOf를 부르고 차트가 같은 것을 그대로 다시 불렀다. 시세 캐시가
        //    1분인데 증시 통 하나가 KIS를 시세 9회 + 일봉 8회 쓰고 호출 사이 1초를 지키므로,
        //    캐시가 그 사이에 식으면 두 번째 조회가 KIS 호출을 통째로 다시 태운다. 게다가
        //    그때는 글에 찍힌 값과 차트 캡션이 서로 다른 조회에서 온 것이 된다
        CountingStock stock = new CountingStock();

        DigestResult result = job(new RecordingClient(), new InMemoryHistory(),
                new CountingFacade(List.of()), fx(false), stock, crypto(false)).run(false);

        assertThat(result.delivered()).containsExactly("증시");
        assertThat(stock.answerCalls)
                .as("글과 차트가 한 조회를 나눠 쓴다")
                .isEqualTo(1);
    }

    private static StockService stock(boolean alive) {
        return stock(alive, alive);
    }

    private static StockService stock(boolean indicesAlive, boolean stocksAlive) {
        return new StockService(List.of(), List.of(), noNames(), new StockListings(List::of), null, code -> java.util.Optional.empty(), symbol -> java.util.Optional.empty(), null) {
            @Override
            public List<StockQuote> indicesOf(List<EconomyHelperProperties.Index> indices) {
                return indicesAlive
                        ? List.of(new StockQuote("코스피", new BigDecimal("6345.53"), null,
                                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, io.saiden.economyhelper.market.StockSource.DATA_GO,
                                BASIS, false))
                        : List.of();
            }

            // ⚠️ quotesOf가 아니라 answersOf다. 브리핑이 국내 종목에 전망을 붙이면서 그쪽으로
            //    옮겨 갔으므로, quotesOf를 오버라이드해 두면 페이크가 **가로채지 못하고**
            //    실물 경로가 돈다 — 테스트가 조용히 딴 것을 시험하게 된다
            @Override
            public List<Answer> answersOf(List<String> codes) {
                return stocksAlive
                        ? List.of(Answer.of(new StockQuote("삼성전자", new BigDecimal("239500"), null,
                                StockQuote.Money.KRW, StockQuote.Market.DOMESTIC, io.saiden.economyhelper.market.StockSource.DATA_GO,
                                BASIS, false)))
                        : List.of();
            }

            // ⚠️ 일봉도 가로채야 한다. 실물은 KisStockApi를 타고 이 페이크는 그 자리에 null을
            //    넣으므로, 오버라이드하지 않으면 차트 경로가 **삼켜진 NPE로** 돈다 —
            //    테스트는 초록인데 지수 차트를 아무도 안 보는 상태가 된다
            @Override
            public List<DailyBar> dailyBarsOf(StockService.Series series) {
                return indicesAlive ? kospiBars() : List.of();
            }
        };
    }

    @Test
    @DisplayName("코인 통의 원화 환산과 김프는 잡이 들고 있던 환율로 만든다")
    void cryptoMessageConvertsWithTheSameFxRate() {
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(true), stock(false, false), cryptoWithBinance()).run(false);

        assertThat(result.delivered()).containsExactly("환율", "코인");
        assertThat(telegram.sent.get(1))
                .contains("<b>코인</b>")
                .contains("업비트").contains("89,848,000 KRW")
                .contains("바이낸스").contains("63,703.69 USDT")
                // 63,703.69 × 1,415 = 90,140,721.35 → 90,140,721
                .as("환율 통에 찍힌 값과 같은 환율로 환산해야 두 통이 어긋나지 않는다")
                .contains("90,140,721 KRW")
                // 89,848,000 ÷ 90,140,721.35 − 1 = -0.3247…%
                .contains("김프\n🔵 -0.32%");
    }

    @Test
    @DisplayName("환율을 못 가져오면 USDT 값만 나간다 — 환산 실패가 코인 통을 통째로 막지 않는다")
    void cryptoMessageSurvivesMissingFxRate() {
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(false, false), cryptoWithBinance()).run(false);

        assertThat(result.delivered()).containsExactly("코인");
        assertThat(telegram.sent.get(0))
                .contains("63,703.69 USDT")
                .doesNotContain("90,140,721")
                .doesNotContain("김프");
    }

    /** 바이낸스 값이 붙은 코인 하나. 원화 환산은 잡이 넘기는 환율이 정한다. */
    private static CryptoService cryptoWithBinance() {
        return new CryptoService(new UpbitApi(RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(),
                        new io.saiden.economyhelper.market.binance.BinanceBanGate(null, java.time.Clock.systemUTC()),
                        "https://example.invalid", ""),
                noCryptoResolver(), Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override
            public List<CryptoQuote> quotesOf(List<String> markets) {
                return List.of(btc(new BigDecimal("63703.69")));
            }
        };
    }

    private static CryptoService crypto(boolean alive) {
        return new CryptoService(new UpbitApi(RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(),
                        new io.saiden.economyhelper.market.binance.BinanceBanGate(null, java.time.Clock.systemUTC()),
                        "https://example.invalid", ""),
                noCryptoResolver(), Clock.fixed(NOW, ZoneOffset.UTC)) {
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
                Quote.of(new BigDecimal("89848000"), null),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(binanceUsdt, null));
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

    /** 이름 검색 스텁 — 브리핑은 코드로만 조회한다. 실수로 나가면 바로 드러나게 한다. */
    private static DataGoStockClient noNames() {
        return new DataGoStockClient(null, null, null) {
            @Override
            public java.util.Optional<StockQuote> byName(String name) {
                throw new AssertionError("브리핑이 이름 검색을 불렀습니다: " + name);
            }
        };
    }

    @Test
    @DisplayName("증시 통이 지수 차트를 함께 보낸다 — 글 다음에, 단위 없이")
    void sendsAnIndexChartAfterTheStockMessage() {
        // ⚠️ 이 자리가 통째로 비어 있었다. KIS 응답에 지수 일봉이 오는데도 배선을 안 해서
        //    브리핑 증시 통의 여섯 심볼 중 차트가 하나도 없었다
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, new InMemoryHistory(), new CountingFacade(List.of()),
                fx(false), stock(true, false), crypto(false)).run(false);

        assertThat(result.delivered()).containsExactly("증시");
        assertThat(telegram.captions)
                .singleElement()
                .as("지수는 단위가 없다 — 「6,869.83 KRW」라고 적을 근거가 없다")
                .isEqualTo("""
                        <b>코스피</b>

                        최근 2거래일
                        6,801.20 → 6,869.83
                        🔴 +1.01%
                        2026.08.17(월) ~ 2026.08.18(화)""");
        assertThat(telegram.order)
                .as("사진이 글보다 먼저 가면 무엇의 그림인지 알 수 없다")
                .containsExactly("글", "사진");
    }

    /** 코스피 실측 눈금대의 일봉 — 삼성전자(20만대)와 축 계산이 다른 자리를 탄다. */
    private static List<DailyBar> kospiBars() {
        return List.of(
                new DailyBar(java.time.LocalDate.of(2026, 8, 17), new BigDecimal("6801.20")),
                new DailyBar(java.time.LocalDate.of(2026, 8, 18), new BigDecimal("6869.83")));
    }

    static EconomyHelperProperties properties() {
        return TestProperties.builder()
                .feeds(Map.of())
                .digest(new EconomyHelperProperties.Digest(
                        "Asia/Seoul", Duration.ofDays(3),
                        List.of(new EconomyHelperProperties.Index("코스피", "0001")),
                        List.of("005930"), List.of("KRW-BTC"), List.of()))
                .build();
    }

    static NewsItem item(String title) {
        return new NewsItem("CNBC", title, "본문",
                "https://example.com/" + title.hashCode(), NOW, true);
    }

    /** 수집 호출 횟수까지 세어 "건너뛸 때는 수집도 안 한다"를 확인한다. */
    private static class CountingFacade extends NewsFacade {
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

    /**
     * 발송을 가로채 기록한다.
     *
     * <p><b>미리보기 여부까지 받는 오버로드를 재정의한다.</b> {@code send(String)}만 막으면
     * 실제 발송 경로가 그 아래 오버로드를 부르므로 테스트가 바깥으로 HTTP를 쏜다.
     */
    static class RecordingClient extends TelegramClient {
        final List<String> sent = new ArrayList<>();
        final List<Boolean> previews = new ArrayList<>();
        /** 사진의 caption만 담는다 — 그림은 골든이 못 보고 낱말은 caption에 다 있다. */
        final List<String> captions = new ArrayList<>();
        /** 글과 사진이 섞인 <b>보낸 순서</b>. 사진이 글 앞에 가면 무엇의 그림인지 알 수 없다. */
        final List<String> order = new ArrayList<>();

        RecordingClient() {
            super(RestClient.builder(), "https://example.invalid", "token", "chat", "", java.time.Duration.ZERO);
        }

        @Override
        public void send(String text, boolean preview) {
            sent.add(text);
            previews.add(preview);
            order.add("글");
        }

        @Override
        public void sendPhoto(byte[] png, String caption) {
            captions.add(caption);
            order.add("사진");
        }
    }
}
