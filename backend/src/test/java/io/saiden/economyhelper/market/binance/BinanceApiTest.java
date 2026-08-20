package io.saiden.economyhelper.market.binance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class BinanceApiTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("1순위가 451이면 미러로 우회한다 — 지역 차단은 재시도로도 이중화로도 안 낫는다")
    void bypassesTheRegionBlockWithTheMirror() {
        // Render 미국 리전에서 api.binance.com이 이걸 준다. 재시도해도 같은 답이고
        // 브레이커도 4xx라 안 열린다 — 그래서 화면에 '조회 실패'만 영영 찍혔다.
        // 공개 데이터 미러는 같은 스키마를 주므로 우회하면 값이 그대로 나온다
        WireMockServer mirror = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        mirror.start();
        try {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(451)));
            mirror.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            [{"symbol":"ETHUSDT","lastPrice":"2256.31","priceChangePercent":"18.06"}]""")));

            List<BinancePrice> prices = new BinanceApi(
                    RestClient.builder(), server.baseUrl(), mirror.baseUrl())
                    .prices(List.of("ETHUSDT"));

            assertThat(prices).singleElement()
                    .satisfies(price -> assertThat(price.lastPrice())
                            .isEqualByComparingTo(new BigDecimal("2256.31")));
        } finally {
            mirror.stop();
        }
    }

    @Test
    @DisplayName("없는 심볼(400)에는 우회하지 않는다 — 호스트를 바꿔도 없는 것은 없다")
    void neverBypassesForAnUnknownSymbol() {
        // 400은 우리 잘못이다(실측: USDTUSDT → -1121 Invalid symbol). 미러에 한 번 더 물으면
        // 헛호출만 늘고 답은 같다 — CryptoService가 이걸 '미상장'으로 읽어야 한다
        WireMockServer mirror = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        mirror.start();
        try {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(400)
                    .withBody("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}")));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BinanceApi(
                    RestClient.builder(), server.baseUrl(), mirror.baseUrl())
                    .prices(List.of("USDTUSDT")))
                    .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);

            assertThat(mirror.getAllServeEvents())
                    .as("미러를 부르지 않아야 한다 — 400은 호스트 문제가 아니다").isEmpty();
        } finally {
            mirror.stop();
        }
    }

    @Test
    @DisplayName("여러 심볼을 한 번에 부르고 배열 응답을 그대로 읽는다")
    void readsBatchResponse() {
        // /ticker/24hr의 실제 응답 모양이다. 값 이름이 price가 아니라 lastPrice이고
        // 등락률(priceChangePercent)이 함께 온다 — 그래서 이 엔드포인트로 옮겼다
        server.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [{"symbol":"BTCUSDT","lastPrice":"63703.69000000",\
                        "priceChange":"-926.01000000","priceChangePercent":"-1.451"},\
                        {"symbol":"ETHUSDT","lastPrice":"1886.36000000",\
                        "priceChange":"12.00000000","priceChangePercent":"0.640"}]""")));

        List<BinancePrice> prices = api().prices(List.of("BTCUSDT", "ETHUSDT"));

        assertThat(prices).extracting(BinancePrice::symbol).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(prices.get(0).lastPrice()).isEqualByComparingTo(new BigDecimal("63703.69"));
        assertThat(prices.get(0).priceChangePercent()).isEqualByComparingTo(new BigDecimal("-1.451"));
    }

    @Test
    @DisplayName("symbols는 JSON 배열을 한 번만 인코딩한다 — 두 번 하면 %255B가 되어 400이다")
    void encodesSymbolsExactlyOnce() {
        assertThat(BinanceApi.query(List.of("BTCUSDT", "ETHUSDT")))
                .isEqualTo("/api/v3/ticker/24hr?symbols=%5B%22BTCUSDT%22%2C%22ETHUSDT%22%5D")
                .doesNotContain("%25");
    }

    @Test
    @DisplayName("심볼이 하나여도 배열로 보낸다 — 개수에 따라 응답 모양이 갈리면 파싱이 두 갈래가 된다")
    void alwaysUsesArrayForm() {
        assertThat(BinanceApi.query(List.of("BTCUSDT")))
                .isEqualTo("/api/v3/ticker/24hr?symbols=%5B%22BTCUSDT%22%5D");
    }

    @Test
    @DisplayName("빈 목록이면 아예 부르지 않는다")
    void skipsEmptyRequest() {
        assertThat(api().prices(List.of())).isEmpty();
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private BinanceApi api() {
        return new BinanceApi(RestClient.builder(), server.baseUrl(), "");
    }
}
