package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.binance.BinanceApi;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.market.upbit.UpbitApi.UpbitTicker;
import io.saiden.economyhelper.market.upbit.UpbitMarket;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 이 클래스의 핵심 주장을 고정한다: <b>동명 후보를 24시간 거래대금으로 가른다.</b>
 *
 * <p>아래 값들은 2026-08-12 업비트 실측이다. 비트·이더·리플 셋 다 <b>이름만 보면 오답이
 * 앞에 왔고</b>, 거래대금으로는 전부 정답이 나왔다. LLM을 쓰지 않는 근거가 이것이다.
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
    @DisplayName("정확한 이름·심볼·영문명은 어느 쪽으로 쳐도 같은 답")
    void resolvesExactNamesInAnyLanguage() {
        CryptoService service = cryptoService(new RecordingApi());

        for (String query : List.of("비트코인", "BTC", "btc", "bitcoin", "비트코인 얼마")) {
            assertThat(service.quote(query))
                    .as("입력 '%s'", query)
                    .get().extracting(CryptoQuote::market).isEqualTo("KRW-BTC");
        }
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

        assertThat(quote.koreanName()).isEqualTo("비트코인");
        assertThat(quote.price()).isEqualByComparingTo("89848000");
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

        assertThat(quote.price()).isEqualByComparingTo(BigDecimal.valueOf(89_848_000));
        assertThat(quote.binanceUsdt()).as("모른다는 것을 0이 아니라 null로 남긴다").isNull();
        assertThat(service.quotesOf(List.of("KRW-BTC", "KRW-ETH")))
                .hasSize(2)
                .allSatisfy(each -> assertThat(each.binanceUsdt()).isNull());
    }

    @Test
    @DisplayName("바이낸스 값이 있으면 마켓별로 제자리에 붙는다")
    void attachesBinancePriceToMatchingMarket() {
        CryptoService service = cryptoService(new RecordingApi(),
                Map.of("BTCUSDT", "63703.69", "ETHUSDT", "1886.36"));

        assertThat(service.quotesOf(List.of("KRW-BTC", "KRW-ETH")))
                .extracting(CryptoQuote::market, CryptoQuote::binanceUsdt)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("KRW-BTC", new BigDecimal("63703.69")),
                        org.assertj.core.api.Assertions.tuple("KRW-ETH", new BigDecimal("1886.36")));
    }

    @Test
    @DisplayName("바이낸스에 없는 코인은 그 자리만 비운다 — 다른 코인까지 잃지 않는다")
    void leavesUnlistedCoinsEmpty() {
        CryptoService service = cryptoService(new RecordingApi(), Map.of("BTCUSDT", "63703.69"));

        assertThat(service.quotesOf(List.of("KRW-BTC", "KRW-ETH")))
                .extracting(CryptoQuote::binanceUsdt)
                .containsExactly(new BigDecimal("63703.69"), null);
    }

    @Test
    @DisplayName("USDT 원화값은 업비트 KRW-USDT를 쓴다 — 환율이 아니라 실제로 바꿀 수 있는 값이다")
    void readsUsdtPriceFromUpbit() {
        assertThat(cryptoService(new RecordingApi()).usdtKrw()).isEmpty();

        UpbitApi withUsdt = new RecordingApi() {
            @Override
            public List<UpbitTicker> tickers(List<String> markets) {
                return List.of(new UpbitTicker("KRW-USDT", BigDecimal.valueOf(1_384), null, null));
            }
        };
        assertThat(cryptoService(withUsdt).usdtKrw())
                .contains(BigDecimal.valueOf(1_384));
    }

    @Test
    @DisplayName("업비트가 죽으면 USDT 환산도 포기한다 — 예외를 위로 던지면 시세 전체가 막힌다")
    void returnsEmptyUsdtWhenUpbitFails() {
        UpbitApi exploding = new RecordingApi() {
            @Override
            public List<UpbitTicker> tickers(List<String> markets) {
                throw new IllegalStateException("업비트 502");
            }
        };

        assertThat(cryptoService(exploding).usdtKrw()).isEmpty();
    }

    /** 바이낸스가 아무것도 못 주는 상태. 기존 단언은 업비트만 보므로 이게 기본값이다. */
    private static CryptoService cryptoService(UpbitApi upbit) {
        return cryptoService(upbit, Map.of());
    }

    private static CryptoService cryptoService(UpbitApi upbit, Map<String, String> binancePrices) {
        return new CryptoService(upbit, new BinanceApi(RestClient.builder(), "https://example.invalid") {
            @Override
            public List<BinancePrice> prices(List<String> symbols) {
                return symbols.stream()
                        .filter(binancePrices::containsKey)
                        .map(symbol -> new BinancePrice(symbol, new BigDecimal(binancePrices.get(symbol))))
                        .toList();
            }
        });
    }

    /** 바이낸스가 죽은 상태 — 지역 차단·타임아웃·브레이커 열림을 모두 대표한다. */
    private static CryptoService cryptoServiceWithDeadBinance(UpbitApi upbit) {
        return new CryptoService(upbit, new BinanceApi(RestClient.builder(), "https://example.invalid") {
            @Override
            public List<BinancePrice> prices(List<String> symbols) {
                throw new IllegalStateException("451 지역 차단");
            }
        });
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
                            market, TICKERS.get(market)[0], TICKERS.get(market)[1], 1_786_497_710_484L))
                    .toList();
        }
    }
}
