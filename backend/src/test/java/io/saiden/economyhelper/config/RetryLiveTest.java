package io.saiden.economyhelper.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.saiden.economyhelper.testsupport.WireMockTest;
import io.saiden.economyhelper.weather.adapter.out.openmeteo.GeocodingApi;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 재시도가 <b>실제로 도는지</b> — 설정이 맞다는 것과 애너테이션이 붙었다는 것은 다른 말이다.
 *
 * <p><b>왜 스프링 컨텍스트가 필요한가.</b> 단위 테스트는 클라이언트를 {@code new}로 만들어
 * <b>프록시를 타지 않는다</b>. 그래서
 * {@code ResilienceConfigTest}는 "규칙이 이렇다"까지만 말하고 "그 규칙이 이 메서드에 걸렸다"는
 * 말하지 못한다. {@code DigestIntegrationTest}가 브레이커에 대해 정확히 이 구멍을 메우려고
 * 있는 자리이고, 재시도도 같은 그물이 필요하다.
 *
 * <p>지오코딩을 고른 이유는 <b>이중화 상대가 없는 단일 경로</b>라서다 — 재시도가 가장 값을
 * 하는 자리이고, 여기가 안 걸려 있으면 {@code /weather}가 통째로 빈손이 된다.
 */
@SpringBootTest
// h2c를 끈다 — 평문 HTTP/1.1이어야 클라이언트가 포기한 순간 연결이 닫혀 서버의 본문 흘리기도 그 자리에서 끝난다.
// h2c는 스트림만 리셋돼 서버 스레드가 남은 조각을 끝까지 흘리고, 그 사이 다음 테스트가 시작된다
@WireMockTest.WireMockOptions(http2PlainDisabled = true)
class RetryLiveTest extends WireMockTest {

    private static final String PATH = "/v1/search";


    @DynamicPropertySource
    static void geocodingPointsAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("economy-helper.weather.open-meteo.geocoding-base-url", () -> server.baseUrl());
        // 캐시를 끈다 — 켜 두면 두 번째 호출이 캐시 히트라 재시도가 아예 안 보인다
        registry.add("spring.cache.type", () -> "none");
        // 본문이 멈추는 경우를 재현하는 읽기 타임아웃 — WireMock(localhost)은 출처별 타임아웃 표에 없어 기본값을 쓴다.
        // ⚠️ 짧게 잡지 않는다. 이 값은 **성공해야 하는 두 번째 시도에도** 걸린다 — 500ms였을 때 포크가 몰리면 그
        //    작은 응답까지 타임아웃을 맞아 세 번째 요청이 나갔다. 멈춘 본문은 8초를 끌므로 2초면 넉넉히 가른다
        registry.add("spring.http.clients.read-timeout", () -> "2s");
    }

    @Autowired GeocodingApi geocoding;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired RetryRegistry retries;

    @BeforeEach
    void resetAll() {
        breakers.circuitBreaker("weatherGeocoding").reset();
    }

    @Test
    @DisplayName("503은 다시 부른다 — 두 번째에 답이 오면 사용자는 실패를 몰라야 한다")
    void retriesAServerError() {
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"results":[{"name":"성남시","latitude":37.43861,"longitude":127.13778,
                                 "country":"대한민국","timezone":"Asia/Seoul","population":914832}]}""")));

        Optional<?> found = geocoding.find("성남시", "KR");

        assertThat(found).as("두 번째 시도가 성공했으면 값이 나와야 한다").isPresent();
        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("본문을 읽다 끊긴 것도 다시 부른다 — Spring이 이것만 ResourceAccessException이 아닌 맨 RestClientException으로 감싼다")
    void retriesAFailureWhileReadingTheBody() {
        // 헤더(200)는 첫 조각과 함께 200ms 만에 오고 본문은 8초에 걸쳐 흘러, 2초 읽기 타임아웃이 본문 한가운데서
        // 난다. 헤더 전에 깨지면(리셋·깨진 청크) ResourceAccessException이라 원래 재시도됐지만, 본문 읽기는
        // DefaultRestClient.readWithMessageConverters가 맨 RestClientException으로 감싸 retryExceptions 열거에
        // 안 걸렸다(spring-web 7.0.8 소스·JDK 클라이언트로 확인 — 2026-10-13).
        // ⚠️ 타이머 없이 본문만 끊는 WireMock 장치는 없다 — Fault는 응답 첫머리에서만 나고(MALFORMED_RESPONSE_CHUNK도
        //    JDK 클라이언트가 첫 청크 머리까지 먼저 읽어 ResourceAccessException이 된다, 40/40), 선언한 Content-Length는
        //    WireMock이 고쳐 쓴다(2026-10-15 실측). 그래서 어느 길로 실패했는지를 재시도 이벤트에서 직접 확인한다
        AtomicReference<Throwable> retried = new AtomicReference<>();
        retries.retry("weatherGeocoding").getEventPublisher()
                .onRetry(event -> retried.compareAndSet(null, event.getLastThrowable()));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("broken-body")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"results\":[{\"name\":\"" + "가".repeat(2000) + "\"}]}")
                        .withChunkedDribbleDelay(40, 8_000))
                .willSetStateTo("recovered"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("broken-body")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"results":[{"name":"성남시","latitude":37.43861,"longitude":127.13778,
                                 "country":"대한민국","timezone":"Asia/Seoul","population":914832}]}""")));

        Optional<?> found = geocoding.find("성남시", "KR");

        assertThat(found).as("두 번째 시도가 성공했으면 값이 나와야 한다").isPresent();
        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(retried.get())
                .as("헤더를 받은 뒤 본문에서 끊긴 모양이어야 이 테스트가 말하는 길이다")
                .isInstanceOf(RestClientException.class)
                .isNotInstanceOf(ResourceAccessException.class)
                .satisfies(e -> assertThat(e.getCause()).isInstanceOf(IOException.class));
    }

    @Test
    @DisplayName("4xx는 한 번만 부른다 — 이상한 문자가 섞인 검색어는 세 번 물어도 같다")
    void neverRetriesAClientError() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        // 4xx는 삼키지 않고 던진다 — 폴백 판단은 호출자(WeatherFacade)의 몫이다.
        // 여기서 보는 것은 "몇 번 물었나"이고, 한 번이어야 한다
        assertThatThrownBy(() -> geocoding.find("%%%", null))
                .isInstanceOf(HttpClientErrorException.class);

        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("못 찾은 것은 재시도하지 않는다 — 빈손은 값이지 실패가 아니다")
    void neverRetriesAnEmptyResult() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThat(geocoding.find("없는지명", null)).isEmpty();

        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("브레이커가 열리면 재시도가 즉시 멈춘다 — 열린 문을 두 번 두드리지 않는다")
    void stopsRetryingWhenTheBreakerIsOpen() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        breakers.circuitBreaker("weatherGeocoding").transitionToOpenState();

        // CallNotPermittedException이 무시 목록에 있다는 것을 **행동으로** 확인한다.
        // 없으면 재시도가 대기 시간을 두 번 더 지불하고 같은 답을 받는다
        try {
            geocoding.find("성남시", "KR");
        } catch (RuntimeException expected) {
            // 브레이커가 열려 있으니 던지는 것이 맞다
        }

        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
