package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 말로 적은 기간이 실제 날짜로 펴지는 규칙을 고정한다.
 *
 * <p>{@code today}를 인자로 받으므로 벽시계에 기대지 않는다 — 그 자체가 이 설계의 요점이다.
 */
class WeatherPeriodTest {

    /** 그 지역의 오늘. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);

    @Test
    @DisplayName("아무것도 안 적으면 오늘 하루치다 — 알람도 검색도 기본이 같다")
    void defaultsToASingleDayToday() {
        WeatherPeriod period = WeatherPeriod.of(TODAY, null, null, null);

        assertThat(period.from()).isEqualTo(TODAY);
        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.length()).isEqualTo(1);
    }

    @Test
    @DisplayName("'내일'은 부를 때마다 그날 기준으로 펴진다 — 캐시가 내일을 고정하면 안 된다")
    void relativeOffsetsAreResolvedAtCallTime() {
        assertThat(WeatherPeriod.of(TODAY, null, 1, 1).from())
                .isEqualTo(LocalDate.of(2026, 8, 18));

        // 같은 해석 결과(offsetDays=1)를 하루 뒤에 다시 펴면 날짜도 하루 뒤다.
        // LLM이 절대 날짜를 굳혀 줬다면 여기가 8월 18일에 머물렀을 것이다
        assertThat(WeatherPeriod.of(TODAY.plusDays(1), null, 1, 1).from())
                .isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    @DisplayName("'일주일치'는 오늘부터 7일이다")
    void spansTheRequestedNumberOfDays() {
        WeatherPeriod period = WeatherPeriod.of(TODAY, null, 0, 7);

        assertThat(period.from()).isEqualTo(TODAY);
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 8, 23));
        assertThat(period.length()).isEqualTo(7);
    }

    @Test
    @DisplayName("절대 날짜는 그대로 쓴다 — 낡지 않으므로 캐시해도 안전하다")
    void usesAnAbsoluteDateAsGiven() {
        WeatherPeriod period = WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1);

        assertThat(period.from()).isEqualTo(LocalDate.of(2025, 8, 19));
        assertThat(period.past(TODAY)).as("지난 날짜는 재분석으로 가야 한다").isTrue();
    }

    @Test
    @DisplayName("끝이 상한을 넘으면 볼 수 있는 데까지만 — 날짜가 화면에 적히므로 속이는 게 아니다")
    void clipsTheTailAtTheForecastLimit() {
        WeatherPeriod period = WeatherPeriod.of(TODAY, null, 0, 30);

        assertThat(period.to()).isEqualTo(TODAY.plusDays(WeatherPeriod.MAX_FORECAST_DAYS - 1));
        assertThat(period.length()).isEqualTo(WeatherPeriod.MAX_FORECAST_DAYS);
        assertThat(period.beyondForecast(TODAY)).isFalse();
    }

    @Test
    @DisplayName("시작이 상한 너머면 자르지 않고 드러낸다 — 아무 날이나 채워 답하면 거짓말이다")
    void refusesWhenEvenTheStartIsBeyondTheLimit() {
        WeatherPeriod period = WeatherPeriod.of(TODAY, null, WeatherPeriod.MAX_FORECAST_DAYS, 1);

        assertThat(period.beyondForecast(TODAY)).isTrue();
    }

    @Test
    @DisplayName("16일째는 아직 예보 안이다 — 경계가 하루 밀리면 마지막 날을 잃는다")
    void keepsTheLastForecastDayInside() {
        WeatherPeriod last = WeatherPeriod.of(TODAY, null, WeatherPeriod.MAX_FORECAST_DAYS - 1, 1);

        assertThat(last.beyondForecast(TODAY)).isFalse();
    }

    @Test
    @DisplayName("오늘을 걸치면 과거가 아니다 — 두 API의 격자를 이어 붙이지 않기 위해서다")
    void isNotPastWhenTheRangeTouchesToday() {
        assertThat(WeatherPeriod.of(TODAY, null, 0, 3).past(TODAY)).isFalse();
        assertThat(WeatherPeriod.of(TODAY, TODAY.minusDays(1), null, 1).past(TODAY)).isTrue();
    }

    @Test
    @DisplayName("0일치를 물어도 하루는 보여준다 — 빈 답은 답이 아니다")
    void neverProducesAnEmptyRange() {
        assertThat(WeatherPeriod.of(TODAY, null, 0, 0).length()).isEqualTo(1);
        assertThat(WeatherPeriod.of(TODAY, null, 0, -5).length()).isEqualTo(1);
    }

    @Test
    @DisplayName("끝이 시작보다 앞서면 만들지 못한다 — 조용히 뒤집힌 범위가 돌아다니면 안 된다")
    void rejectsAnInvertedRange() {
        assertThatThrownBy(() -> new WeatherPeriod(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
