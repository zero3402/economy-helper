package io.saiden.economyhelper.digest;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.digest.DailyDigestJobTest.InMemoryHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이 클래스가 있는 이유는 <b>사고가 났기 때문이다.</b> 두 잡이 슬롯 기계를 각자 들고 있었고,
 * 나중에 붙인 날씨 잡이 접두사를 빠뜨려 6시 날씨가 슬롯을 잡는 바람에 9시 브리핑이 통째로
 * 나가지 않았다. 여기서 잠그는 것은 그 사고가 다시 못 나게 하는 성질들이다.
 */
class DigestSlotTest {

    /** KST 2026-08-17 06:00. */
    private static final Instant NOW = Instant.parse("2026-08-16T21:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static DigestSlot slot(SendHistory history, String prefix) {
        return new DigestSlot(history, Clock.fixed(NOW, ZoneOffset.UTC), SEOUL, prefix, "test");
    }

    @Test
    @DisplayName("접두사가 다르면 서로를 막지 않는다 — 저장소가 하나라 이게 유일한 칸막이다")
    void differentPrefixesNeverCollide() {
        InMemoryHistory history = new InMemoryHistory();

        DigestSlot.Claim weather = slot(history, "weather-").claim(false);
        DigestSlot.Claim digest = slot(history, "").claim(false);

        assertThat(weather.id()).isEqualTo("weather-2026-08-17");
        assertThat(digest.id()).isEqualTo("2026-08-17");
        assertThat(digest.proceed())
                .as("6시 날씨가 잡았다고 9시 브리핑이 막히면 안 된다 — 실제로 그랬다")
                .isTrue();
    }

    @Test
    @DisplayName("슬롯은 날짜 단위다 — 시각을 넣으면 09:10 재시도가 다른 슬롯이 되어 두 번 나간다")
    void slotIsPerDayNotPerTick() {
        InMemoryHistory history = new InMemoryHistory();
        DigestSlot slot = slot(history, "");

        assertThat(slot.claim(false).proceed()).isTrue();
        assertThat(slot.claim(false).proceed()).isFalse();
    }

    @Test
    @DisplayName("이름만 묻는 것으로는 선점되지 않는다 — 보낼 것이 없어 건너뛸 때 쓴다")
    void askingForTheNameNeverClaims() {
        InMemoryHistory history = new InMemoryHistory();
        DigestSlot slot = slot(history, "weather-");

        assertThat(slot.id()).isEqualTo("weather-2026-08-17");

        assertThat(slot.claim(false).proceed())
                .as("이름을 물었다고 선점되면 설정을 고쳐 넣어도 그날은 영영 안 나간다")
                .isTrue();
    }

    @Test
    @DisplayName("force로 지나친 선점은 되돌리지 않는다 — 남이 잡은 것을 푸는 셈이 된다")
    void releaseOnlyGivesBackWhatItTook() {
        InMemoryHistory history = new InMemoryHistory();
        DigestSlot slot = slot(history, "");
        slot.claim(false);                       // 누군가 이미 오늘 몫을 잡았다

        DigestSlot.Claim forced = slot.claim(true);
        assertThat(forced.proceed()).as("force는 진행한다").isTrue();
        assertThat(forced.claimed()).as("다만 잡은 것은 아니다").isFalse();

        slot.release(forced);

        assertThat(history.claimed)
                .as("되돌렸다면 원래 발송이 한 번 더 나갈 수 있게 된다")
                .contains("2026-08-17");
    }

    @Test
    @DisplayName("Redis가 죽으면 사유를 값에 담아 건너뛴다 — 예외를 올리면 스케줄러가 삼킨다")
    void turnsRedisFailureIntoAReason() {
        SendHistory dead = new InMemoryHistory() {
            @Override
            public boolean claim(String slot) {
                throw new IllegalStateException("연결할 수 없습니다");
            }
        };

        DigestSlot.Claim claim = slot(dead, "").claim(false);

        assertThat(claim.proceed()).isFalse();
        assertThat(claim.blockedReason()).contains("Redis");
        assertThat(claim.id()).as("어느 슬롯이 막혔는지는 알려 준다").isEqualTo("2026-08-17");
    }
}
