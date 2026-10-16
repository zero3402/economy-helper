package io.saiden.economyhelper.weather.adapter.out.accu;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.saiden.economyhelper.testsupport.WireMockTest;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AccuWeather 브레이커가 예보 한 번을 <b>한 번</b>으로 세는지 — 지점 조회가 예보 안에서 불리는데
 * 거기에도 같은 이름이 걸려 있으면 한 실패가 둘로 세인다.
 *
 * <p>스프링 컨텍스트가 필요한 이유는 {@code RetryLiveTest}와 같다 — 애너테이션은 프록시를 타야 산다.
 */
@SpringBootTest
class AccuBreakerLiveTest extends WireMockTest {

    private static final GeoLocation MIGEUM = new GeoLocation("미금역", "KR", 37.35, 127.10889, ZoneId.of("Asia/Seoul"));
    private static final WeatherPeriod TODAY = new WeatherPeriod(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15));

    @DynamicPropertySource
    static void accuWeatherPointsAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("economy-helper.weather.accu-weather.base-url", () -> server.baseUrl());
        // 키가 비면 forecast가 HTTP 전에 던져 지점 조회에 닿지 않는다 — 그러면 고치기 전에도 한 번이다
        registry.add("economy-helper.weather.accu-weather.api-key", () -> "test-key");
        // 캐시를 끈다 — 지점 키가 적중하면 안쪽 호출이 아예 없다
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired AccuWeatherClient accuWeather;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetTheBreaker() {
        breakers.circuitBreaker("weatherAccuWeather").reset();
    }

    @Test
    @DisplayName("지점 조회 한 번의 실패는 브레이커에 한 번만 센다 — 둘로 세면 실패 다섯에 열리고 half-open 셋 중 둘을 한 요청이 먹는다")
    void countsOneFailurePerForecast() {
        server.stubFor(get(urlPathMatching("/locations/.*")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> accuWeather.forecast(MIGEUM, TODAY)).isInstanceOf(RuntimeException.class);

        assertThat(breakers.circuitBreaker("weatherAccuWeather").getMetrics().getNumberOfFailedCalls())
                .as("예보 한 번은 실패 한 번이다 — 출처 하나에 브레이커 하나라는 뜻이 「세는 자리도 하나」다")
                .isEqualTo(1);
    }
}
