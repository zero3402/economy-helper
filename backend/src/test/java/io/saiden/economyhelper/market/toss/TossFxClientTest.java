package io.saiden.economyhelper.market.toss;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 401·429를 <b>한 번씩만</b> 되짚는지가 요점이다.
 *
 * <p>무한 재시도는 상대가 아픈 상황을 더 나쁘게 만들고, 재시도를 아예 안 하면
 * 토큰 만료 한 번에 사용자가 실패를 본다.
 */
class TossFxClientTest {

    private static final Instant NOW = Instant.parse("2026-08-12T01:36:31Z");
    private static final String PATH = "/api/v1/exchange-rate";

    private WireMockServer server;
    private TossFxClient client;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        server.start();
        WireMock.configureFor(server.port());

        server.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"expires_in\":86399}")));

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TossTokenProvider tokens = new TossTokenProvider(RestClient.builder(), server.baseUrl(),
                "id", "secret", clock, CircuitBreakerRegistry.ofDefaults());
        client = new TossFxClient(RestClient.builder(), server.baseUrl(), tokens, clock);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private static String okBody() {
        return """
                {"result":{"baseCurrency":"USD","quoteCurrency":"KRW","rate":"1414.7",
                 "midRate":"1414.55","rateChangeType":"UP",
                 "validFrom":"2026-08-12T10:36:31.000+09:00",
                 "validUntil":"2026-08-12T10:41:30.000+09:00"}}
                """;
    }

    @Test
    @DisplayName("result 봉투에서 환율을 꺼낸다 — 값이 문자열이라 BigDecimal로 정확히 옮긴다")
    void parsesResultEnvelope() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(okBody())));

        FxRate rate = client.usdToKrw();

        assertThat(rate.rate()).isEqualByComparingTo("1414.7");
        assertThat(rate.source()).isEqualTo(FxSource.TOSS);
        assertThat(rate.asOf()).isEqualTo(Instant.parse("2026-08-12T01:36:31Z"));
    }

    @Test
    @DisplayName("401 expired-token이면 토큰을 새로 받아 한 번만 재시도한다")
    void reissuesTokenOnceOnExpiry() {
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("expiry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"error\":{\"code\":\"expired-token\",\"message\":\"expired\"}}"))
                .willSetStateTo("재발급됨"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("expiry")
                .whenScenarioStateIs("재발급됨")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(okBody())));

        assertThat(client.usdToKrw().rate()).isEqualByComparingTo("1414.7");

        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        server.verify(2, postRequestedFor(urlPathEqualTo("/oauth2/token")));  // 최초 + 재발급
    }

    @Test
    @DisplayName("401이 반복되면 두 번째에 포기한다 — 무한 재시도는 하지 않는다")
    void givesUpAfterOneRetry() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"code\":\"expired-token\"}}")));

        assertThatThrownBy(() -> client.usdToKrw()).isInstanceOf(HttpClientErrorException.class);

        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("토큰 문제가 아닌 401은 재시도하지 않는다 — 권한 오류는 다시 해도 같다")
    void doesNotRetryNonTokenUnauthorized() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"code\":\"forbidden-ip\"}}")));

        assertThatThrownBy(() -> client.usdToKrw()).isInstanceOf(HttpClientErrorException.class);

        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("429는 retry-after만큼 기다렸다 한 번만 재시도한다")
    void retriesOnceAfterRateLimit() {
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withHeader("retry-after", "1")
                        .withBody("{\"error\":{\"code\":\"rate-limit-exceeded\"}}"))
                .willSetStateTo("풀림"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("429")
                .whenScenarioStateIs("풀림")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(okBody())));

        assertThat(client.usdToKrw().rate()).isEqualByComparingTo("1414.7");

        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("error 봉투가 오면 실패로 다룬다 — result가 없는데 성공으로 읽으면 안 된다")
    void treatsErrorEnvelopeAsFailure() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"requestId\":\"r1\",\"code\":\"internal\"}}")));

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate");
    }

    @Test
    @DisplayName("validFrom이 깨져도 값은 살린다 — 시각을 못 읽는 것과 환율이 없는 것은 다르다")
    void toleratesBrokenTimestamp() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"result\":{\"rate\":\"1414.7\",\"validFrom\":\"어제쯤\"}}")));

        assertThat(client.usdToKrw().rate()).isEqualByComparingTo("1414.7");
    }
}
