package io.saiden.economyhelper.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 수동 발송 엔드포인트 둘이 <b>실제로 등록되는지</b>.
 *
 * <p>이 둘은 애너테이션만으로 사는 자리라 <b>컴파일이 통과해도 안 붙을 수 있다.</b>
 * 붙지 않으면 {@code /actuator/digest}가 404가 되는데, 그건 아침 브리핑이 안 나갔을 때
 * 사람이 손으로 쏘는 유일한 수단이라 조용히 사라지면 안 된다.
 *
 * <p>연산 두 개를 함께 본다 — 쓰기({@code trigger})와 읽기({@code lastResult})다.
 * 하나만 붙어도 등록 자체는 성공한 것처럼 보이므로 개수까지 못 박는다.
 */
@SpringBootTest
class TriggerEndpointsTest {

    @Autowired
    WebEndpointsSupplier endpoints;

    private ExposableWebEndpoint endpoint(String id) {
        return endpoints.getEndpoints().stream()
                .filter(each -> each.getEndpointId().toLowerCaseString().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "액추에이터에 '" + id + "' 엔드포인트가 없습니다 — /actuator/" + id + "가 404가 된다"));
    }

    @Test
    @DisplayName("브리핑 수동 발송이 읽기·쓰기 둘 다 달고 등록된다")
    void registersTheDigestEndpoint() {
        assertThat(endpoint("digest").getOperations())
                .extracting(operation -> operation.getType())
                .containsExactlyInAnyOrder(OperationType.READ, OperationType.WRITE);
    }

    @Test
    @DisplayName("날씨 알람 수동 발송도 같은 모양으로 등록된다")
    void registersTheWeatherEndpoint() {
        assertThat(endpoint("weather").getOperations())
                .extracting(operation -> operation.getType())
                .containsExactlyInAnyOrder(OperationType.READ, OperationType.WRITE);
    }
}
