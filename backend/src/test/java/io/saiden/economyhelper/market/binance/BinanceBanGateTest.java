package io.saiden.economyhelper.market.binance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 밴 문의 <b>산수</b>만 본다 — Redis 없이 프로세스 사본으로 돈다.
 *
 * <p>이 클래스가 있는 이유는 브레이커가 못 하는 일을 하기 때문이다. {@code binance} 브레이커는
 * 10건 중 50%가 실패해야 열리므로 <b>418 하나로는 안 열리고 다섯 번을 더 부른다.</b>
 * 그 다섯 번이 정확히 바이낸스가 밴을 연장하는 호출이다.
 */
class BinanceBanGateTest {

    private static final Instant NOW = Instant.parse("2026-08-20T05:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("밴 전에는 문이 열려 있다 — 평상시에 값을 막으면 안 된다")
    void staysOpenUntilBanned() {
        assertThat(gate().bannedUntil()).isNull();
    }

    @Test
    @DisplayName("밴을 기록하면 그 시각까지 닫혀 있다")
    void closesUntilTheGivenInstant() {
        BinanceBanGate gate = gate();

        gate.ban(Duration.ofMinutes(2));

        assertThat(gate.bannedUntil()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("짧은 밴이 긴 밴을 지우지 않는다 — 429 하나가 앞선 418을 덮으면 그 순간 다시 찌른다")
    void neverShortensAnExistingBan() {
        BinanceBanGate gate = gate();

        gate.ban(Duration.ofHours(3));
        gate.ban(BinanceBanGate.WARNING_BACKOFF);

        assertThat(gate.bannedUntil())
                .as("나중에 온 짧은 경고가 3시간 밴을 1분으로 깎으면 안 된다")
                .isEqualTo(NOW.plus(Duration.ofHours(3)));
    }

    @Test
    @DisplayName("더 긴 밴은 늘린다 — 밴 중에 또 418을 받으면 실제로 연장된 것이다")
    void extendsWhenTheNewBanIsLonger() {
        BinanceBanGate gate = gate();

        gate.ban(Duration.ofMinutes(2));
        gate.ban(Duration.ofMinutes(30));

        assertThat(gate.bannedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("시각이 지나면 문이 스스로 열린다 — 밴은 우리가 푸는 것이 아니다")
    void reopensOnceTheBanHasPassed() {
        MovableClock clock = new MovableClock(NOW);
        BinanceBanGate gate = new BinanceBanGate(null, clock);
        gate.ban(Duration.ofMinutes(2));

        clock.now = NOW.plusSeconds(119);
        assertThat(gate.bannedUntil()).as("아직 밴 중이다").isEqualTo(NOW.plusSeconds(120));

        clock.now = NOW.plusSeconds(121);
        assertThat(gate.bannedUntil()).as("지나면 아무것도 안 막는다").isNull();
    }

    /** 시간을 밀어 볼 수 있는 시계 — 밴은 시각으로 풀리므로 그 경계를 넘겨 봐야 한다. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** @return Redis 없는 문. Redis가 죽었을 때와 같은 경로다 */
    private BinanceBanGate gate() {
        return new BinanceBanGate(null, clock);
    }
}
