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

import java.util.Set;

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
    @Autowired io.github.resilience4j.retry.RetryRegistry retries;

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
        //
        // ⚠️ **목록을 손으로 적지 않는다.** 예전에는 여기에 이름 여섯을 박아 뒀는데, 그러면
        //    새 클라이언트에 @RateLimiter를 달면서 yml 블록을 빠뜨려도 이 테스트가 초록이다 —
        //    잡으려던 바로 그 실수를 못 잡는다. 그래서 실제로 쓰이는 이름을 긁어 온다 —
        //    애너테이션에 적힌 것과 빈이 손으로 꺼내 들고 있는 것을 함께 본다
        Set<String> used = rateLimiterNamesInUse();

        assertThat(used)
                .as("하나도 못 찾았다 — 스캔이 깨지면 이 테스트는 아무것도 안 본다")
                .isNotEmpty()
                .as("스캔이 절반만 돌아도 이 테스트는 초록이다 — 아는 이름이 다 잡히는지 함께 본다")
                .contains("gemini", "upbit", "binance", "dataGo", "fmp", "kexim");

        for (String name : used) {
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
    @DisplayName("@Retry를 단 이름이 전부 선언돼 있다 — 없으면 기본값이 4xx까지 세 번 부른다")
    void everyRetryNameIsDeclared() {
        // ⚠️ retry에는 configs.default가 있어도, **인스턴스 선언이 없으면** 그 이름은
        //    라이브러리 기본값(3회 · 500ms · 모든 예외 재시도)으로 만들어진다.
        //    리미터는 없는 인스턴스가 아무것도 막지 않는 쪽으로 망가졌지만(met.no가 그랬다)
        //    이쪽은 **우리 잘못(4xx)까지 세 번 부르는** 쪽으로 망가진다 — 더 나쁘다
        assertThat(retries.getAllRetries().stream().map(io.github.resilience4j.retry.Retry::getName))
                .as("yml의 retry.instances에 선언된 것만 eager로 만들어진다")
                .contains("weatherGeocoding", "weatherOpenMeteo", "weatherOpenMeteoArchive",
                        "upbit", "binance", "fxFrankfurter", "telegram", "feed");
    }

    @Test
    @DisplayName("4xx는 절대 재시도하지 않는다 — 없는 심볼과 없는 지명은 우리 잘못이다")
    void neverRetriesOurOwnMistakes() {
        RateLimiter drained = RateLimiter.of("drained-retry", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(java.time.Duration.ofMinutes(10))
                .timeoutDuration(java.time.Duration.ZERO).build());
        drained.acquirePermission();
        CircuitBreaker open = registry.circuitBreaker("binance");
        open.transitionToOpenState();

        Throwable[] never = {
            // 없는 심볼(바이낸스 -1121)·없는 지명·허용목록 밖(FMP 402)·지역 차단(451)
            org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "", null, null, null),
            org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.NOT_FOUND, "", null, null, null),
            org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "", null, null, null),
            org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.valueOf(451), "", null, null, null),
            // 우리 장치가 거절한 것. 재시도하면 퍼밋을 더 먹으려 줄에 다시 서고, 열린 문을
            // 두 번 두드린다 — 브레이커가 이 둘을 실패로 세지 않는 것과 같은 판단이다
            RequestNotPermitted.createRequestNotPermitted(drained),
            io.github.resilience4j.circuitbreaker.CallNotPermittedException
                    .createCallNotPermittedException(open),
        };
        open.reset();

        for (io.github.resilience4j.retry.Retry retry : retries.getAllRetries()) {
            for (Throwable e : never) {
                assertThat(retry.getRetryConfig().getExceptionPredicate().test(e))
                        .as("%s가 %s를 재시도한다", retry.getName(), e.getClass().getSimpleName())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("5xx와 I/O 실패는 재시도한다 — 그게 일시적 실패의 전부다")
    void retriesWhatIsActuallyTransient() {
        Throwable[] always = {
            org.springframework.web.client.HttpServerErrorException.create(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "", null, null, null),
            org.springframework.web.client.HttpServerErrorException.create(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "", null, null, null),
            new org.springframework.web.client.ResourceAccessException(
                    "read timed out", new java.net.SocketTimeoutException()),
        };

        for (io.github.resilience4j.retry.Retry retry : retries.getAllRetries()) {
            for (Throwable e : always) {
                assertThat(retry.getRetryConfig().getExceptionPredicate().test(e))
                        .as("%s가 %s를 재시도하지 않는다", retry.getName(), e.getClass().getSimpleName())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("텔레그램은 닿지 못한 것만 다시 시도한다 — chat not found를 세 번 부르면 브리핑이 통마다 늦어진다")
    void telegramRetriesOnlyWhenItCouldNotReach() {
        // ⚠️ retryExceptions·ignoreExceptions는 baseConfig의 것을 **덮어쓴다**. telegram은
        //    둘 다 따로 적으므로 기본의 것을 다시 적어야 하는데, 빠뜨리면 이 단언이 잡는다 —
        //    브레이커에서 이미 세 번 물린 함정이라 여기에도 같은 그물을 친다
        var predicate = retries.retry("telegram").getRetryConfig().getExceptionPredicate();

        assertThat(predicate.test(new io.saiden.economyhelper.telegram.TelegramClient
                .TelegramUnavailable("게이트웨이 502"))).isTrue();
        assertThat(predicate.test(new io.saiden.economyhelper.telegram.TelegramClient
                .TelegramException("텔레그램 sendMessage 거절: 400 chat not found")))
                .as("설정이 틀린 것은 세 번 불러도 같은 답이다")
                .isFalse();
        assertThat(predicate.test(new io.saiden.economyhelper.telegram.TelegramClient
                .TelegramRateLimited("429")))
                .as("우리가 빨리 물은 것이다 — 더 빨리 다시 쏘면 429를 우리가 만든다")
                .isFalse();
    }

    @Test
    @DisplayName("재시도가 브레이커 바깥, 브레이커가 리미터 바깥이다 — 안쪽이면 브레이커가 영원히 안 열린다")
    void retrySitsOutsideTheBreaker() {
        // 안쪽으로 옮기면 "매번 두 번 실패하고 세 번째에 성공"하는 상대가 브레이커에 성공만
        // 남겨 영원히 안 열린다 — 그게 정확히 잡고 싶은 상태다. 그리고 CallNotPermitted가
        // 재시도에 보이지 않게 되어 무시 목록이 뜻을 잃는다.
        // ⚠️ 숫자 리터럴로 적지 않는다 — 라이브러리가 값을 바꿔도 뜻이 살아야 한다
        int retry = order(io.github.resilience4j.spring6.retry.configure.RetryAspect.class);
        int breaker = order(io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerAspect.class);
        int limiter = order(io.github.resilience4j.spring6.ratelimiter.configure.RateLimiterAspect.class);

        assertThat(retry).as("재시도가 브레이커보다 바깥이어야 한다").isLessThan(breaker);
        assertThat(breaker).as("브레이커가 리미터보다 바깥이어야 한다").isLessThan(limiter);
    }

    private int order(Class<?> aspect) {
        return ((Ordered) context.getBean(aspect)).getOrder();
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
    @DisplayName("바이낸스 418·429는 브레이커를 연다 — 밴 중에 계속 찌르면 밴이 길어진다")
    void binanceIpBanOpensTheBreaker() {
        // ⚠️ 다른 출처의 429는 브레이커에서 빼 뒀다(텔레그램·Gemini) — 그쪽은 잠깐 물러서면
        //    끝이라 상대 장애가 아니다. **바이낸스는 다르다.** 429를 받고도 계속 부르면 IP를
        //    자동 밴하고(418) 밴 중의 호출이 밴을 연장한다. 즉 여기서는 "물러서기"가 옳고
        //    브레이커가 그 수단이다. 같은 상태 코드가 출처에 따라 다른 뜻인 자리다
        CircuitBreaker breaker = registry.circuitBreaker("binance");

        long before = breaker.getMetrics().getNumberOfFailedCalls();
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.valueOf(418), "", null, null, null));
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "", null, null, null));

        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("418·429가 실패로 세어져야 브레이커가 열려 호출이 멈춘다")
                .isEqualTo(before + 2);

        // 없는 심볼만 무시한다 — 그건 우리가 물은 것이지 상대 장애가 아니다
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new io.saiden.economyhelper.market.binance.BinanceApi.UnknownSymbol(
                        "없는 심볼", new RuntimeException()));
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("없는 심볼의 400이 브레이커를 열면 멀쩡한 다른 코인까지 막힌다")
                .isEqualTo(before + 2);

        // 밴 중이라 안 부른 것은 우리가 스스로 닫은 문이다 — 실패로 세면 밴이 풀린 뒤에도
        // 브레이커가 열린 채 남아 밴보다 오래 가는 정지가 된다(리미터 거절과 같은 자리)
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new io.saiden.economyhelper.market.binance.BinanceApi.Banned(
                        java.time.Instant.now().plusSeconds(120)));
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("우리 스로틀을 상대 장애로 세지 않는다")
                .isEqualTo(before + 2);
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
        for (String name : new String[] {"translation", "telegram", "fmp", "fmpOutlook",
                "upbit", "binance", "kisFx", "kisStock",
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
    @DisplayName("KIS의 「우리 잘못」 둘은 브레이커를 열지 않는다 — 리미터를 걷어낸 자리에 같은 보호가 있어야 한다")
    void kisOwnFaultsDoNotOpenTheBreaker() {
        // ⚠️ KIS는 resilience4j 리미터를 **걷어내고** KisThrottle로 바꿨다. 그 순간 거절이
        //    RequestNotPermitted가 아니게 되어, default 블록이 그것을 빼 둔 보호에서
        //    조용히 빠졌다 — rateLimiterRejectionDoesNotOpenTheBreaker가 KIS만은 못 보던 자리다.
        Throwable congested = new io.saiden.economyhelper.market.kis.KisThrottle.Congested(
                "KIS 호출이 밀려 대기 한도를 넘겼습니다: PT20S");
        // 애초에 만들 수 없는 요청. HTTP 호출조차 없이 나므로 상대를 건드리지도 않는다
        Throwable unsupported = new io.saiden.economyhelper.market.kis.KisStockApi.Unsupported(
                "KIS 지수 일봉에 업종코드가 없습니다: 코스피200");

        for (String name : new String[] {"kisFx", "kisStock"}) {
            CircuitBreaker breaker = registry.circuitBreaker(name);
            long before = breaker.getMetrics().getNumberOfFailedCalls();

            breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, congested);

            assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                    .as("%s가 우리 간격 문의 거절을 실패로 셌다 — 브리핑이 몰릴 때마다 "
                            + "브레이커가 열려 멀쩡한 KIS를 끊는다", name)
                    .isEqualTo(before);
        }

        CircuitBreaker stock = registry.circuitBreaker("kisStock");
        long before = stock.getMetrics().getNumberOfFailedCalls();
        stock.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, unsupported);
        assertThat(stock.getMetrics().getNumberOfFailedCalls())
                .as("설정 표에 없는 지수·심볼은 영원히 같은 실패다 — 세면 그것이 쌓여 "
                        + "미국 시세까지 막힌다(2순위 FMP는 대부분 402다)")
                .isEqualTo(before);

        // 무시 목록이 넓어지지 않았는지 함께 본다 — KIS가 진짜로 죽으면 열려야 한다
        stock.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new IllegalStateException("KIS 국내 종목 005930 조회 실패: 유효하지 않은 token 입니다."));
        assertThat(stock.getMetrics().getNumberOfFailedCalls())
                .as("그 밖의 실패는 여전히 실패다 — 무효 토큰·5xx는 알려야 한다")
                .isEqualTo(before + 1);
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


    /**
     * 우리 빈이 쓰는 리미터 이름 전부 — <b>애너테이션과 손으로 꺼낸 것을 함께</b> 본다.
     *
     * <p>둘 다 봐야 하는 이유가 있다. {@code StockPriceApi}·{@code MarketIndexApi}·
     * {@code KeximFxClient}는 <b>애너테이션을 안 쓴다</b> — 퍼밋을 얻어야 하는 자리가 되짚기
     * 루프 안의 private 메서드라 프록시가 안 닿기 때문이고, 그래서 레지스트리에서 직접 꺼내
     * 필드로 들고 있다. 애너테이션만 긁으면 그 셋이 빠져 <b>정작 한도가 빡빡한 출처들</b>
     * (공공데이터포털 일 1만·수출입은행 일 1,000)이 이 그물 밖에 남는다.
     *
     * <p>필드는 값에서 이름을 읽는다 — 정적으로는 그 문자열이 어디에도 없다(레지스트리 호출의
     * 인자로만 스쳐 간다). 이미 만들어진 빈이 들고 있는 것이 곧 그 이름이다.
     */
    private Set<String> rateLimiterNamesInUse() {
        Set<String> names = new java.util.TreeSet<>();
        for (String bean : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(bean);
            if (type == null || !type.getName().startsWith("io.saiden.economyhelper")) {
                continue;
            }
            Class<?> target = org.springframework.util.ClassUtils.getUserClass(type);
            addAnnotated(names, org.springframework.core.annotation.AnnotatedElementUtils
                    .findMergedAnnotation(target,
                            io.github.resilience4j.ratelimiter.annotation.RateLimiter.class));
            for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                addAnnotated(names, org.springframework.core.annotation.AnnotatedElementUtils
                        .findMergedAnnotation(method,
                                io.github.resilience4j.ratelimiter.annotation.RateLimiter.class));
            }
            // ⚠️ 프록시가 아니라 **대상 객체**에서 읽어야 한다. @Cacheable·@CircuitBreaker가
            //    씌운 CGLIB 하위 클래스는 같은 필드를 갖되 비어 있어, 프록시에서 읽으면
            //    조용히 null이 나오고 이 그물이 아무것도 못 잡는다
            addHeldLimiters(names,
                    org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                            context.getBean(bean)), target);
        }
        return names;
    }

    private static void addAnnotated(Set<String> names,
            io.github.resilience4j.ratelimiter.annotation.RateLimiter annotation) {
        if (annotation != null && !annotation.name().isBlank()) {
            names.add(annotation.name());
        }
    }

    /** 빈이 필드로 들고 있는 리미터 — 손으로 꺼내 쓰는 셋이 여기서 잡힌다. */
    private static void addHeldLimiters(Set<String> names, Object bean, Class<?> target) {
        for (java.lang.reflect.Field field : target.getDeclaredFields()) {
            if (!RateLimiter.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.get(bean) instanceof RateLimiter limiter) {
                    names.add(limiter.getName());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException(
                        "리미터 필드를 못 읽었다 — 그러면 이 그물에 구멍이 난다: " + field, e);
            }
        }
    }
}
