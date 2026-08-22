package io.saiden.economyhelper.digest;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/weather} — 오전 6시 날씨 알람을 손으로 쏘고 마지막 결과를 본다.
 *
 * <p>브리핑과 <b>슬롯이 갈려 있어야</b> 둘 중 하나만 나가는 일이 없다({@link DigestSlot}의
 * 접두사). 여기서 강제로 쏘면 그날 슬롯을 다시 잡으므로, 스케줄이 아직 안 돈 시각에 쓰면
 * 정시 발송이 건너뛴다.
 */
@Component
@Endpoint(id = "weather")
public class WeatherDigestEndpoint extends TriggerableJobEndpoint {

    public WeatherDigestEndpoint(WeatherDigestJob job) {
        super(job);
    }
}
