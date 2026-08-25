package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.weather.HalfDay.Half;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>한 블록의 강수 값은 한 예보에서 나온다.</b>
 *
 * <p>요약(하루)과 반나절이 다른 예보에서 오면 통이 제 말을 뒤집는다. 1순위 AccuWeather는
 * 하루를 낮/밤 두 칸으로만 주므로 반나절이 통째로 Open-Meteo 시간별에서 오는데, 그 둘이
 * 어긋난 화면을 실측으로 양쪽 다 봤다 — 「소나기 61%인데 오전·오후 마름」과 그 반대인
 * 「맑음인데 종일 비」다.
 *
 * <p>⚠️ <b>지나간 날은 예외다.</b> 거기 담긴 것은 예보가 아니라 실측이라 손대면 사실을 고친다.
 */
class WeatherDailyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 26);
    private static final BigDecimal LOW = new BigDecimal("24.2");
    private static final BigDecimal HIGH = new BigDecimal("32.0");

    @Test
    @DisplayName("요약이 약속한 비를 반나절이 못 보이면 요약을 낮춘다 — 실측 「소나기 61%」인데 둘 다 마름")
    void lowersASummaryThePartsCannotBack() {
        // 실측 2026-08-26 미금역: 요약은 AccuWeather 낮 칸(소나기 61%),
        // 반나절은 Open-Meteo 시간별 봉우리 18%로 둘 다 말랐다
        Weather.Daily day = Weather.Daily
                .withChance(DAY, SkyCondition.SHOWERS, LOW, HIGH, 61)
                .withHalves(List.of(
                        HalfDay.dry(Half.MORNING, SkyCondition.CLOUDY, 18),
                        HalfDay.dry(Half.AFTERNOON, SkyCondition.PARTLY_CLOUDY, 12)));

        assertThat(day.sky()).as("반나절이 말한 것 중 가장 무거운 것").isEqualTo(SkyCondition.CLOUDY);
        assertThat(day.precipitationChance()).as("확률도 시간별 봉우리로 갈린다").isEqualTo(18);
    }

    @Test
    @DisplayName("요약이 반나절보다 가벼우면 올린다 — 「맑음 + 종일 비」는 어떻게 읽어도 모순이다")
    void raisesASummaryThePartsOutrun() {
        // ⚠️ codex 적대적 리뷰가 잡은 반례다. 한동안 낮추는 쪽만 고쳤는데, 그 근거가
        //    「흐림에 한때 비는 『대체로 흐리고 한때 비』로 읽힌다」였다. 그 해석이 이 입력에서
        //    무너진다 — AccuWeather가 맑음이라 하고 시간별이 종일 비라고 하는 날이다
        Weather.Daily day = Weather.Daily
                .withChance(DAY, SkyCondition.CLEAR, LOW, HIGH, 5)
                .withHalves(List.of(
                        HalfDay.withChance(LocalTime.of(0, 0), LocalTime.of(11, 0),
                                SkyCondition.RAIN, 100),
                        HalfDay.withChance(LocalTime.of(12, 0), LocalTime.of(23, 0),
                                SkyCondition.RAIN, 100)));

        assertThat(day.sky()).isEqualTo(SkyCondition.RAIN);
        assertThat(day.precipitationChance()).isEqualTo(100);
    }

    @Test
    @DisplayName("둘 다 비라고 하면 종류가 달라도 1순위를 그대로 둔다 — 소나기를 이슬비로 낮추지 않는다")
    void keepsTheSummaryWhenBothSayRain() {
        // ⚠️ 이것이 규칙을 좁힌 이유다. 한동안 반나절이 있는 모든 날에 요약을 갈아 끼웠는데,
        //    실측(네 역 × 닷새)으로 스무 날 중 16일이 바뀌고 실제 모순은 4일뿐이었다.
        //    나머지는 두 출처가 둘 다 비라는데 1순위를 버린 것이고, 소나기 → 이슬비는
        //    **경고를 깎는다** — AccuWeather는 동 단위 지점 예보라 1순위인 것이다
        Weather.Daily day = Weather.Daily
                .withChance(DAY, SkyCondition.SHOWERS, LOW, HIGH, 61)
                .withHalves(List.of(
                        HalfDay.withChance(LocalTime.of(13, 0), LocalTime.of(15, 0),
                                SkyCondition.DRIZZLE, 80),
                        HalfDay.dry(Half.AFTERNOON, SkyCondition.CLOUDY, 20)));

        assertThat(day.sky()).as("둘 다 비라고 한다 — 어긋나지 않는다").isEqualTo(SkyCondition.SHOWERS);
        assertThat(day.precipitationChance()).as("확률은 그래도 시간별 봉우리다").isEqualTo(80);
    }

    @Test
    @DisplayName("마른 반나절이 강수 어휘를 들고 있으면 그것도 「비라고 말한 것」이다")
    void countsADryHalfThatStillNamesRain() {
        // HalfDays.skyOf가 「가장 흔한 코드」라 마른 반나절도 이슬비일 수 있고,
        // 그때 화면은 「☔ 오전 이슬비」라고 말하고 있다 — 요약 「소나기」와 어긋나지 않는다
        Weather.Daily day = Weather.Daily
                .withChance(DAY, SkyCondition.SHOWERS, LOW, HIGH, 61)
                .withHalves(List.of(
                        HalfDay.dry(Half.MORNING, SkyCondition.DRIZZLE, 40),
                        HalfDay.dry(Half.AFTERNOON, SkyCondition.CLOUDY, 20)));

        assertThat(day.sky()).isEqualTo(SkyCondition.SHOWERS);
    }

    @Test
    @DisplayName("지나간 날의 실측은 고치지 않는다 — 「맑음 / 강수량 0.1mm」가 나왔던 자리다")
    void neverRewritesAMeasuredDay() {
        // ⚠️ codex가 잡은 두 번째 반례다. 시간별 강수량이 전부 0.1mm 문턱 아래면 반나절이
        //    마른 것으로 잡히는데, 일별 실측 강수량은 그대로 남는다. 그때 하늘까지 낮추면
        //    「비가 왔다고 적으면서 맑았다고 말하는」 화면이 된다.
        //    확률을 다시 세지 않는 날(= 지나간 날)에는 하늘도 건드리지 않는다
        Weather.Daily day = Weather.Daily
                .withAmount(DAY, SkyCondition.RAIN, LOW, HIGH, new BigDecimal("0.05"))
                .withHalves(List.of(
                        HalfDay.dry(Half.MORNING, SkyCondition.CLEAR, null),
                        HalfDay.dry(Half.AFTERNOON, SkyCondition.CLEAR, null)));

        assertThat(day.sky()).as("실측이 비라고 하면 비다").isEqualTo(SkyCondition.RAIN);
        assertThat(day.precipitationAmount()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("반나절이 전부 어휘를 모르면 요약을 그대로 둔다 — 모르는 것으로 아는 것을 덮지 않는다")
    void keepsTheSummaryWhenThePartsAreUnknown() {
        Weather.Daily day = Weather.Daily
                .withChance(DAY, SkyCondition.SHOWERS, LOW, HIGH, 61)
                .withHalves(List.of(
                        HalfDay.dry(Half.MORNING, SkyCondition.UNKNOWN, 18),
                        HalfDay.dry(Half.AFTERNOON, SkyCondition.UNKNOWN, 12)));

        assertThat(day.sky()).isEqualTo(SkyCondition.SHOWERS);
    }

    @Test
    @DisplayName("반나절이 없으면 아무것도 바뀌지 않는다 — 보충이 실패한 날이다")
    void leavesADayWithoutHalvesAlone() {
        Weather.Daily day = Weather.Daily
                .withChance(DAY, SkyCondition.SHOWERS, LOW, HIGH, 61);

        assertThat(day.sky()).isEqualTo(SkyCondition.SHOWERS);
        assertThat(day.precipitationChance()).isEqualTo(61);
        assertThat(day.halves()).isEmpty();
    }
}
