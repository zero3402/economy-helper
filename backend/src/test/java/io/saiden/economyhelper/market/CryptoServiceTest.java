package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import io.saiden.economyhelper.market.binance.BinanceApi;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.market.upbit.UpbitApi.UpbitTicker;
import io.saiden.economyhelper.market.upbit.UpbitMarket;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 두 갈래를 함께 고정한다.
 *
 * <p><b>LLM 폴백(업비트 이름 매칭)</b> — 동명 후보를 24시간 거래대금으로 가른다. 아래 값들은
 * 2026-08-12 업비트 실측이다. 비트·이더·리플 셋 다 <b>이름만 보면 오답이 앞에 왔고</b>,
 * 거래대금으로는 전부 정답이 나왔다. LLM이 죽어도 이 경로가 남아 있어야 하는 근거다.
 *
 * <p><b>LLM 경로</b> — 티커 하나로 두 거래소를 각각 물어, 한쪽에 없어도 다른 쪽을 내보낸다.
 * 그리고 <b>없는 이유를 뭉개지 않는다</b>: {@code NOT_LISTED}(영영 없음)와
 * {@code FAILED}(잠시 뒤 다시)를 가르는 것이 이 개편의 요지다.
 */
class CryptoServiceTest {

    private static final List<UpbitMarket> MARKETS = List.of(
            UpbitMarket.of("KRW-BTC", "비트코인", "Bitcoin"),
            UpbitMarket.of("KRW-BCH", "비트코인캐시", "Bitcoin Cash"),
            UpbitMarket.of("KRW-TAO", "비트텐서", "Bittensor"),
            UpbitMarket.of("KRW-ARB", "아비트럼", "Arbitrum"),
            UpbitMarket.of("KRW-ETH", "이더리움", "Ethereum"),
            UpbitMarket.of("KRW-ETHFI", "이더파이", "ether.fi"),
            UpbitMarket.of("KRW-XRP", "엑스알피(리플)", "XRP"),
            UpbitMarket.of("KRW-RLUSD", "리플유에스디", "Ripple USD"));

    /** market -> (현재가, 24h 거래대금). 거래대금은 실측 규모를 그대로 옮겼다. */
    private static final Map<String, BigDecimal[]> TICKERS = Map.of(
            "KRW-BTC", money(89_848_000, 59_143_942_921L),
            "KRW-BCH", money(700_000, 810_104_088L),
            "KRW-TAO", money(400_000, 1_255_354_197L),
            "KRW-ARB", money(500, 1_259_430_482L),
            "KRW-ETH", money(4_400_000, 26_548_949_783L),
            "KRW-ETHFI", money(1_500, 1_321_230_531L),
            "KRW-XRP", money(3_100, 75_923_305_997L),
            "KRW-RLUSD", money(1_400, 20_711_969L));

    private static BigDecimal[] money(long price, long volume) {
        return new BigDecimal[] {BigDecimal.valueOf(price), BigDecimal.valueOf(volume)};
    }

    @Test
    @DisplayName("이름만으로는 오답이 앞에 오는 약칭을 거래대금이 가른다")
    void volumeResolvesAmbiguity() {
        RecordingApi api = new RecordingApi();
        CryptoService service = cryptoService(api);

        // 이름 매칭만 하면 각각 비트코인캐시·이더파이·리플유에스디가 걸렸던 것들이다
        assertThat(service.quote("비트")).get().extracting(CryptoQuote::market).isEqualTo("KRW-BTC");
        assertThat(service.quote("이더")).get().extracting(CryptoQuote::market).isEqualTo("KRW-ETH");
        assertThat(service.quote("리플")).get().extracting(CryptoQuote::market).isEqualTo("KRW-XRP");
    }

