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
import org.springframework.core.Ordered;
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
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired io.github.resilience4j.ratelimiter.RateLimiterRegistry limiters;

    @Test
    @DisplayName("선언한 리미터가 실제로 스로틀이다 — 이름만 애너테이션에 있으면 조용히 무력해진다")
    void declaredRateLimitersActuallyThrottle() {
        // ⚠️ ratelimiter에는 configs.default가 없다. 인스턴스를 선언하지 않으면 라이브러리
        // 기본값(50 permits / 500ns)으로 만들어지는데 그건 사실상 무제한이라,
        // @RateLimiter가 프록시만 태우고 아무것도 막지 않는다. 실제로 met.no가 그 상태였다.
        // 날씨는 그래서 리미터를 아예 달지 않는다 — AccuWeather의 제약은 일 단위다
        // ⚠️ kis는 여기 없다. 그 제약은 "1초에 몇 건"이 아니라 "호출 사이 얼마"라서 고정 윈도로
        // 표현할 수 없고(경계에서 두 호출이 120ms 간격으로 나갔다 — 실측), KisThrottle이 맡는다.
        // 이 목록에 kis를 되돌려 놓으려면 KisThrottle을 먼저 걷어낼 것 — 둘 다 걸면 두 배로 쉰다
        for (String name : new String[] {"gemini", "upbit", "binance", "dataGo", "fmp", "kexim"}) {
            RateLimiterConfig config = limiters.rateLimiter(name).getRateLimiterConfig();

            assertThat(config.getLimitRefreshPeriod())
                    .as("%s 리미터가 선언되지 않아 기본값(500ns)으로 떨어졌다 — 무제한과 같다", name)
                    .isGreaterThanOrEqualTo(java.time.Duration.ofSeconds(1));
            assertThat(config.getLimitForPeriod())
                    .as("%s 리미터의 허용량이 기본값(50)이다 — 선언이 안 먹었다는 뜻이다", name)
                    .isLessThan(50);
        }
    }

    @Test
    @DisplayName("KIS의 간격 문이 빈 자리로 남지 않는다 — 리미터를 걷어낸 자리를 대신 채운 것이다")
    void theKisThrottleExists() {
        // 리미터 목록에서 kis를 뺐으므로, 그 제약을 지키는 것이 실제로 컨텍스트에 있어야 한다.
        // 빈이 사라지면 KIS 호출이 아무 간격 없이 나가고 두 번째부터 조용히 거절된다
        assertThat(context.getBeansOfType(io.saiden.economyhelper.market.kis.KisThrottle.class))
                .as("KisThrottle 빈이 없다 — KIS 초당 한도를 지키는 것이 아무것도 없다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("캐시가 리미터·브레이커보다 바깥이다 — 히트가 퍼밋을 태우면 캐시가 아무것도 아껴 주지 않는다")
    void cacheSitsOutsideTheResilienceAspects() {
        // 실측 기본값: 브레이커 LOWEST_PRECEDENCE-4, 리미터 LOWEST_PRECEDENCE-3,
        // 캐시 어드바이저는 order를 안 주면 LOWEST_PRECEDENCE다 — 값이 작을수록 바깥이라
        // 그대로 두면 브레이커 → 리미터 → 캐시 순이 된다. 그러면 (1) 캐시에 있는 값도 퍼밋을
        // 태워 히트가 아무것도 아껴 주지 않고(gemini는 분당 12건이라 이게 곧 실패다),
        // (2) 브레이커가 열리면
        // 캐시된 값조차 못 읽는다(accu-location 30일 캐시가 하루 50회 한도의 실질 방어인데
        // 그게 브레이커 앞에서 무력해진다). @EnableCaching(order = ...)로 캐시를 바깥에 둔다.
        int cacheOrder = context.getBean(
                        org.springframework.cache.config.CacheManagementConfigUtils.CACHE_ADVISOR_BEAN_NAME,
                        org.springframework.aop.Advisor.class) instanceof org.springframework.core.Ordered ordered
                ? ordered.getOrder()
                : Integer.MAX_VALUE;

        assertThat(cacheOrder)
                .as("캐시가 리미터(LOWEST_PRECEDENCE-3)보다 바깥이어야 한다")
                .isLessThan(Ordered.LOWEST_PRECEDENCE - 3);
        assertThat(cacheOrder)
                .as("캐시가 브레이커(LOWEST_PRECEDENCE-4)보다도 바깥이어야 한다")
                .isLessThan(Ordered.LOWEST_PRECEDENCE - 4);
    }

    @Test
    @DisplayName("텔레그램 429는 브레이커를 열지 않는다 — 우리가 빨리 물은 것이지 상대 장애가 아니다")
    void telegramRateLimitDoesNotOpenTheBreaker() {
        // 429가 200 본문에 error_code로 오므로 HTTP 예외가 아니다. 하나의 TelegramException으로
        // 뭉쳐 던지던 동안에는 ignoreExceptions에 적어도 걸러낼 수 없었고, 10회 창에 5번이면
        // 멀쩡한 발송까지 60초 막혔다 — translation이 TooManyRequests를 빼 둔 것과 같은 자리다
        CircuitBreaker breaker = registry.circuitBreaker("telegram");
        Throwable rateLimited = new io.saiden.economyhelper.telegram.TelegramClient
                .TelegramRateLimited("텔레그램 sendMessage 거절: 429 Too Many Requests");

        long before = breaker.getMetrics().getNumberOfFailedCalls();
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, rateLimited);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("429를 실패로 세면 우리 트래픽이 몰릴 때마다 브레이커가 열린다")
                .isEqualTo(before);

        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new io.saiden.economyhelper.telegram.TelegramClient
                        .TelegramException("chat not found"));
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("그 밖의 거절은 여전히 실패다 — 설정이 틀린 것은 알려야 한다")
                .isEqualTo(before + 1);
    }

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

        // ⚠️ ignoreExceptions를 따로 적은 브레이커(binance·weatherGeocoding)를 반드시 포함한다 —
        // 그 설정은 baseConfig의 것을 덮어쓰므로 RequestNotPermitted가 조용히 빠질 수 있다.
        // binance가 실제로 그 상태였고, 목록에 없어서 아무도 몰랐다
        for (String name : new String[] {"translation", "telegram", "fmp", "upbit", "binance",
                "kisFx", "kisStock",
                "weatherAccuWeather", "weatherOpenMeteo", "weatherOpenMeteoArchive",
                "weatherGeocoding"}) {
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
