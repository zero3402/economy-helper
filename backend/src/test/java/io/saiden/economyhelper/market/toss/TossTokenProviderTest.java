package io.saiden.economyhelper.market.toss;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 토큰을 <b>얼마나 자주 받는지</b>가 이 클래스의 요점이다.
 *
 * <p>실측 {@code expires_in}이 86,399초라 하루 한 번이면 되는데, 매 요청마다 받으면
 * {@code AUTH} 그룹 한도를 태우고 지연도 붙는다.
 */
class TossTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        // WireMock의 평문 h2 구현이 POST 본문에서 JDK HttpClient와 충돌한다 — HTTP/2를 끈다
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        server.start();
        WireMock.configureFor(server.port());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private TossTokenProvider providerAt(Instant now) {
        return new TossTokenProvider(RestClient.builder(), server.baseUrl(), "id", "secret",
                Clock.fixed(now, ZoneOffset.UTC), CircuitBreakerRegistry.ofDefaults());
    }

    private void stubToken(String token, long expiresIn) {
        server.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"%s","token_type":"Bearer","expires_in":%d}
                                """.formatted(token, expiresIn))));
    }

    @Test
    @DisplayName("한 번 받은 토큰을 재사용한다 — 매 요청마다 발급하면 AUTH 한도를 태운다")
    void reusesTokenUntilExpiry() {
        stubToken("tok-1", 86399);
        TossTokenProvider provider = providerAt(NOW);

        assertThat(provider.token()).isEqualTo("tok-1");
        assertThat(provider.token()).isEqualTo("tok-1");
        assertThat(provider.token()).isEqualTo("tok-1");

        server.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    @Test
    @DisplayName("만료 60초 전부터 새로 받는다 — 경계에서 401을 맞지 않으려고 앞당긴다")
    void refreshesBeforeActualExpiry() {
        // 100초짜리 토큰이면 유효기간은 40초(100 - 60 마진)다
        stubToken("tok-1", 100);
        TossTokenProvider fresh = providerAt(NOW);
        assertThat(fresh.token()).isEqualTo("tok-1");
        server.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));

        // 45초 뒤에 만든 provider는 캐시가 없으므로, 마진 계산 자체를 확인한다
        TossTokenProvider aged = providerAt(NOW);
        aged.token();
        assertThat(aged.token()).isEqualTo("tok-1");
    }

    @Test
    @DisplayName("invalidate하면 다시 받는다 — 서버가 우리보다 먼저 토큰을 버릴 수 있다")
    void reissuesAfterInvalidate() {
        stubToken("tok-1", 86399);
        TossTokenProvider provider = providerAt(NOW);
        provider.token();

        provider.invalidate();
        provider.token();

        server.verify(2, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    @Test
    @DisplayName("access_token이 없으면 실패한다 — 빈 토큰으로 요청하면 원인이 더 멀리서 드러난다")
    void failsWhenTokenMissing() {
        server.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token_type\":\"Bearer\"}")));

        assertThatThrownBy(() -> providerAt(NOW).token())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access_token");
    }

    @Test
    @DisplayName("예외에 토큰 값이 새지 않는다 — 로그에 남으면 그 자체로 자격증명 유출이다")
    void neverLeaksCredentials() {
        server.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"error\":{\"code\":\"invalid-client\"}}")));

        assertThatThrownBy(() -> providerAt(NOW).token())
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("client_secret");
    }

    @Test
    @DisplayName("발급이 계속 실패하면 브레이커가 끊는다 — 매 요청이 재발급을 시도하면 안 된다")
    void circuitBreakerStopsRepeatedFailures() {
        server.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse().withStatus(500)));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .build());
        TossTokenProvider provider = new TossTokenProvider(RestClient.builder(), server.baseUrl(),
                "id", "secret", Clock.fixed(NOW, ZoneOffset.UTC), registry);

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(provider::token).isInstanceOf(RuntimeException.class);
        }

        // 6번 시도했지만 브레이커가 열려 실제 호출은 그보다 적어야 한다
        assertThat(server.findAll(postRequestedFor(urlPathEqualTo("/oauth2/token"))).size())
                .isLessThan(6);
    }
}
