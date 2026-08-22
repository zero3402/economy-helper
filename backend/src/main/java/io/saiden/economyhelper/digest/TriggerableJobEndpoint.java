package io.saiden.economyhelper.digest;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * 발송 작업 하나를 손으로 쏘고 결과를 보는 액추에이터 엔드포인트.
 *
 * <p>{@link TriggerableJob}이 이미 「돌린다 / 마지막 결과」 둘로 좁혀져 있으므로 엔드포인트가
 * 하는 일은 작업마다 같다 — 다른 것은 <b>어느 작업이냐</b>와 <b>{@code @Endpoint} id</b>뿐이다.
 * 그래서 하위 클래스는 그 둘만 든다.
 *
 * <p>⚠️ <b>이 클래스에 {@code @Endpoint}를 달지 않는다.</b> 달면 상위 자신이 엔드포인트로
 * 등록되려 하고, 애너테이션은 <b>실체가 있는 하위</b>가 들어야 id가 따라온다.
 *
 * <p>연산이 상속으로 발견되는지는 {@code TriggerEndpointsTest}가 못 박는다 — 애너테이션만으로
 * 사는 자리라 컴파일이 통과해도 안 붙을 수 있다.
 */
public abstract class TriggerableJobEndpoint {

    private final TriggerableJob job;

    protected TriggerableJobEndpoint(TriggerableJob job) {
        this.job = job;
    }

    /**
     * 지금 쏜다.
     *
     * @param force 이미 오늘 몫이 나갔어도 다시 보낼지. 비우면 슬롯이 하루 한 번을 지킨다
     */
    @WriteOperation
    public DigestResult trigger(@Nullable Boolean force) {
        return job.run(Boolean.TRUE.equals(force));
    }

    /** 마지막 실행 결과. 스케줄 경로는 아무도 응답을 안 보므로 여기서 되짚는다. */
    @ReadOperation
    public DigestResult lastResult() {
        return job.lastResult();
    }
}