    @Test
    @DisplayName("후보 전부를 한 번에 조회한다 — 후보마다 부르면 초당 10회 제한에 닿는다")
    void fetchesAllCandidatesInOneCall() {
        RecordingApi api = new RecordingApi();
        cryptoService(api).quote("비트");

        assertThat(api.tickerCalls).hasSize(1);
        assertThat(api.tickerCalls.get(0)).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("현재가와 체결 시각을 그대로 옮긴다")
    void carriesPriceAndTimestamp() {
        CryptoQuote quote = cryptoService(new RecordingApi()).quote("비트코인").orElseThrow();

        assertThat(quote.name()).isEqualTo("비트코인");
        assertThat(quote.upbit().price()).isEqualByComparingTo("89848000");
        assertThat(quote.at()).isNotNull();
    }

    @Test
    @DisplayName("걸리는 코인이 없으면 빈 결과 — 아무거나 돌려주면 사용자가 오해한다")
    void returnsEmptyWhenNothingMatches() {
        CryptoService service = cryptoService(new RecordingApi());

        assertThat(service.quote("없는코인zzz")).isEmpty();
        assertThat(service.quote("")).isEmpty();
        assertThat(service.quote(null)).isEmpty();
    }

    @Test
    @DisplayName("업비트가 죽어도 예외를 밖으로 내보내지 않는다 — 웹훅은 어떤 경우에도 200이어야 한다")
    void degradesWhenUpbitFails() {
        UpbitApi exploding = new RecordingApi() {
            @Override
            public List<UpbitMarket> krwMarkets() {
                throw new IllegalStateException("서킷브레이커 열림");
            }
        };

        assertThat(cryptoService(exploding).quote("비트코인")).isEmpty();
        assertThat(cryptoService(exploding).quotesOf(List.of("KRW-BTC"))).isEmpty();
    }

    @Test
    @DisplayName("설정에 박힌 마켓 코드로도 조회한다 — 아침 브리핑이 이 경로를 쓴다")
    void quotesConfiguredMarkets() {
        List<CryptoQuote> quotes =
                cryptoService(new RecordingApi()).quotesOf(List.of("KRW-BTC", "KRW-ETH"));

        assertThat(quotes).extracting(CryptoQuote::market)
                .containsExactlyInAnyOrder("KRW-BTC", "KRW-ETH");
    }

    @Test
    @DisplayName("업비트에 없는 마켓 코드는 조용히 버린다 — 설정 오타가 발송 전체를 막으면 안 된다")
    void skipsUnknownConfiguredMarkets() {
        RecordingApi api = new RecordingApi();

        assertThat(cryptoService(api).quotesOf(List.of("KRW-BTC", "KRW-없는것")))
                .extracting(CryptoQuote::market).containsExactly("KRW-BTC");
        assertThat(cryptoService(api).quotesOf(List.of("KRW-전부없음"))).isEmpty();
    }

    // --- 바이낸스 ---------------------------------------------------------

    @Test
    @DisplayName("바이낸스가 터져도 업비트 시세는 그대로 나간다 — 거래소를 더했다고 되던 게 안 되면 개악이다")
    void keepsUpbitWhenBinanceIsDown() {
        CryptoService service = cryptoServiceWithDeadBinance(new RecordingApi());

        CryptoQuote quote = service.quote("비트코인").orElseThrow();

        assertThat(quote.upbit().price()).isEqualByComparingTo(BigDecimal.valueOf(89_848_000));
        assertThat(quote.binance().state())
                .as("장애는 미상장이 아니다 — 사용자에게 '잠시 뒤 다시'와 '영영 없음'은 다른 말이다")
                .isEqualTo(CryptoQuote.Quote.State.FAILED);
        assertThat(service.quotesOf(List.of("KRW-BTC", "KRW-ETH")))
                .hasSize(2)
                .allSatisfy(each -> assertThat(each.binance().hasPrice()).isFalse());
    }

    @Test
    @DisplayName("바이낸스 값이 있으면 마켓별로 제자리에 붙는다")
    void attachesBinancePriceToMatchingMarket() {
        CryptoService service = cryptoService(new RecordingApi(),
                Map.of("BTCUSDT", "63703.69", "ETHUSDT", "1886.36"));

        assertThat(service.quotesOf(List.of("KRW-BTC", "KRW-ETH")))
                .extracting(CryptoQuote::market, each -> each.binance().price())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("KRW-BTC", new BigDecimal("63703.69")),
                        org.assertj.core.api.Assertions.tuple("KRW-ETH", new BigDecimal("1886.36")));
    }

    @Test
    @DisplayName("바이낸스에 없는 코인은 그 자리만 비운다 — 다른 코인까지 잃지 않는다")
    void leavesUnlistedCoinsEmpty() {
        CryptoService service = cryptoService(new RecordingApi(), Map.of("BTCUSDT", "63703.69"));

        assertThat(service.quotesOf(List.of("KRW-BTC", "KRW-ETH")))
                .extracting(each -> each.binance().price())
                .containsExactly(new BigDecimal("63703.69"), null);
    }

