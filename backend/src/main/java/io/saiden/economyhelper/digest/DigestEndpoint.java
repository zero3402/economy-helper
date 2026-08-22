package io.saiden.economyhelper.digest;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/digest} — 아침 브리핑을 손으로 쏘고 마지막 결과를 본다.
 *
 * <p>⚠️ 쓰기 연산이 <b>텔레그램 전체 발송을 즉시 일으킨다.</b> 그래서 액추에이터를 앱 포트와
 * 분리해 띄운다 — 한 포트로 합쳐야 하는 호스트에서는 이 id를 노출 목록에서 빼는 것이 방어다
 * ({@code application.yml}의 {@code management.endpoints.web.exposure} 참조).
 */
@Component
@Endpoint(id = "digest")
public class DigestEndpoint extends TriggerableJobEndpoint {

    public DigestEndpoint(DailyDigestJob job) {
        super(job);
    }
}
