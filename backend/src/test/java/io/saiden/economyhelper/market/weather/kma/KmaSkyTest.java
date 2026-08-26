package io.saiden.economyhelper.market.weather.kma;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.weather.SkyCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기상청 어휘 → 우리 어휘. <b>강수가 하늘보다 먼저</b>라는 것이 이 표의 규칙이다. */
class KmaSkyTest {

    @Test
    @DisplayName("강수가 없으면 하늘을 읽는다 — 기상청은 셋으로만 가른다")
    void readsTheSkyWhenNothingFalls() {
        assertThat(KmaSky.of("1", "0")).isEqualTo(SkyCondition.CLEAR);
        assertThat(KmaSky.of("3", "0"))
                .as("「구름많음」은 「구름 조금」이 아니다 — 그래서 MOSTLY_CLOUDY가 생겼다")
                .isEqualTo(SkyCondition.MOSTLY_CLOUDY);
        assertThat(KmaSky.of("4", "0")).isEqualTo(SkyCondition.CLOUDY);
    }

    @Test
    @DisplayName("⚠️ 강수형태가 하늘을 이긴다 — 비 오는 시간의 SKY는 대개 「흐림」이다")
    void letsPrecipitationWinOverTheSky() {
        // 이 순서가 뒤집히면 젖은 줄의 이름이 「흐림」이 된다 — HalfDays가 막으려고
        // 있는 바로 그 모순을 출처 어휘를 옮기는 자리에서 다시 만드는 셈이다
        assertThat(KmaSky.of("4", "1")).isEqualTo(SkyCondition.RAIN);
        assertThat(KmaSky.of("4", "2")).isEqualTo(SkyCondition.SLEET);
        assertThat(KmaSky.of("4", "3")).isEqualTo(SkyCondition.SNOW);
        assertThat(KmaSky.of("3", "4")).isEqualTo(SkyCondition.SHOWERS);
    }

    @Test
    @DisplayName("초단기예보의 강수형태도 읽는다 — 출처가 어휘를 늘리는 날 조용히 모름이 되지 않게")
    void readsTheUltraShortTermTypes() {
        assertThat(KmaSky.of("1", "5")).isEqualTo(SkyCondition.DRIZZLE);
        assertThat(KmaSky.of("1", "6")).isEqualTo(SkyCondition.SLEET);
        assertThat(KmaSky.of("1", "7")).isEqualTo(SkyCondition.SNOW);
    }

    @Test
    @DisplayName("못 읽은 값은 모름이다 — 「아니다」가 아니라서 거부권이 없다")
    void fallsBackToUnknown() {
        assertThat(KmaSky.of("9", "0")).isEqualTo(SkyCondition.UNKNOWN);
        assertThat(KmaSky.of(null, null)).isEqualTo(SkyCondition.UNKNOWN);
    }

}
