package io.saiden.economyhelper.support;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 실패 하나를 <b>사람이 읽을 한 줄</b>로 — 그리고 그 줄이 다음 사람의 진단 순서를 정한다.
 *
 * <p><b>왜 필요했나.</b> {@code /crypto 이더}에서 바이낸스 칸이 빠졌는데 화면에는
 * {@code 조회 실패} 넉 자, 로그에는 {@code e.toString()} 한 줄뿐이었다. 그 상태로는 넷을
 * 가릴 수 없다 — 상대가 죽었나, <b>우리 브레이커가 열렸나</b>, <b>우리 리미터가 거절했나</b>,
 * <b>지역 차단인가</b>. 앞의 셋은 잠시 뒤 낫고 마지막은 영영 안 낫는데, 사용자에게 할 말이
 * 정반대다.
 *
 * <p>특히 <b>451을 따로 알아본다.</b> 바이낸스는 미국 IP를 차단하고
 * ({@code BinanceApi} javadoc: "리전을 옮기면 가장 먼저 깨질 연동"), 그때는 재시도도
 * 이중화도 답이 아니다 — <b>리전을 옮기는 것만</b>이 답이라 로그가 그렇게 말해야 한다.
 *
 * <p><b>우리 장치가 거절한 것을 상대 장애로 적지 않는다.</b> {@code RequestNotPermitted}는
 * 우리 리미터이고 {@code CallNotPermittedException}은 우리 브레이커다. 이걸 "상대가 죽었다"로
 * 읽으면 멀쩡한 상대를 의심하며 시간을 버린다 — 브레이커 설정이 이 둘을 실패로 세지 않는 것과
 * 같은 판단이다.
 *
 * <p><b>어디에 쓰고 어디에 안 쓰는지가 규칙이다.</b> 이것은 <b>외부 호출</b> 실패를 분류한다 —
 * 상대가 죽었는지 우리가 끊었는지를 가르는 것이 전부이기 때문이다. 그래서 Redis 접근 실패
 * ({@code KisTokenStore}·{@code KisExchangeCache}·{@code FmpQuotaGuard}·{@code DigestSlot})와
 * 내부 조립 실패({@code TelegramWebhookController}의 답 만들기)에는 <b>쓰지 않는다</b> —
 * 거기서는 브레이커도 리미터도 451도 나올 수 없어 예외 이름이 이미 그 자체로 답이고,
 * 굳이 통과시키면 "분류했다"는 인상만 남는다. 로그가 곳곳에서 다른 모양인 것이 아니라
 * <b>다른 종류의 실패라서 다른 것</b>이다.
 *
 * <p>절대 던지지 않는다. {@code catch} 안에서 불리는 자리라 그렇다
 * ({@code KisHeaders.reasonOf}와 같은 규칙).
 */
public final class FailureReason {

    private FailureReason() {
    }

    /** 지역 차단. 재시도·이중화로 못 고친다 — 리전을 옮겨야 한다. */
    private static final int UNAVAILABLE_FOR_LEGAL_REASONS = 451;

    /**
     * @return {@code 지역 차단(451)}·{@code 브레이커 열림}처럼 짧은 이유. 모르면 예외 이름
     */
    public static String of(Throwable e) {
        return of(e, 3);
    }

    /**
     * @param depth 원인을 몇 겹까지 벗길지. <b>순환하는 원인 사슬에서 멈추기 위한 것</b>이다 —
     *              {@code initCause}가 자기 참조는 막지만 A→B→A는 막지 않는다. 이 메서드는
     *              {@code catch} 안에서 불리므로 스택오버플로도 던지는 것과 같다
     */
    private static String of(Throwable e, int depth) {
        if (e == null) {
            return "알 수 없음";
        }
        if (e instanceof CallNotPermittedException) {
            // 우리가 스스로 끊은 것이다. 상대를 의심하기 전에 왜 열렸는지를 봐야 한다
            return "브레이커 열림 — 앞선 실패가 쌓여 우리가 끊었습니다";
        }
        if (e instanceof RequestNotPermitted) {
            return "리미터 거절 — 우리 스로틀입니다, 상대 장애가 아닙니다";
        }
        if (e instanceof RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == UNAVAILABLE_FOR_LEGAL_REASONS) {
                return "HTTP 451 지역 차단 — 이 IP에서는 영영 안 됩니다. "
                        + "재시도·이중화가 아니라 리전을 옮겨야 합니다";
            }
            return "HTTP " + status;
        }
        if (e instanceof ResourceAccessException access) {
            Throwable cause = access.getCause();
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return "타임아웃";
            }
            return cause instanceof IOException io
                    ? "연결 실패 (" + io.getClass().getSimpleName() + ")"
                    : "연결 실패";
        }
        // 감싸여 온 것을 한 겹 벗겨 본다 — Failover가 IllegalStateException으로 다시 던지는 자리가 있다
        return e.getCause() == null || e.getCause() == e || depth <= 0
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + " — " + of(e.getCause(), depth - 1);
    }
}
