package io.saiden.economyhelper.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * <b>진단 순서를 정하는 한 줄.</b> {@code /crypto 이더}의 바이낸스 칸이 빠졌을 때 로그에
 * {@code e.toString()} 하나뿐이라 넷을 가릴 수 없었다 — 상대 장애 · 우리 브레이커 · 우리
 * 리미터 · 지역 차단. 앞의 셋은 잠시 뒤 낫고 마지막은 영영 안 낫는다.
 */
class FailureReasonTest {

    @Test
    @DisplayName("451은 지역 차단이라고 말한다 — 재시도도 이중화도 답이 아니라 리전을 옮겨야 한다")
    void namesTheRegionBlock() {
        // 바이낸스는 미국 IP를 이걸로 막는다. '조회 실패'로 뭉개면 사용자는 다시 치고
        // 우리는 코드를 파헤친다 — 실제로 그렇게 시간을 썼다
        String reason = FailureReason.of(HttpClientErrorException.create(
                HttpStatus.valueOf(451), "", null, null, null));

        assertThat(reason).contains("451").contains("지역 차단").contains("리전");
    }

    @Test
    @DisplayName("우리 브레이커·리미터가 거절한 것을 상대 장애로 적지 않는다")
    void neverBlamesTheRemoteForOurOwnGates() {
        CircuitBreaker open = CircuitBreaker.of("t", CircuitBreakerConfig.ofDefaults());
        open.transitionToOpenState();
        assertThat(FailureReason.of(CallNotPermittedException.createCallNotPermittedException(open)))
                .contains("브레이커 열림");

        RateLimiter drained = RateLimiter.of("t", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO).build());
        drained.acquirePermission();
        assertThat(FailureReason.of(RequestNotPermitted.createRequestNotPermitted(drained)))
                .contains("리미터 거절").contains("상대 장애가 아닙니다");
    }

    @Test
    @DisplayName("타임아웃과 연결 실패를 가른다 — 상대가 느린 것과 못 닿는 것은 다른 일이다")
    void tellsApartATimeoutFromAConnectFailure() {
        assertThat(FailureReason.of(new ResourceAccessException("x", new SocketTimeoutException())))
                .isEqualTo("타임아웃");
        assertThat(FailureReason.of(new ResourceAccessException("x", new UnknownHostException("h"))))
                .contains("연결 실패").contains("UnknownHostException");
    }

    @Test
    @DisplayName("그 밖의 HTTP는 상태 코드로 적는다")
    void keepsPlainStatusCodes() {
        assertThat(FailureReason.of(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "", null, null, null))).isEqualTo("HTTP 500");
    }

    @Test
    @DisplayName("감싸여 온 것은 한 겹 벗겨 본다 — 폴백이 다시 던지는 자리가 있다")
    void unwrapsOneLayer() {
        assertThat(FailureReason.of(new IllegalStateException("겉",
                HttpClientErrorException.create(HttpStatus.valueOf(451), "", null, null, null))))
                .contains("IllegalStateException").contains("지역 차단");
    }
}
