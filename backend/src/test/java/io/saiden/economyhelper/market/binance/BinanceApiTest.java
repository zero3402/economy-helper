package io.saiden.economyhelper.market.binance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class BinanceApiTest {

    private WireMockServer server;

    /**
     * 밴 문. Redis 없이 프로세스 사본만 쓴다 — Redis가 죽었을 때와 같은 경로다.
     *
     * <p><b>테스트마다 새로 만든다.</b> 하나를 나눠 쓰면 418을 던진 테스트가 다음 테스트의
     * 호출까지 막아, 순서에 따라 결과가 갈린다.
     */
    private BinanceBanGate gate;

    /** 밴 시각을 눈으로 검산하려고 고정한다 — 「지금부터 몇 초」가 아니라 「몇 시」를 단언한다. */
    private static final Instant NOW = Instant.parse("2026-08-20T05:00:00Z");

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        gate = new BinanceBanGate(null, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("418(IP 밴)에는 미러를 부르지 않는다 — 밴 중에 더 부르면 밴이 길어진다")
    void neverBypassesAnIpBan() {
        // 바이낸스는 429를 받고도 계속 부르면 IP를 자동 밴하고(418), **밴 중의 호출이 밴을
        // 연장한다**(2분~3일). 그리고 실측으로 두 호스트가 한 IP 예산을 공유한다 —
        // x-mbx-used-weight-1m이 api→data-api를 번갈아 물어도 2·4·6·8로 이어졌다.
        // 즉 우회는 아무 이득 없이 밴만 늘린다
        WireMockServer mirror = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        mirror.start();
        try {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(418)));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BinanceApi(
                    RestClient.builder(), gate, server.baseUrl(), mirror.baseUrl())
                    .prices(List.of("ETHUSDT")))
                    .as("밴은 미상장이 아니다 — 좁은 타입으로 삼켜지면 브레이커가 안 열린다")
                    .isNotInstanceOf(BinanceApi.UnknownSymbol.class);

            assertThat(mirror.getAllServeEvents())
                    .as("미러를 부르면 그것이 밴을 연장한다").isEmpty();
        } finally {
            mirror.stop();
        }
    }

    @Test
    @DisplayName("429에도 미러를 부르지 않는다 — 여기서 더 부르면 418로 굳는다")
    void neverBypassesARateLimitWarning() {
        WireMockServer mirror = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        mirror.start();
        try {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(429)));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BinanceApi(
                    RestClient.builder(), gate, server.baseUrl(), mirror.baseUrl())
                    .prices(List.of("ETHUSDT")))
                    .isInstanceOf(RuntimeException.class);

            assertThat(mirror.getAllServeEvents()).isEmpty();
        } finally {
            mirror.stop();
        }
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
                    RestClient.builder(), gate, server.baseUrl(), mirror.baseUrl())
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
                    RestClient.builder(), gate, server.baseUrl(), mirror.baseUrl())
                    .prices(List.of("USDTUSDT")))
                    .as("좁은 타입으로 던져야 브레이커가 이것만 무시할 수 있다")
                    .isInstanceOf(BinanceApi.UnknownSymbol.class);

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


    @Test
    @DisplayName("418을 한 번 받으면 그 뒤로는 HTTP가 아예 안 나간다 — 밴 중의 호출이 밴을 연장한다")
    void stopsCallingAfterTheFirstBan() {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(418)));
        BinanceApi api = api();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.prices(List.of("ETHUSDT")))
                .isNotInstanceOf(BinanceApi.Banned.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.prices(List.of("BTCUSDT")))
                .as("두 번째는 부르지 않고 스스로 거절한다")
                .isInstanceOf(BinanceApi.Banned.class);

        assertThat(server.getAllServeEvents())
                .as("브레이커에 맡기면 열릴 때까지 다섯 번을 더 부른다 — 그 다섯 번이 밴을 늘린다")
                .hasSize(1);
    }

    @Test
    @DisplayName("Retry-After를 그대로 믿는다 — 바이낸스가 언제 풀리는지 직접 말해 준다")
    void honoursRetryAfter() {
        server.stubFor(get(anyUrl())
                .willReturn(aResponse().withStatus(418).withHeader("Retry-After", "300")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api().prices(List.of("ETHUSDT")))
                .isInstanceOf(RuntimeException.class);

        assertThat(gate.bannedUntil())
                .as("넘겨짚은 최소값(2분)이 아니라 상대가 말한 300초다")
                .isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("Retry-After가 없으면 상대가 문서로 말한 최소 밴(2분)으로 넘겨짚는다")
    void fallsBackToTheDocumentedMinimumBan() {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(418)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api().prices(List.of("ETHUSDT")))
                .isInstanceOf(RuntimeException.class);

        assertThat(gate.bannedUntil()).isEqualTo(NOW.plus(BinanceBanGate.MIN_BAN));
    }

    @Test
    @DisplayName("429는 밴이 아니라 경고다 — 더 짧게 물러섰다가 돌아온다")
    void backsOffBrieflyOnAWarning() {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(429)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api().prices(List.of("ETHUSDT")))
                .isInstanceOf(RuntimeException.class);

        assertThat(gate.bannedUntil())
                .as("여기서 계속 부르면 418로 굳는다. 그래도 2분은 과하다")
                .isEqualTo(NOW.plus(BinanceBanGate.WARNING_BACKOFF));
    }

    @Test
    @DisplayName("미상장(400)은 문을 닫지 않는다 — 우리 잘못이지 밴이 아니다")
    void anUnknownSymbolNeverClosesTheGate() {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api().prices(List.of("USDTUSDT")))
                .isInstanceOf(BinanceApi.UnknownSymbol.class);

        assertThat(gate.bannedUntil())
                .as("없는 심볼 하나가 멀쩡한 코인까지 막으면 안 된다").isNull();
    }
    private BinanceApi api() {
        return new BinanceApi(RestClient.builder(), gate, server.baseUrl(), "");
    }
}
