package io.saiden.economyhelper.digest;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * 스케줄을 기다리지 않고 정기 발송을 한 번 돌린다 — 8단계 스모크 테스트의 진입점이다.
 *
 * <p>일반 컨트롤러가 아니라 액추에이터 엔드포인트로 둔 이유는 <b>포트를 분리하기 위해서다</b>.
 * 이 호출은 구독자 전원에게 즉시 방송을 날리므로 애플리케이션 포트(8080)에 있으면
 * 외부 누구나 트리거할 수 있다. {@code management.server.port}로 8081에 격리한다.
 *
 * <pre>
 * curl -X POST localhost:8081/actuator/digest \
 *   -H 'Content-Type: application/json' -d '{"force":true}'
 * </pre>
 */
@Component
@Endpoint(id = "digest")
public class DigestEndpoint {

    private final DailyDigestJob job;

    public DigestEndpoint(DailyDigestJob job) {
        this.job = job;
    }

    /**
     * @param force 생략하면 이미 보낸 시간대는 건너뛴다. 같은 시간대를 반복 점검할 때만 참을 준다
     */
    @WriteOperation
    public DigestResult trigger(@Nullable Boolean force) {
        return job.run(Boolean.TRUE.equals(force));
    }
}
