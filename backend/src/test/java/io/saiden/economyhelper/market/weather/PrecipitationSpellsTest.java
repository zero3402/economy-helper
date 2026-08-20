package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「비옴」 한 마디가 <b>언제</b>를 안 말해 주는 문제를 이 클래스가 푼다 — 그 경계를 못 박는다.
 *
 * <p>실측(2026-08-20 성남시)이 이 파일의 근거다. 일 단위로는 이슬비 코드에 최대 강수확률 80%인
 * 날이었는데 시간별로는 13~19시에 몰려 있었고 오전은 말라 있었다.
 */
class PrecipitationSpellsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 20);

    @Test
    @DisplayName("몰려 있는 시간을 한 토막으로 접는다 — 24시간을 나열하면 숫자 벽이 된다")
    void foldsTheWetHoursIntoOneSpell() {
        // 실측 그대로다. 13시 60% → 15시 80% → 19시 45%로 떨어진다
        List<Integer> chances = Arrays.asList(
                0, 0, 0, 0, 0, 1, 2, 3, 5, 10, 20, 34,      // 00~11시
                47, 60, 73, 80, 77, 68, 57, 45, 32, 22, 16, 12);  // 12~23시
        List<BigDecimal> amounts = mm(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0.4, 1.1, 0.4, 0.2, 0, 0.7, 0.1, 0, 0, 0, 0);
        List<Integer> codes = Arrays.asList(
                1, 2, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3,
                3, 51, 55, 51, 51, 3, 53, 51, 1, 2, 2, 2);

        Map<LocalDate, List<PrecipitationSpell>> byDay =
                PrecipitationSpells.byDay(hours(DAY, 24), chances, amounts, codes);

        assertThat(byDay).containsOnlyKeys(DAY);
        assertThat(byDay.get(DAY)).singleElement().satisfies(spell -> {
            assertThat(spell.from()).as("13시부터").isEqualTo(LocalTime.of(13, 0));
            assertThat(spell.to()).as("19시까지 — 45%인 19시는 강수량 0.1mm로 걸린다")
                    .isEqualTo(LocalTime.of(19, 0));
            assertThat(spell.chance()).as("그 토막의 최대 확률").isEqualTo(80);
            assertThat(spell.kind()).isEqualTo(SkyCondition.DRIZZLE);
        });
    }

    @Test
    @DisplayName("마른 날은 빈 목록이다 — 매일 「강수 없음」을 찍으면 소잡이 된다")
    void givesNothingForADryDay() {
        List<Integer> chances = Arrays.asList(
                0, 0, 0, 0, 0, 2, 5, 8, 10, 12, 15, 18,
                20, 22, 25, 28, 30, 25, 20, 15, 10, 5, 2, 0);

        assertThat(PrecipitationSpells.byDay(hours(DAY, 24), chances, mm(new double[24]), null)).isEmpty();
    }

    @Test
    @DisplayName("낮은 확률이 종일 깔린 것은 토막이 아니다 — 「오전 9시~밤 11시 비」는 아무것도 안 말한다")
    void ignoresTheSummerAfternoonBaseline() {
        List<Integer> chances = Arrays.asList(
                30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                40, 45, 49, 45, 40, 35, 30, 30, 30, 30, 30, 30);

        assertThat(PrecipitationSpells.byDay(hours(DAY, 24), chances, mm(new double[24]), null)).isEmpty();
    }

    @Test
    @DisplayName("확률이 낮아도 양이 잡히면 토막이다 — 짧고 센 소나기가 여기 걸린다")
    void keepsAShortHeavyShower() {
        List<Integer> chances = Arrays.asList(0, 0, 0, 20, 0, 0);
        List<BigDecimal> amounts = mm(0, 0, 0, 4.2, 0, 0);
        List<Integer> codes = Arrays.asList(1, 1, 1, 81, 1, 1);

        List<PrecipitationSpell> spells = PrecipitationSpells.byDay(hours(DAY, 6), chances, amounts, codes).get(DAY);

        assertThat(spells).singleElement().satisfies(spell -> {
            assertThat(spell.single()).as("한 시간짜리다").isTrue();
            assertThat(spell.from()).isEqualTo(LocalTime.of(3, 0));
            assertThat(spell.kind()).isEqualTo(SkyCondition.SHOWERS);
        });
    }

    @Test
    @DisplayName("눈과 소나기와 뇌우를 가른다 — 물으신 것이 바로 이 구분이다")
    void tellsSnowFromShowersFromThunder() {
        assertThat(kindOf(71)).as("눈").isEqualTo(SkyCondition.SNOW);
        assertThat(kindOf(85)).as("소낙눈").isEqualTo(SkyCondition.SNOW_SHOWERS);
        assertThat(kindOf(80)).as("소나기").isEqualTo(SkyCondition.SHOWERS);
        assertThat(kindOf(95)).as("뇌우").isEqualTo(SkyCondition.THUNDERSTORM);
        assertThat(kindOf(61)).as("비").isEqualTo(SkyCondition.RAIN);
    }

    @Test
    @DisplayName("한 토막의 종류는 가장 무거운 것으로 부른다 — 이슬비로 시작해 뇌우로 끝나면 뇌우다")
    void namesTheSpellAfterItsHeaviestHour() {
        List<Integer> chances = Arrays.asList(60, 70, 80);
        List<Integer> codes = Arrays.asList(51, 61, 95);

        List<PrecipitationSpell> spells = PrecipitationSpells.byDay(hours(DAY, 3), chances, null, codes).get(DAY);

        assertThat(spells).singleElement().satisfies(spell -> assertThat(spell.kind())
                .as("우산만 들고 나가게 하면 안 된다").isEqualTo(SkyCondition.THUNDERSTORM));
    }

    @Test
    @DisplayName("하루에 두 번 오면 토막도 둘이다 — 아침 비와 저녁 비를 하나로 잇지 않는다")
    void keepsTwoSpellsApart() {
        List<Integer> chances = Arrays.asList(70, 80, 20, 10, 20, 75, 85);

        List<PrecipitationSpell> spells = PrecipitationSpells.byDay(hours(DAY, 7), chances, null, null).get(DAY);

        assertThat(spells).hasSize(2);
        assertThat(spells.get(0).to()).isEqualTo(LocalTime.of(1, 0));
        assertThat(spells.get(1).from()).isEqualTo(LocalTime.of(5, 0));
    }

    @Test
    @DisplayName("자정을 넘겨 잇지 않는다 — 「23시~1시」는 어느 날 것인지 알 수 없다")
    void neverJoinsAcrossMidnight() {
        // 20일 23시와 21일 00시가 모두 젖어 있다
        List<LocalDateTime> times = List.of(
                DAY.atTime(22, 0), DAY.atTime(23, 0),
                DAY.plusDays(1).atTime(0, 0), DAY.plusDays(1).atTime(1, 0));
        List<Integer> chances = Arrays.asList(20, 80, 85, 20);

        Map<LocalDate, List<PrecipitationSpell>> byDay = PrecipitationSpells.byDay(times, chances, null, null);

        assertThat(byDay).containsOnlyKeys(DAY, DAY.plusDays(1));
        assertThat(byDay.get(DAY)).singleElement()
                .satisfies(spell -> assertThat(spell.from()).isEqualTo(LocalTime.of(23, 0)));
        assertThat(byDay.get(DAY.plusDays(1))).singleElement()
                .satisfies(spell -> assertThat(spell.from()).isEqualTo(LocalTime.MIDNIGHT));
    }

    @Test
    @DisplayName("지나간 날은 확률이 없다 — 실제로 온 양으로 토막을 만든다")
    void usesActualAmountsForThePast() {
        List<BigDecimal> amounts = mm(0, 0, 1.2, 2.0, 0.5, 0);
        List<Integer> codes = Arrays.asList(1, 1, 61, 63, 61, 1);

        List<PrecipitationSpell> spells =
                PrecipitationSpells.byDay(hours(DAY, 6), null, amounts, codes).get(DAY);

        assertThat(spells).singleElement().satisfies(spell -> {
            assertThat(spell.chance()).as("지나간 날에 '올 확률'은 말이 안 된다").isNull();
            assertThat(spell.amount()).isEqualByComparingTo(new BigDecimal("3.7"));
            assertThat(spell.from()).isEqualTo(LocalTime.of(2, 0));
            assertThat(spell.to()).isEqualTo(LocalTime.of(4, 0));
        });
    }

    @Test
    @DisplayName("시간별 값이 아예 없으면 빈 결과다 — 보충이 실패해도 답은 나가야 한다")
    void survivesMissingHourlyData() {
        assertThat(PrecipitationSpells.byDay(null, null, null, null)).isEmpty();
        assertThat(PrecipitationSpells.byDay(List.of(), null, null, null)).isEmpty();
    }

    // --- 도우미 ---

    private static SkyCondition kindOf(int code) {
        List<PrecipitationSpell> spells = PrecipitationSpells.byDay(hours(DAY, 1), List.of(80), null, List.of(code))
                .get(DAY);
        return spells.get(0).kind();
    }

    private static List<LocalDateTime> hours(LocalDate day, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(hour -> day.atTime(hour, 0)).toList();
    }

    private static List<BigDecimal> mm(double... values) {
        return Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }
}
