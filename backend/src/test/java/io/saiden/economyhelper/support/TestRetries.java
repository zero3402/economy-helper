package io.saiden.economyhelper.support;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 단위 테스트용 재시도 레지스트리 — <b>{@code application.yml}의 {@code configs.default}와
 * 같은 규칙</b>으로 만든다.
 *
 * <p>⚠️ <b>{@code RetryRegistry.ofDefaults()}를 쓰면 안 된다.</b> 라이브러리 기본값은
 * <b>모든 예외를 재시도</b>하므로, "요청이 한 번만 나갔다"를 보는 단위 테스트가 조용히
 * 두 번으로 바뀌고 그 실패가 테스트 버그처럼 보인다. 더 나쁜 것은 그 반대다 — 기본값으로
 * 초록이면 4xx를 세 번 부르는 설정이 통과해 버린다.
 *
 * <p>여기 값이 yml과 어긋나지 않는지는 {@code ResilienceConfigTest}가 런타임 레지스트리를
 * 직접 꺼내 본다. 이 파일은 <b>스프링 없이 도는 테스트</b>가 같은 규칙을 쓰게 하는 자리다.
 */
public final class TestRetries {

    private TestRetries() {
    }

    /** 대기를 0으로 둔다 — 규칙만 보는 자리에서 밀리초를 기다릴 이유가 없다. */
    public static RetryRegistry registry() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(RequestNotPermitted.class, CallNotPermittedException.class)
                .build());
    }
}