    @Test
    @DisplayName("테더는 USDTUSD로 묻는다 — USDTUSDT가 없다고 미상장으로 찍던 자리다")
    void asksBinanceForTetherWithUsdSymbol() {
        UpbitApi upbit = new RecordingApi() {
            @Override
            public List<UpbitMarket> krwMarkets() {
                return List.of(UpbitMarket.of("KRW-USDT", "테더", "Tether"));
            }

            @Override
            public List<UpbitTicker> tickers(List<String> markets) {
                return List.of(new UpbitTicker("KRW-USDT", BigDecimal.valueOf(1_425), null, null, null));
            }
        };
        CryptoService service = cryptoService(upbit, Map.of("USDTUSD", "0.99906"));

        CryptoQuote quote = service.quotesOf(List.of("KRW-USDT")).get(0);

        assertThat(quote.binance().price()).isEqualByComparingTo("0.99906");
        assertThat(quote.binanceUnit())
                .as("호가가 USD라 원화 환산은 환율로 해야 한다")
                .isEqualTo("USD");
    }

    // --- LLM은 업비트가 못 풀 때만 부른다 -------------------------------------

    @Test
    @DisplayName("업비트 이름이 걸리면 LLM을 부르지 않는다 — 캐시된 목록에 대한 순수 계산이라 공짜다")
    void skipsLlmWhenUpbitNameMatches() {
        CryptoService service = cryptoService(new RecordingApi(), Map.of("BTCUSDT", "63703.69"),
                explodingResolver());

        for (String query : List.of("비트코인", "BTC", "bitcoin", "비트", "비트코인 얼마")) {
            CryptoQuote quote = service.quote(query).orElseThrow();
            assertThat(quote.market()).as("입력 '%s'", query).isEqualTo("KRW-BTC");
            assertThat(quote.binance().price()).isEqualByComparingTo("63703.69");
        }
    }

    @Test
    @DisplayName("업비트에 없는 코인도 바이낸스로 답한다 — 이름 매칭만 쓰면 시작조차 못 하던 것들이다")
    void answersCoinsMissingFromUpbit() {
        CryptoService service = cryptoService(new RecordingApi(), Map.of("BNBUSDT", "612.40"),
                resolverOf("BNB"));

        CryptoQuote quote = service.quote("바이낸스코인").orElseThrow();

        assertThat(quote.name())
                .as("업비트 한글명이 없으면 티커다 — LLM에게 '비앤비' 같은 표기를 받아 쓰지 않는다")
                .isEqualTo("BNB");
        assertThat(quote.market()).as("업비트 마켓 코드가 없으므로 비운다").isNull();
        assertThat(quote.upbit().state()).isEqualTo(CryptoQuote.Quote.State.NOT_LISTED);
        assertThat(quote.binance().price()).isEqualByComparingTo("612.40");
    }

    @Test
    @DisplayName("업비트 체결 시각을 그대로 쓴다 — 조회 시각으로 덮으면 경로마다 값이 달라진다")
    void carriesUpbitTradeTimestamp() {
        CryptoQuote quote = cryptoService(new RecordingApi(), Map.of("BTCUSDT", "63703.69"),
                explodingResolver()).quote("비트코인").orElseThrow();

        assertThat(quote.at()).isEqualTo(java.time.Instant.ofEpochMilli(1_786_497_710_484L));
    }

    @Test
    @DisplayName("두 거래소 어디에도 없으면 빈 결과 — LLM 환각이 여기서 걸러진다")
    void returnsEmptyWhenNeitherExchangeHasTheTicker() {
        CryptoService service = cryptoService(new RecordingApi(), Map.of(),
                resolverOf("ZZZ"));

        assertThat(service.quote("없는코인zzz")).isEmpty();
    }

    @Test
    @DisplayName("바이낸스 400만 미상장이다 — 451·429까지 묶으면 재시도하면 될 것을 '없는 코인'이라 답하게 된다")
    void separatesInvalidSymbolFromOutage() {
        CryptoService invalidSymbol = new CryptoService(new RecordingApi(),
                explodingBinance(new HttpClientErrorException(HttpStatus.BAD_REQUEST)),
                resolverOf("BNB"));
        CryptoService blocked = new CryptoService(new RecordingApi(),
                explodingBinance(new HttpClientErrorException(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS)),
                resolverOf("BNB"));

        assertThat(invalidSymbol.quote("바이낸스코인"))
                .as("Invalid symbol — 업비트에도 바이낸스에도 없으니 '찾지 못했다'가 맞다")
                .isEmpty();
        assertThat(blocked.quote("바이낸스코인")).get()
                .extracting(quote -> quote.binance().state())
                .as("지역 차단은 장애다 — '없는 코인'이 아니라 '잠시 뒤 다시'다")
                .isEqualTo(CryptoQuote.Quote.State.FAILED);
    }

