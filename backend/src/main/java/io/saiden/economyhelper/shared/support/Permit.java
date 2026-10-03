package io.saiden.economyhelper.shared.support;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

/**
 * 리미터 퍼밋을 <b>HTTP 호출 자리에서</b> 얻는 한 줄 — 되짚기 루프를 도는 클라이언트 둘(공공데이터포털·수출입은행)이
 * 나눠 쓴다.
 *
 * <p>⚠️ <b>{@code acquirePermission()}은 던지지 않는다 — boolean을 준다</b>(resilience4j 2.4.0, javap로 확인).
 * 반환값을 버리면 <b>포화 상황에 그대로 HTTP가 나간다</b> — 스로틀이 아니라 장식이 된다. 거절을 {@link RequestNotPermitted}로 올려야 브레이커가 그것을(baseConfig의 {@code ignoreExceptions}로) 상대 장애가
 * 아닌 우리 스로틀로 읽는다.
 *
 * <p>애너테이션({@code @RateLimiter})을 바깥 {@code @Cacheable} 메서드에 걸면 <b>진입 한 번에 퍼밋 하나</b>인데,
 * 되짚기 루프는 그 안에서 최대 열 번 HTTP를 부른다 — 그래서 레지스트리에서 직접 꺼내 호출 자리마다 얻는다.
 */
public final class Permit {

    private Permit() {
    }

    /**
     * @return 그 이름의 리미터. {@code registry}가 {@code null}이면 {@code null} — 테스트가 그렇게 만든다(세지 않는다)
     */
    public static RateLimiter of(RateLimiterRegistry registry, String name) {
        return registry == null ? null : registry.rateLimiter(name);
    }

    /** @throws RequestNotPermitted 타임아웃 안에 퍼밋을 못 얻었을 때. {@code null} 리미터는 언제나 통과다 */
    public static void acquire(RateLimiter limiter) {
        if (limiter != null && !limiter.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(limiter);
        }
    }
}
