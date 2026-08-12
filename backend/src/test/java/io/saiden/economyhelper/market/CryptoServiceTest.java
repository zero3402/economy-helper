package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

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
        CryptoService service = new CryptoService(api);

        // 이름 매칭만 하면 각각 비트코인캐시·이더파이·리플유에스디가 걸렸던 것들이다
        assertThat(service.quote("비트")).get().extracting(CryptoQuote::market).isEqualTo("KRW-BTC");
        assertThat(service.quote("이더")).get().extracting(CryptoQuote::market).isEqualTo("KRW-ETH");
        assertThat(service.quote("리플")).get().extracting(CryptoQuote::market).isEqualTo("KRW-XRP");
    }

    @Test
    @DisplayName("정확한 이름·심볼·영문명은 어느 쪽으로 쳐도 같은 답")
    void resolvesExactNamesInAnyLanguage() {
        CryptoService service = new CryptoService(new RecordingApi());

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
        new CryptoService(api).quote("비트");

        assertThat(api.tickerCalls).hasSize(1);
        assertThat(api.tickerCalls.get(0)).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("현재가와 체결 시각을 그대로 옮긴다")
    void carriesPriceAndTimestamp() {
        CryptoQuote quote = new CryptoService(new RecordingApi()).quote("비트코인").orElseThrow();

        assertThat(quote.koreanName()).isEqualTo("비트코인");
        assertThat(quote.price()).isEqualByComparingTo("89848000");
        assertThat(quote.at()).isNotNull();
    }

    @Test
    @DisplayName("걸리는 코인이 없으면 빈 결과 — 아무거나 돌려주면 사용자가 오해한다")
    void returnsEmptyWhenNothingMatches() {
        CryptoService service = new CryptoService(new RecordingApi());

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

        assertThat(new CryptoService(exploding).quote("비트코인")).isEmpty();
        assertThat(new CryptoService(exploding).quotesOf(List.of("KRW-BTC"))).isEmpty();
    }

    @Test
    @DisplayName("설정에 박힌 마켓 코드로도 조회한다 — 아침 브리핑이 이 경로를 쓴다")
    void quotesConfiguredMarkets() {
        List<CryptoQuote> quotes =
                new CryptoService(new RecordingApi()).quotesOf(List.of("KRW-BTC", "KRW-ETH"));

        assertThat(quotes).extracting(CryptoQuote::market)
                .containsExactlyInAnyOrder("KRW-BTC", "KRW-ETH");
    }

    @Test
    @DisplayName("업비트에 없는 마켓 코드는 조용히 버린다 — 설정 오타가 발송 전체를 막으면 안 된다")
    void skipsUnknownConfiguredMarkets() {
        RecordingApi api = new RecordingApi();

        assertThat(new CryptoService(api).quotesOf(List.of("KRW-BTC", "KRW-없는것")))
                .extracting(CryptoQuote::market).containsExactly("KRW-BTC");
        assertThat(new CryptoService(api).quotesOf(List.of("KRW-전부없음"))).isEmpty();
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