    @Test
    @DisplayName("업비트 마켓 목록이 죽으면 미상장이 아니라 조회 실패다 — 상장 여부를 '모르는' 것이다")
    void marksUpbitFailedWhenMarketListIsDown() {
        UpbitApi exploding = new RecordingApi() {
            @Override
            public List<UpbitMarket> krwMarkets() {
                throw new IllegalStateException("서킷브레이커 열림");
            }
        };

        CryptoQuote quote = cryptoService(exploding, Map.of("BTCUSDT", "63703.69"),
                resolverOf("BTC")).quote("비트코인").orElseThrow();

        assertThat(quote.upbit().state()).isEqualTo(CryptoQuote.Quote.State.FAILED);
        assertThat(quote.binance().price())
                .as("업비트가 죽어도 바이낸스 값은 그대로 나간다").isEqualByComparingTo("63703.69");
    }

    /** 바이낸스가 아무것도 못 주는 상태. 기존 단언은 업비트만 보므로 이게 기본값이다. */
    private static CryptoService cryptoService(UpbitApi upbit) {
        return cryptoService(upbit, Map.of());
    }

    private static CryptoService cryptoService(UpbitApi upbit, Map<String, String> binancePrices) {
        return cryptoService(upbit, binancePrices, noResolver());
    }

    private static CryptoService cryptoService(UpbitApi upbit, Map<String, String> binancePrices,
                                               CryptoResolver resolver) {
        return new CryptoService(upbit, new BinanceApi(RestClient.builder(), "https://example.invalid") {
            @Override
            public List<BinancePrice> prices(List<String> symbols) {
                return symbols.stream()
                        .filter(binancePrices::containsKey)
                        .map(symbol -> new BinancePrice(symbol, new BigDecimal(binancePrices.get(symbol)), null))
                        .toList();
            }
        }, resolver);
    }

    /** 바이낸스가 죽은 상태 — 지역 차단·타임아웃·브레이커 열림을 모두 대표한다. */
    private static CryptoService cryptoServiceWithDeadBinance(UpbitApi upbit) {
        return new CryptoService(upbit, explodingBinance(new IllegalStateException("451 지역 차단")),
                noResolver());
    }

    private static BinanceApi explodingBinance(RuntimeException failure) {
        return new BinanceApi(RestClient.builder(), "https://example.invalid") {
            @Override
            public List<BinancePrice> prices(List<String> symbols) {
                throw failure;
            }
        };
    }

    /** LLM이 못 푼 상태 — 업비트 이름 매칭 경로를 고정한다. */
    private static CryptoResolver noResolver() {
        return new CryptoResolver(null, null) {
            @Override
            public Optional<ResolvedCoin> resolve(String query) {
                return Optional.empty();
            }
        };
    }

    /** 불리면 안 되는 상태 — 업비트가 푸는 검색어에 Gemini가 나가면 여기서 드러난다. */
    private static CryptoResolver explodingResolver() {
        return new CryptoResolver(null, null) {
            @Override
            public Optional<ResolvedCoin> resolve(String query) {
                throw new AssertionError("업비트가 푸는 검색어에 LLM을 불렀습니다: " + query);
            }
        };
    }

    /** LLM이 티커를 준 상태. 프롬프트·파싱은 이 클래스의 관심사가 아니다. */
    private static CryptoResolver resolverOf(String symbol) {
        return new CryptoResolver(null, null) {
            @Override
            public Optional<ResolvedCoin> resolve(String query) {
                return Optional.of(new ResolvedCoin(symbol));
            }
        };
    }

    /** HTTP는 {@code UpbitApiTest}가 따로 본다. 여기서는 해석 규칙만 본다. */
    private static class RecordingApi extends UpbitApi {
        private final List<List<String>> tickerCalls = new ArrayList<>();

        RecordingApi() {
            super(RestClient.builder(), "https://example.invalid");
        }

        @Override
        public List<UpbitMarket> krwMarkets() {
            return MARKETS;
        }

        @Override
        public List<UpbitTicker> tickers(List<String> markets) {
            tickerCalls.add(markets);
            return markets.stream()
                    .filter(TICKERS::containsKey)
                    .map(market -> new UpbitTicker(
                            market, TICKERS.get(market)[0], TICKERS.get(market)[1],null,  1_786_497_710_484L))
                    .toList();
        }
    }
}
