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
    @DisplayName("여러 심볼을 한 번에 부르고 배열 응답을 그대로 읽는다")
    void readsBatchResponse() {
        server.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [{"symbol":"BTCUSDT","price":"63703.69000000"},\
                        {"symbol":"ETHUSDT","price":"1886.36000000"}]""")));

        List<BinancePrice> prices = api().prices(List.of("BTCUSDT", "ETHUSDT"));

        assertThat(prices).extracting(BinancePrice::symbol).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(prices.get(0).price()).isEqualByComparingTo(new BigDecimal("63703.69"));
    }

    @Test
    @DisplayName("symbols는 JSON 배열을 한 번만 인코딩한다 — 두 번 하면 %255B가 되어 400이다")
    void encodesSymbolsExactlyOnce() {
        assertThat(BinanceApi.query(List.of("BTCUSDT", "ETHUSDT")))
                .isEqualTo("/api/v3/ticker/price?symbols=%5B%22BTCUSDT%22%2C%22ETHUSDT%22%5D")
                .doesNotContain("%25");
    }

    @Test
    @DisplayName("심볼이 하나여도 배열로 보낸다 — 개수에 따라 응답 모양이 갈리면 파싱이 두 갈래가 된다")
    void alwaysUsesArrayForm() {
        assertThat(BinanceApi.query(List.of("BTCUSDT")))
                .isEqualTo("/api/v3/ticker/price?symbols=%5B%22BTCUSDT%22%5D");
    }

    @Test
    @DisplayName("빈 목록이면 아예 부르지 않는다")
    void skipsEmptyRequest() {
        assertThat(api().prices(List.of())).isEmpty();
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private BinanceApi api() {
        return new BinanceApi(RestClient.builder(), server.baseUrl());
    }
}
