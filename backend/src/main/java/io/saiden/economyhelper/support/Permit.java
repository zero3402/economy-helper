package io.saiden.economyhelper.support;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

/**
 * 리미터 퍼밋을 <b>HTTP 호출 자리에서</b> 얻는 한 줄 — 되짚기 루프를 도는 클라이언트 둘(공공데이터포털·수출입은행)이
 * 나눠 쓴다.
 *
 * <p><b>왜 한 곳에 있어야 하나.</b> 같은 네 줄이 두 파일에 있었고, 한쪽({@code DataGoRequest})에서 고친 결함이
 * 다른 쪽({@code KeximFxClient})에 그대로 남아 있었다 — 그 파일 주석이 「형제인 이쪽에 남아 있었다」고 스스로 적어
 * 두고 있었다. 저장소가 다른 곳에서 막으려는 그 사고다.
 *
 * <p>⚠️ <b>{@code acquirePermission()}은 던지지 않는다 — boolean을 준다.</b> resilience4j 2.4.0의 시그니처가
 * {@code boolean acquirePermission()}이라(javap로 확인) 타임아웃 안에 퍼밋을 못 얻으면 조용히 false다. 반환값을
 * 버리던 동안 <b>리미터가 가장 필요한 포화 상황에 그대로 HTTP가 나갔다</b> — 스로틀이 아니라 장식이었다.
 * 거절을 {@link RequestNotPermitted}로 올려야 브레이커가 그것을(baseConfig의 {@code ignoreExceptions}로) 상대 장애가
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
