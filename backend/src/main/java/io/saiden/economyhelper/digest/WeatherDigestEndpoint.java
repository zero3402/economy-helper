package io.saiden.economyhelper.digest;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * 오전 6시를 기다리지 않고 날씨 알람을 한 번 돌린다.
 *
 * <p>이것이 없으면 <b>알람을 확인할 방법이 아침 6시뿐이다.</b> {@link WeatherDigestJob}의
 * {@code force} 인자도 그때까지는 아무도 참을 넘기지 않아 죽은 분기였다.
 *
 * <p>일반 컨트롤러가 아니라 액추에이터 엔드포인트로 둔 이유는 {@link DigestEndpoint}와 같다 —
 * <b>포트를 분리하기 위해서다.</b> 이 호출은 구독자 전원에게 즉시 방송을 날리므로 애플리케이션
 * 포트(8080)에 있으면 외부 누구나 트리거할 수 있다. {@code management.server.port}로 8081에
 * 격리한다.
 *
 * <pre>
 * curl -X POST localhost:8081/actuator/weather \
 *   -H 'Content-Type: application/json' -d '{"force":true}'
 * </pre>
 */
@Component
@Endpoint(id = "weather")
public class WeatherDigestEndpoint {

    private final WeatherDigestJob job;

    public WeatherDigestEndpoint(WeatherDigestJob job) {
        this.job = job;
    }

    /**
     * @param force 생략하면 이미 보낸 슬롯은 건너뛴다. 같은 날을 반복 점검할 때만 참을 준다
     */
    @WriteOperation
    public DigestResult trigger(@Nullable Boolean force) {
        return job.run(Boolean.TRUE.equals(force));
    }

    /**
     * 마지막 실행 결과를 <b>발송 없이</b> 본다 ({@code GET /actuator/weather}).
     *
     * <p>"오늘 아침 날씨가 왜 안 왔나"를 확인하려고 실제 방송을 한 번 더 쏘는 것은 구독자에게
     * 중복을 보내는 일이다. 읽기와 쓰기를 갈라 둔 이유가 그것이다.
     */
    @ReadOperation
    public DigestResult lastResult() {
        return job.lastResult();
    }
}
