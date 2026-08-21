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
    @DisplayName("몰린 시간만 남긴다 — 가장자리까지 넣으면 「오후 1시~7시」가 되어 우산 챙길 때를 못 말한다")
    void keepsOnlyTheHoursTheRainIsConcentratedIn() {
        // 실측 그대로다. 13시 60% → 15시 80% → 19시 45%로 떨어진다.
        // 예전에는 절대 문턱(50%) 하나였고 그래서 13~19시가 통째로 한 토막이었다 —
        // 여섯 시간 폭은 「비옴」 한 마디에서 별로 나아가지 못한다. 이제는 봉우리(80%)의
        // 80%인 64%가 경계라 14~17시만 남는다
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
            assertThat(spell.from()).as("13시(60%)는 봉우리의 가장자리다").isEqualTo(LocalTime.of(14, 0));
            assertThat(spell.to()).as("18시(57%)·19시(45%)도 마찬가지다").isEqualTo(LocalTime.of(17, 0));
            assertThat(spell.chance()).as("그 토막의 최대 확률").isEqualTo(80);
            assertThat(spell.kind()).isEqualTo(SkyCondition.DRIZZLE);
        });
    }

    @Test
    @DisplayName("봉우리가 낮은 날은 덜 깎는다 — 좁히는 것은 절대 문턱을 올리는 일이 아니다")
    void narrowsRelativeToThatDaysPeak() {
        // 하루 종일 55% 언저리인 날. 봉우리의 80%는 44%지만 절대 문턱(50%)이 그보다 크므로
        // 그쪽이 이긴다 — 안 그러면 봉우리가 낮은 날에 마른 시간까지 토막이 된다
        List<Integer> chances = Arrays.asList(30, 52, 55, 51, 30, 30);

        List<PrecipitationSpell> spells =
                PrecipitationSpells.byDay(hours(DAY, 6), chances, null, null).get(DAY);

        assertThat(spells).singleElement().satisfies(spell -> {
            assertThat(spell.from()).isEqualTo(LocalTime.of(1, 0));
            assertThat(spell.to()).as("51%도 50% 문턱을 넘으므로 남는다").isEqualTo(LocalTime.of(3, 0));
            assertThat(spell.chance()).isEqualTo(55);
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

    @Test
    @DisplayName("정오를 넘는 비는 오전과 오후로 쪼개진다 — 아홉 시간 폭은 「비옴」과 다를 게 없다")
    void splitsAtNoon() {
        // 오전 10시부터 오후 3시까지 이어지는 비. 한 토막이면 「오전 10시~오후 3시」가 되는데
        // 그 여섯 시간 폭으로는 우산을 언제 챙길지 알 수 없다
        List<Integer> chances = new java.util.ArrayList<>(java.util.Collections.nCopies(24, 0));
        for (int hour = 10; hour <= 15; hour++) {
            chances.set(hour, 80);
        }

        List<PrecipitationSpell> spells =
                PrecipitationSpells.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(spells).hasSize(2);
        assertThat(spells.get(0).from()).isEqualTo(LocalTime.of(10, 0));
        assertThat(spells.get(0).to())
                .as("오전 토막은 11시에 끝난다 — 12시는 이미 오후다")
                .isEqualTo(LocalTime.of(11, 0));
        assertThat(spells.get(1).from()).isEqualTo(LocalTime.of(12, 0));
        assertThat(spells.get(1).to()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("쪼갠 두 토막이 각각 제 확률을 든다 — 한 숫자를 두 줄에 찍으면 거짓이 된다")
    void eachHalfCarriesItsOwnPeak() {
        // 오전은 80%, 오후는 90%. 화면에서 한 토막을 두 줄로 그리면 둘 다 90%가 되는데
        // 그건 오전에 대해 거짓이다. 쪼개는 일이 포매터가 아니라 접는 층에 있는 이유다.
        // ⚠️ 두 값이 **둘 다 그날 문턱을 넘어야** 이 테스트가 뜻을 갖는다 — 봉우리 90%의
        //    80%는 72%이므로 80·90이 함께 통과한다. 오전을 55%로 두면 문턱에 밀려
        //    토막이 하나만 나오고, 그건 thresholdStaysScopedToTheWholeDay가 보는 것이다
        List<Integer> chances = new java.util.ArrayList<>(java.util.Collections.nCopies(24, 0));
        chances.set(10, 80);
        chances.set(11, 80);
        chances.set(12, 90);
        chances.set(13, 90);

        List<PrecipitationSpell> spells =
                PrecipitationSpells.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(spells).hasSize(2);
        assertThat(spells.get(0).chance()).as("오전은 오전 것을 든다").isEqualTo(80);
        assertThat(spells.get(1).chance()).as("오후는 오후 것을 든다").isEqualTo(90);
    }

    @Test
    @DisplayName("문턱은 반나절이 아니라 그날 봉우리로 잰다 — 반나절마다 재면 필터가 조용히 느슨해진다")
    void thresholdStaysScopedToTheWholeDay() {
        // 그날 봉우리가 오후 90%다. 하루 기준 문턱은 max(50, 72) = 72이므로 오전 60%는 걸러진다.
        // 반나절마다 다시 재면 오전 문턱이 max(50, 48) = 50이 되어 그 60%가 새로 통과한다 —
        // 같은 날 같은 비를 반나절에 따라 다르게 판정하는 것이라 틀린다
        List<Integer> chances = new java.util.ArrayList<>(java.util.Collections.nCopies(24, 0));
        chances.set(9, 60);
        chances.set(10, 60);
        chances.set(14, 90);
        chances.set(15, 90);

        List<PrecipitationSpell> spells =
                PrecipitationSpells.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(spells)
                .as("오전 60%는 그날 봉우리(90%)의 80%인 72%에 밀려 토막이 되지 않는다")
                .singleElement()
                .satisfies(spell -> assertThat(spell.from()).isEqualTo(LocalTime.of(14, 0)));
    }

    @Test
    @DisplayName("어떤 토막도 정오를 넘지 않는다 — WeatherFormatter.range()가 이 불변에 기대고 있다")
    void neverLetsASpellCrossNoon() {
        // range()에서 「정오를 넘는 토막」 분기를 지웠으므로 그 불변이 여기서 지켜져야 한다.
        // 종일 비가 오는 극단을 먹여 본다 — 쪼개지지 않으면 자정~밤 11시 한 토막이 된다
        List<Integer> chances = java.util.Collections.nCopies(24, 90);

        List<PrecipitationSpell> spells =
                PrecipitationSpells.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(spells).as("종일 비인데 토막이 없으면 이 단언이 공허하다").isNotEmpty();
        assertThat(spells).allSatisfy(spell -> assertThat(spell.from().getHour() / 12)
                .as("%s~%s가 정오를 넘는다", spell.from(), spell.to())
                .isEqualTo(spell.to().getHour() / 12));
    }

    private static List<LocalDateTime> hours(LocalDate day, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(hour -> day.atTime(hour, 0)).toList();
    }

    private static List<BigDecimal> mm(double... values) {
        return Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }
}
