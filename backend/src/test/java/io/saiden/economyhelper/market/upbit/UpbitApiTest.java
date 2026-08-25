package io.saiden.economyhelper.market.upbit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.upbit.UpbitApi.UpbitTicker;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * <b>이 파일이 없었다.</b> {@code CryptoServiceTest}의 주석이 {@code "HTTP는 UpbitApiTest가
 * 따로 본다"}고 적어 두었는데 <b>그런 파일이 없었다</b> — 그래서 업비트만 HTTP 계약이
 * 미검증인 채로 남았다. 형제 전부에는 있다({@code BinanceApiTest}·{@code FmpApiTest}·
 * {@code StockPriceApiTest}·{@code GeocodingApiTest}…). 주석이 구멍을 가리고 있었던 셈이다.
 *
 * <p>여기서 보는 것은 <b>계약</b>이다 — 원화 마켓만 걸러내는지, 후보를 한 호출로 묶는지,
 * 빈 목록에 헛호출을 안 하는지, {@code null} 응답을 실패로 다루는지.
 */
class UpbitApiTest {

    private WireMockServer server;
    private UpbitApi api;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        api = new UpbitApi(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stub(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("원화 마켓만 남긴다 — BTC 마켓은 값의 단위가 달라 섞으면 시세가 거짓이 된다")
    void keepsOnlyKrwMarkets() {
        // 실측 응답을 줄인 것이다. 업비트는 KRW·BTC·USDT 마켓을 한 목록에 담아 준다
        stub("/v1/market/all", """
                [{"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"},
                 {"market":"BTC-ETH","korean_name":"이더리움","english_name":"Ethereum"},
                 {"market":"USDT-SOL","korean_name":"솔라나","english_name":"Solana"},
                 {"market":"KRW-ETH","korean_name":"이더리움","english_name":"Ethereum"}]""");

        List<UpbitMarket> markets = api.krwMarkets();

        assertThat(markets).extracting(UpbitMarket::market)
                .containsExactly("KRW-BTC", "KRW-ETH");
        assertThat(markets).extracting(UpbitMarket::koreanName)
                .containsExactly("비트코인", "이더리움");
    }

    @Test
    @DisplayName("market이 없는 줄에서 죽지 않는다 — 한 줄이 깨지면 조회 전체가 NPE로 죽는다")
    void survivesARowWithoutAMarketCode() {
        stub("/v1/market/all", """
                [{"market":null,"korean_name":"이상","english_name":"Odd"},
                 {"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"}]""");

        assertThat(api.krwMarkets()).extracting(UpbitMarket::market).containsExactly("KRW-BTC");
    }

    @Test
    @DisplayName("후보 여럿을 한 호출로 묶는다 — 따로 부르면 초당 10회 제한에 금방 닿는다")
    void asksForEveryMarketInOneCall() {
        stub("/v1/ticker", """
                [{"market":"KRW-ETH","trade_price":3117000.0,"acc_trade_price_24h":194497300539.0,
                  "signed_change_rate":0.0064578624,"trade_timestamp":1787186239692},
                 {"market":"KRW-ETC","trade_price":9100.0,"acc_trade_price_24h":3135939618.0,
                  "signed_change_rate":-0.01,"trade_timestamp":1787186239692}]""");

        List<UpbitTicker> tickers = api.tickers(List.of("KRW-ETH", "KRW-ETC"));

        assertThat(tickers).hasSize(2);
        assertThat(tickers.get(0).tradePrice()).isEqualByComparingTo(new BigDecimal("3117000.0"));
        assertThat(tickers.get(0).accTradePrice24h())
                .as("동명 후보를 거래대금으로 가르는 값이다 — 없으면 이더리움이 메가이더에 진다")
                .isEqualByComparingTo(new BigDecimal("194497300539.0"));
        // 콤마로 이어 한 번만 나가야 한다
        server.verify(1, getRequestedFor(urlPathEqualTo("/v1/ticker"))
                .withQueryParam("markets", WireMock.equalTo("KRW-ETH,KRW-ETC")));
    }

    @Test
    @DisplayName("빈 목록에는 부르지 않는다 — 헛호출이 초당 한도를 태운다")
    void neverCallsForAnEmptyList() {
        assertThat(api.tickers(List.of())).isEmpty();

        server.verify(0, getRequestedFor(urlPathEqualTo("/v1/ticker")));
    }

    @Test
    @DisplayName("응답이 비면 실패로 던진다 — 조용히 빈 목록을 주면 폴백도 안 돌고 화면만 빈다")
    void treatsANullBodyAsFailure() {
        // 본문 없는 200이다. 삼키면 "그런 코인이 없다"와 구분되지 않는다
        server.stubFor(get(urlPathEqualTo("/v1/market/all")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("null")));

        assertThatThrownBy(() -> api.krwMarkets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("업비트 마켓 목록");
    }

    @Test
    @DisplayName("일봉을 날짜 순으로 뒤집어 담는다 — 업비트는 최근 것이 먼저 온다")
    void reversesCandlesIntoDateOrder() {
        stub("/v1/candles/days", """
                [{"candle_date_time_kst":"2026-08-21T00:00:00","trade_price":161000000},
                 {"candle_date_time_kst":"2026-08-20T00:00:00","trade_price":158500000},
                 {"candle_date_time_kst":"2026-08-19T00:00:00","trade_price":159200000}]""");

        var bars = api.dailyBars("KRW-BTC");

        assertThat(bars).extracting(bar -> bar.date().toString())
                .as("그림은 왼쪽이 과거여야 한다")
                .containsExactly("2026-08-19", "2026-08-20", "2026-08-21");
        assertThat(bars.get(2).close()).isEqualByComparingTo(new java.math.BigDecimal("161000000"));
    }

    @Test
    @DisplayName("KST 날짜를 쓴다 — UTC를 쓰면 하루가 어긋난다")
    void usesTheKstDate() {
        // 두 필드가 함께 오는데 utc는 하루 앞이다. 잘못 고르면 차트의 x축이 통째로 밀린다
        stub("/v1/candles/days", """
                [{"candle_date_time_utc":"2026-08-20T15:00:00",
                  "candle_date_time_kst":"2026-08-21T00:00:00","trade_price":161000000},
                 {"candle_date_time_utc":"2026-08-19T15:00:00",
                  "candle_date_time_kst":"2026-08-20T00:00:00","trade_price":158500000}]""");

        assertThat(api.dailyBars("KRW-BTC")).extracting(bar -> bar.date().toString())
                .containsExactly("2026-08-20", "2026-08-21");
    }

    @Test
    @DisplayName("상장 직후라 칸이 하나뿐이면 그림을 안 그린다 — 실패가 아니라 「그만큼밖에 없다」다")
    void toleratesTooFewCandles() {
        stub("/v1/candles/days", """
                [{"candle_date_time_kst":"2026-08-21T00:00:00","trade_price":1000}]""");

        var bars = api.dailyBars("KRW-NEW");

        assertThat(bars).hasSize(1);
        assertThat(io.saiden.economyhelper.market.chart.DailySeries.drawable(bars))
                .as("점 하나로는 선이 없다")
                .isFalse();
    }

    @Test
    @DisplayName("빈 일봉은 캐시하지 않는다 — 09시의 빈손 하나가 브리핑 창(1시간) 내내 차트를 지운다")
    void neverCachesAnEmptySeries() throws Exception {
        // ⚠️ 동작으로 재려면 스프링 컨텍스트가 필요하다. 여기서 보는 것은 **선언**이고,
        //    그게 load-bearing이다 — crypto-series TTL이 1시간이고 브리핑이 09~10시 창에서
        //    10분마다 도므로 길이가 겹친다. 빈 배열이 굳으면 그날 브리핑에서 그 코인 차트가
        //    통째로 빠진다. FeedFetcher가 같은 이유로 같은 조건을 달아 두었다
        var cacheable = UpbitApi.class
                .getDeclaredMethod("dailyBars", String.class)
                .getAnnotation(org.springframework.cache.annotation.Cacheable.class);

        assertThat(cacheable).as("@Cacheable이 사라졌다 — 이 단언이 뜻을 잃는다").isNotNull();
        assertThat(cacheable.unless())
                .as("빈 목록이 캐시되면 일시적 빈손이 TTL 내내 굳는다")
                .contains("isEmpty");
    }
}
