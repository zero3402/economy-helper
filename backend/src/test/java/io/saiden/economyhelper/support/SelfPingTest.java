package io.saiden.economyhelper.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 자체 핑은 <b>실패해도 조용해야 하고, 꺼져 있으면 아무 일도 없어야 한다.</b>
 * 10분마다 도는 것이라 여기가 시끄러우면 정작 봐야 할 로그가 묻힌다.
 */
class SelfPingTest {

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetStubs() {
        // 스텁·요청기록·시나리오를 함께 비운다 — 서버는 그대로 두고 상태만 되돌린다
        server.resetAll();
    }

    @Test
    @DisplayName("주소가 있으면 그 주소를 친다")
    void pingsConfiguredUrl() {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));

        new SelfPing(RestClient.builder(), server.baseUrl() + "/actuator/health/liveness").ping();

        server.verify(getRequestedFor(urlPathEqualTo("/actuator/health/liveness")));
    }

    @Test
    @DisplayName("주소가 비면 요청조차 하지 않는다 — 잠들지 않는 호스트에서는 없는 기능이다")
    void doesNothingWithoutUrl() {
        new SelfPing(RestClient.builder(), "  ").ping();
        new SelfPing(RestClient.builder(), null).ping();

        server.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("404·500이어도 조용히 넘어간다 — 요청이 닿은 순간 유휴 타이머는 이미 리셋됐다")
    void survivesErrorResponses() {
        for (int status : new int[] {404, 500}) {
            server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(status)));

            assertThatCode(() -> new SelfPing(RestClient.builder(), server.baseUrl()).ping())
                    .as("상태 %d", status)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("호스트가 죽어 있어도 예외를 밖으로 내보내지 않는다 — 스케줄러가 멈추면 안 된다")
    void survivesUnreachableHost() {
        assertThatCode(() -> new SelfPing(RestClient.builder(), "http://localhost:1").ping())
                .doesNotThrowAnyException();
    }
}
