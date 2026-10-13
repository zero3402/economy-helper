package io.saiden.economyhelper.infrastructure.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.testsupport.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KisCallTest {

    @Test
    @DisplayName("토큰을 먼저 받고 나서 간격 문을 지난다 — 새 토큰 POST 직후에 GET이 붙어 나가지 않게")
    void takesTheTokenBeforePacing() {
        List<String> events = new ArrayList<>();
        KisTokenStore tokens = new KisTokenStore(RestClient.builder(),
                KisFixtures.credentials("http://localhost:1"), null, Clock.systemUTC(), KisFixtures.unpaced()) {
            @Override
            public String token() {
                events.add("token");
                return KisFixtures.TOKEN;
            }
        };
        KisThrottle throttle = new KisThrottle(TestProperties.builder().kisPacing(Duration.ZERO, Duration.ZERO).build()) {
            @Override
            public void pace() {
                events.add("pace");
            }
        };
        KisCall call = KisFixtures.call("http://localhost:1", tokens, throttle);

        // 닿지 않는 주소라 실패한다 — 보는 것은 실패 전까지의 순서다
        assertThatThrownBy(() -> call.get(Empty.class, "TR", "순서 확인", uri -> uri.path("/x").build()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events).startsWith("token", "pace");
    }

    @Test
    @DisplayName("토큰 단계의 실패는 이유를 그대로 싣는다 — 「IllegalStateException」만 남으면 재발급 대기인지 키 누락인지 모른다")
    void keepsTheReasonOfATokenFailure() {
        KisTokenStore tokens = new KisTokenStore(RestClient.builder(),
                KisFixtures.credentials("http://localhost:1"), null, Clock.systemUTC(), KisFixtures.unpaced()) {
            @Override
            public String token() {
                throw new IllegalStateException("KIS 토큰이 무효로 확인돼 버렸습니다 — 42분 뒤에 다시 발급합니다");
            }
        };
        KisCall call = KisFixtures.call("http://localhost:1", tokens, KisFixtures.unpaced());

        assertThatThrownBy(() -> call.get(Empty.class, "TR", "환율", uri -> uri.path("/x").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42분 뒤에 다시 발급합니다");
    }

    record Empty(String resultCode, String message, String messageCode) implements KisResponse {}
}
