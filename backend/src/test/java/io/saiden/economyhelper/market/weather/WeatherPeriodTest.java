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
        assertThat(days(period)).isEqualTo(1);
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
        assertThat(days(period)).isEqualTo(7);
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
        assertThat(days(period)).isEqualTo(WeatherPeriod.MAX_FORECAST_DAYS);
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
        assertThat(days(WeatherPeriod.of(TODAY, null, 0, 0))).isEqualTo(1);
        assertThat(days(WeatherPeriod.of(TODAY, null, 0, -5))).isEqualTo(1);
    }

    // --- 연도 없이 적은 날 ----------------------------------------------------

    @Test
    @DisplayName("'8월 16일'은 어제다 — LLM에 연도를 맡겼더니 2024년을 지어냈다")
    void resolvesAMonthAndDayToTheNearestYear() {
        // 오늘이 2026-08-17이므로 8월 16일은 하루 전이지, 364일 뒤(2027)도 2024년도 아니다
        assertThat(WeatherPeriod.nearestOccurrence(TODAY, 8, 16))
                .isEqualTo(LocalDate.of(2026, 8, 16));
    }

    @Test
    @DisplayName("해를 넘겨야 더 가까우면 넘어간다 — 12월 31일을 1월에 물으면 지난 연말이다")
    void crossesTheYearBoundaryWhenThatIsCloser() {
        LocalDate newYear = LocalDate.of(2026, 1, 2);

        assertThat(WeatherPeriod.nearestOccurrence(newYear, 12, 31))
                .as("이틀 전인 2025-12-31이지 363일 뒤인 2026-12-31이 아니다")
                .isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("먼 미래의 그 날은 올해로 본다 — 작년 값을 슬쩍 내미는 것보다 '못 본다'가 옳다")
    void prefersThisYearForADistantDateRatherThanLastYear() {
        LocalDate christmas = WeatherPeriod.nearestOccurrence(TODAY, 12, 25);

        assertThat(christmas).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(WeatherPeriod.of(TODAY, christmas, null, 1).beyondForecast(TODAY))
                .as("예보 범위 밖이라 '16일까지만'이라고 답하게 된다")
                .isTrue();
    }

    @Test
    @DisplayName("그 해에 없는 날(2월 29일)이면 있는 해를 고른다 — 던지면 검색 전체가 죽는다")
    void skipsAYearThatDoesNotHaveTheDay() {
        // 후보는 작년·올해·내년뿐이다. 2026 기준이면 2025·2026·2027 모두 평년이라 없다
        assertThat(WeatherPeriod.nearestOccurrence(TODAY, 2, 29)).isNull();

        // 2025년 기준이면 후보에 윤년(2024)이 들어온다
        assertThat(WeatherPeriod.nearestOccurrence(LocalDate.of(2025, 3, 1), 2, 29))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    @DisplayName("일자를 못 읽었으면 아무것도 고르지 않는다 — 그때만 offsetDays로 간다")
    void returnsNullWhenTheDayIsMissing() {
        assertThat(WeatherPeriod.nearestOccurrence(TODAY, 8, null)).isNull();
        assertThat(WeatherPeriod.nearestOccurrence(TODAY, null, null)).isNull();
    }

    // --- 일자만 적은 날 (월도 코드가 채운다) -----------------------------------

    @Test
    @DisplayName("'16일'은 이번 달 16일이다 — 예전에는 조용히 오늘 날씨가 나왔다")
    void resolvesADayAloneWithinTheNearestMonth() {
        // 오늘이 2026-08-17이므로 16일은 어제다. 월을 안 적었어도 채워 준다
        assertThat(WeatherPeriod.nearestOccurrence(TODAY, null, 16))
                .isEqualTo(LocalDate.of(2026, 8, 16));
    }

    @Test
    @DisplayName("앞으로 올 날도 이번 달에서 찾는다")
    void resolvesAnUpcomingDayInTheSameMonth() {
        assertThat(WeatherPeriod.nearestOccurrence(TODAY, null, 20))
                .isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    @DisplayName("월을 넘겨야 더 가까우면 넘어간다 — 연 경계도 함께 넘는다")
    void crossesTheMonthAndYearBoundaryWhenThatIsCloser() {
        LocalDate newYear = LocalDate.of(2026, 1, 2);

        assertThat(WeatherPeriod.nearestOccurrence(newYear, null, 31))
                .as("이틀 전인 2025-12-31이지 29일 뒤인 2026-01-31이 아니다")
                .isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("그 달에 없는 날이면 있는 달을 고른다 — 4월엔 31일이 없다")
    void skipsAMonthThatDoesNotHaveTheDay() {
        LocalDate midApril = LocalDate.of(2026, 4, 15);

        assertThat(WeatherPeriod.nearestOccurrence(midApril, null, 31))
                .as("3월 31일(15일 전)이 5월 31일(46일 뒤)보다 가깝다")
                .isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("끝이 시작보다 앞서면 만들지 못한다 — 조용히 뒤집힌 범위가 돌아다니면 안 된다")
    void rejectsAnInvertedRange() {
        assertThatThrownBy(() -> new WeatherPeriod(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 며칠치인가. 하루면 1이다 — 이 계산이 필요한 곳은 여기뿐이다. */
    private static long days(WeatherPeriod period) {
        return java.time.temporal.ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
    }
}
