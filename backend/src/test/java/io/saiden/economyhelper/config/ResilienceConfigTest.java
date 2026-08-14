package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 설정 파일에 적은 회복탄력성 규칙이 <b>런타임에 실제로 그렇게 되는지</b> 본다.
 *
 * <p>YAML은 오타가 나도 조용히 무시된다 — {@code ignoreExceptions}가 실제로 걸렸는지는
 * 레지스트리에서 꺼내 봐야 알 수 있고, 안 걸렸다면 우리 트래픽이 몰릴 때마다 브레이커가
 * 열려 멀쩡한 상대를 끊는다.
 */
@SpringBootTest
class ResilienceConfigTest {

    @DynamicPropertySource
    static void noRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired CircuitBreakerRegistry registry;

    @Test
    @DisplayName("리미터 거절은 브레이커의 실패로 세지 않는다 — 자기 스로틀은 상대 장애가 아니다")
    void rateLimiterRejectionDoesNotOpenTheBreaker() {
        // 실제로 소진된 리미터를 만들어 진짜 거절 예외를 얻는다 — 이 예외의 정확한
        // 타입이 무시 목록과 맞는지가 이 테스트의 전부다
        RateLimiter drained = RateLimiter.of("drained", RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(java.time.Duration.ofMinutes(10))
                .timeoutDuration(java.time.Duration.ZERO)
                .build());
        drained.acquirePermission();
        RequestNotPermitted rejection = RequestNotPermitted.createRequestNotPermitted(drained);

        for (String name : new String[] {"translation", "telegram", "fmp", "upbit"}) {
            CircuitBreaker breaker = registry.circuitBreaker(name);
            long before = breaker.getMetrics().getNumberOfFailedCalls();

            breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, rejection);

            assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                    .as("%s 브레이커가 리미터 거절을 실패로 셌다 — baseConfig가 "
                            + "ignoreExceptions를 상속하지 않는다는 뜻이다", name)
                    .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("진짜 장애는 그대로 센다 — 무시 목록이 넓어져 브레이커가 무력해지면 안 된다")
    void realFailuresStillCount() {
        CircuitBreaker breaker = registry.circuitBreaker("translation");
        long before = breaker.getMetrics().getNumberOfFailedCalls();

        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new IllegalStateException("Gemini 500"));

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(before + 1);
    }
}
