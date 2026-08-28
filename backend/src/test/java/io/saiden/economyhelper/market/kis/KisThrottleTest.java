package io.saiden.economyhelper.market.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>고정 윈도 리미터로는 표현할 수 없던 규칙</b>을 잠근다.
 *
 * <p>여기 있는 주장은 하나다: <b>호출 사이가 벌어진다.</b> "1초에 몇 건"이 아니다 — 그건
 * resilience4j가 이미 하는데, 그것으로는 윈도 경계에서 두 호출이 120ms 간격으로 나갔고
 * KIS가 거절했다(실측). 그래서 세는 것이 아니라 <b>띄우는 것</b>을 본다.
 */
class KisThrottleTest {

    @Test
    @DisplayName("연달아 부르면 사이가 벌어진다 — 이것이 리미터가 못 하던 일이다")
    void keepsAGapBetweenConsecutiveCalls() {
        KisThrottle throttle = new KisThrottle(Duration.ofMillis(300), Duration.ofSeconds(5));

        long started = System.nanoTime();
        throttle.pace();
        throttle.pace();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(280));
    }

    @Test
    @DisplayName("첫 호출은 기다리지 않는다 — 켜고 나서 첫 조회가 느려질 이유가 없다")
    void doesNotMakeTheFirstCallWait() {
        KisThrottle throttle = new KisThrottle(Duration.ofSeconds(30), Duration.ofSeconds(5));

        long started = System.nanoTime();
        throttle.pace();

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("줄이 너무 길면 기다리지 않고 던진다 — 예전 timeoutDuration이 하던 몫이다")
    void throwsWhenTheQueueIsLongerThanTheLimit() throws Exception {
        // 던져야 상위 서비스가 다음 출처로 넘어간다. 무한히 기다리면 사용자는 답을 못 받는다
        KisThrottle throttle = new KisThrottle(Duration.ofSeconds(2), Duration.ofMillis(50));
        throttle.pace();

        CountDownLatch waiting = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            throttle.pace();
        });
        assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);

        assertThatThrownBy(throttle::pace)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("대기 한도");
        holder.join();
    }

    @Test
    @DisplayName("간격이 0이면 기다리지 않는다 — 테스트와 실전 계정(초당 20건)이 이 자리를 쓴다")
    void doesNotWaitWhenTheIntervalIsZero() {
        KisThrottle throttle = KisFixtures.unpaced();

        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            throttle.pace();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofMillis(500));
    }
}
