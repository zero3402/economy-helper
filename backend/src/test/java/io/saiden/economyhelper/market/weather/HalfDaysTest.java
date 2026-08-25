package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.weather.HalfDay.Half;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 하루를 <b>오전 한 줄, 오후 한 줄</b>로 접는 규칙을 못 박는다.
 *
 * <p>두 가지를 함께 지켜야 한다 — 반나절마다 <b>반드시 하나</b>가 나오고, 그 하나가
 * <b>그 반나절을 가장 잘 말하는 것</b>이어야 한다. 젖었으면 가장 센 토막, 말랐으면 그 시간대의 하늘이다.
 *
 * <p>실측(2026-08-20 성남시)이 이 파일의 근거다. 일 단위로는 이슬비 코드에 최대 강수확률 80%인
 * 날이었는데 시간별로는 13~19시에 몰려 있었고 오전은 말라 있었다.
 */
class HalfDaysTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 20);

    @Test
    @DisplayName("실측 하루가 오전·오후 두 줄이 된다 — 오전은 마르고 오후만 몰려 있다")
    void foldsTheMeasuredDayIntoTwoHalves() {
        // 13시 60% → 15시 80% → 19시 45%로 떨어진다. 봉우리(80%)의 80%인 64%가 경계라
        // 14~17시만 남는다 — 가장자리까지 넣으면 「오후 1시~7시」가 되어 우산 챙길 때를 못 말한다
        List<Integer> chances = Arrays.asList(
                0, 0, 0, 0, 0, 1, 2, 3, 5, 10, 20, 34,            // 00~11시
                47, 60, 73, 80, 77, 68, 57, 45, 32, 22, 16, 12);  // 12~23시
        List<BigDecimal> amounts = mm(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0.4, 1.1, 0.4, 0.2, 0, 0.7, 0.1, 0, 0, 0, 0);
        List<Integer> codes = Arrays.asList(
                1, 2, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3,
                3, 51, 55, 51, 51, 3, 53, 51, 1, 2, 2, 2);

        Map<LocalDate, List<HalfDay>> byDay =
                HalfDays.byDay(hours(DAY, 24), chances, amounts, codes);

        assertThat(byDay).containsOnlyKeys(DAY);
        assertThat(byDay.get(DAY)).satisfiesExactly(
                morning -> {
                    assertThat(morning.half()).isEqualTo(Half.MORNING);
                    assertThat(morning.wet()).as("오전 봉우리가 34%라 문턱 아래다").isFalse();
                    assertThat(morning.kind()).as("코드 1이 가장 흔하다")
                            .isEqualTo(SkyCondition.MOSTLY_CLEAR);
                },
                afternoon -> {
                    assertThat(afternoon.half()).isEqualTo(Half.AFTERNOON);
                    assertThat(afternoon.from()).as("13시(60%)는 봉우리의 가장자리다")
                            .isEqualTo(LocalTime.of(14, 0));
                    // 17시는 확률로는 68%라 문턱을 넘지만 코드가 3(흐림)이고 강수량이
                    // 0.0mm다 — 셋 중 둘이 「안 온다」고 말하는 시간이라 꼬리에서 잘린다.
                    // 예전에는 확률 하나로 통과해 「비」의 끝이 비가 안 오는 시간이었다
                    assertThat(afternoon.to())
                            .as("17시는 코드도 양도 마르다 · 18시(57%)·19시(45%)는 확률에서 밀린다")
                            .isEqualTo(LocalTime.of(16, 0));
                    assertThat(afternoon.chance()).isEqualTo(80);
                    assertThat(afternoon.kind()).isEqualTo(SkyCondition.DRIZZLE);
                });
    }

    @Test
    @DisplayName("마른 반나절은 그 시간대 하늘을 있는 그대로 든다 — 지어낸 「맑음」이 아니다")
    void namesADryHalfAfterItsActualSky() {
        // 종일 마른 날. 오전은 맑고 오후는 흐리다 — 그 차이가 화면에 남아야 한다
        List<Integer> chances = Collections.nCopies(24, 10);
        List<Integer> codes = new ArrayList<>(Collections.nCopies(12, 0));
        codes.addAll(Collections.nCopies(12, 3));

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, null, codes).get(DAY);

        assertThat(halves).satisfiesExactly(
                morning -> assertThat(morning.kind()).isEqualTo(SkyCondition.CLEAR),
                afternoon -> assertThat(afternoon.kind()).isEqualTo(SkyCondition.CLOUDY));
        assertThat(halves).allSatisfy(half -> {
            assertThat(half.wet()).isFalse();
            assertThat(half.from()).as("적을 시각이 없다 — 없는 값을 채우지 않는다").isNull();
            // 확률은 들되 화면에는 안 나간다(WeatherFormatter가 wet()으로 막는다).
            // 담는 이유는 하루 요약이다 — 양쪽이 다 마르면 갈 값이 없어 일별 출처의
            // 확률이 남고, 그것이 「소나기 61%인데 오전·오후 둘 다 마름」 화면이었다
            assertThat(half.chance()).as("마른 반나절도 제 봉우리를 든다").isEqualTo(10);
        });
    }

    @Test
    @DisplayName("마른 반나절의 하늘은 가장 흔한 것이다 — 한 시간 낀 이슬비가 반나절 이름이 되면 안 된다")
    void picksTheMostCommonSkyForADryHalf() {
        // 문턱을 못 넘어 비로 치지 않기로 한 값이 이름만 비가 되어서는 안 된다
        List<Integer> chances = Collections.nCopies(6, 20);
        List<Integer> codes = Arrays.asList(0, 0, 0, 0, 51, 0);

        assertThat(HalfDays.byDay(hours(DAY, 6), chances, null, codes).get(DAY))
                .singleElement()
                .satisfies(half -> assertThat(half.kind()).isEqualTo(SkyCondition.CLEAR));
    }

    @Test
    @DisplayName("낮은 확률이 종일 깔린 것은 비가 아니다 — 「오전 9시~밤 11시 비」는 아무것도 안 말한다")
    void ignoresTheSummerAfternoonBaseline() {
        List<Integer> chances = Arrays.asList(
                30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                40, 45, 49, 45, 40, 35, 30, 30, 30, 30, 30, 30);

        List<HalfDay> halves =
                HalfDays.byDay(hours(DAY, 24), chances, mm(new double[24]), null).get(DAY);

        assertThat(halves).as("두 줄은 나오되 둘 다 마른 것이어야 한다").hasSize(2);
        assertThat(halves).allSatisfy(half -> assertThat(half.wet()).isFalse());
    }

    @Test
    @DisplayName("봉우리가 낮은 날은 덜 깎는다 — 좁히는 것은 절대 문턱을 올리는 일이 아니다")
    void narrowsRelativeToThatDaysPeak() {
        // 하루 종일 55% 언저리인 날. 봉우리의 80%는 44%지만 절대 문턱(50%)이 그보다 크므로
        // 그쪽이 이긴다 — 안 그러면 봉우리가 낮은 날에 마른 시간까지 비가 된다
        List<Integer> chances = Arrays.asList(30, 52, 55, 51, 30, 30);

        assertThat(HalfDays.byDay(hours(DAY, 6), chances, null, null).get(DAY))
                .singleElement().satisfies(half -> {
                    assertThat(half.from()).isEqualTo(LocalTime.of(1, 0));
                    assertThat(half.to()).as("51%도 50% 문턱을 넘으므로 남는다")
                            .isEqualTo(LocalTime.of(3, 0));
                    assertThat(half.chance()).isEqualTo(55);
                });
    }

    @Test
    @DisplayName("확률이 낮아도 양이 잡히면 비다 — 짧고 센 소나기가 여기 걸린다")
    void keepsAShortHeavyShower() {
        List<Integer> chances = Arrays.asList(0, 0, 0, 20, 0, 0);
        List<BigDecimal> amounts = mm(0, 0, 0, 4.2, 0, 0);
        List<Integer> codes = Arrays.asList(1, 1, 1, 81, 1, 1);

        assertThat(HalfDays.byDay(hours(DAY, 6), chances, amounts, codes).get(DAY))
                .singleElement().satisfies(half -> {
                    assertThat(half.single()).as("한 시간짜리다").isTrue();
                    assertThat(half.from()).isEqualTo(LocalTime.of(3, 0));
                    assertThat(half.kind()).isEqualTo(SkyCondition.SHOWERS);
                });
    }

    @Test
    @DisplayName("눈과 소나기와 뇌우를 가른다 — 우산만 들고 나가게 하면 안 된다")
    void tellsSnowFromShowersFromThunder() {
        assertThat(kindOf(71)).as("눈").isEqualTo(SkyCondition.SNOW);
        assertThat(kindOf(85)).as("소낙눈").isEqualTo(SkyCondition.SNOW_SHOWERS);
        assertThat(kindOf(80)).as("소나기").isEqualTo(SkyCondition.SHOWERS);
        assertThat(kindOf(95)).as("뇌우").isEqualTo(SkyCondition.THUNDERSTORM);
        assertThat(kindOf(61)).as("비").isEqualTo(SkyCondition.RAIN);
    }

    @Test
    @DisplayName("젖은 반나절의 종류는 가장 무거운 것으로 부른다 — 이슬비로 시작해 뇌우로 끝나면 뇌우다")
    void namesAWetHalfAfterItsHeaviestHour() {
        List<Integer> chances = Arrays.asList(60, 70, 80);
        List<Integer> codes = Arrays.asList(51, 61, 95);

        assertThat(HalfDays.byDay(hours(DAY, 3), chances, null, codes).get(DAY))
                .singleElement()
                .satisfies(half -> assertThat(half.kind()).isEqualTo(SkyCondition.THUNDERSTORM));
    }

    @Test
    @DisplayName("같은 반나절에 두 번 와도 한 줄이다 — 가장 센 것을 든다")
    void foldsTwoStretchesInOneHalfIntoTheStrongest() {
        // 둘을 다 적으면 「오전」이 두 줄이 되고, 사이의 마른 시간까지 한 범위로 이으면
        // 오지 않는 비를 적는 셈이 된다
        List<Integer> chances = Arrays.asList(70, 80, 20, 10, 20, 75, 85);

        assertThat(HalfDays.byDay(hours(DAY, 7), chances, null, null).get(DAY))
                .singleElement().satisfies(half -> {
                    assertThat(half.from()).as("85%짜리 뒷토막이 이긴다").isEqualTo(LocalTime.of(5, 0));
                    assertThat(half.to()).isEqualTo(LocalTime.of(6, 0));
                    assertThat(half.chance()).isEqualTo(85);
                });
    }

    @Test
    @DisplayName("자정을 넘겨 잇지 않는다 — 「23시~1시」는 어느 날 것인지 알 수 없다")
    void neverJoinsAcrossMidnight() {
        List<LocalDateTime> times = List.of(
                DAY.atTime(22, 0), DAY.atTime(23, 0),
                DAY.plusDays(1).atTime(0, 0), DAY.plusDays(1).atTime(1, 0));
        List<Integer> chances = Arrays.asList(20, 80, 85, 20);

        Map<LocalDate, List<HalfDay>> byDay = HalfDays.byDay(times, chances, null, null);

        assertThat(byDay).containsOnlyKeys(DAY, DAY.plusDays(1));
        assertThat(byDay.get(DAY)).singleElement().satisfies(half -> {
            assertThat(half.half()).as("22·23시는 오후뿐이다").isEqualTo(Half.AFTERNOON);
            assertThat(half.from()).isEqualTo(LocalTime.of(23, 0));
        });
        assertThat(byDay.get(DAY.plusDays(1))).singleElement().satisfies(half -> {
            assertThat(half.half()).isEqualTo(Half.MORNING);
            assertThat(half.from()).isEqualTo(LocalTime.MIDNIGHT);
        });
    }

    @Test
    @DisplayName("지나간 날은 확률이 없다 — 실제로 온 양으로 만든다")
    void usesActualAmountsForThePast() {
        List<BigDecimal> amounts = mm(0, 0, 1.2, 2.0, 0.5, 0);
        List<Integer> codes = Arrays.asList(1, 1, 61, 63, 61, 1);

        assertThat(HalfDays.byDay(hours(DAY, 6), null, amounts, codes).get(DAY))
                .singleElement().satisfies(half -> {
                    assertThat(half.chance()).as("지나간 날에 '올 확률'은 말이 안 된다").isNull();
                    assertThat(half.amount()).isEqualByComparingTo(new BigDecimal("3.7"));
                    assertThat(half.from()).isEqualTo(LocalTime.of(2, 0));
                    assertThat(half.to()).isEqualTo(LocalTime.of(4, 0));
                });
    }

    @Test
    @DisplayName("시간별 값이 아예 없으면 빈 결과다 — 보충이 실패해도 답은 나가야 한다")
    void survivesMissingHourlyData() {
        assertThat(HalfDays.byDay(null, null, null, null)).isEmpty();
        assertThat(HalfDays.byDay(List.of(), null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("정오를 넘는 비는 오전과 오후로 쪼개진다 — 아홉 시간 폭은 「비옴」과 다를 게 없다")
    void splitsAtNoon() {
        List<Integer> chances = new ArrayList<>(Collections.nCopies(24, 0));
        for (int hour = 10; hour <= 15; hour++) {
            chances.set(hour, 80);
        }

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(halves).hasSize(2);
        assertThat(halves.get(0).from()).isEqualTo(LocalTime.of(10, 0));
        assertThat(halves.get(0).to())
                .as("오전은 11시에 끝난다 — 12시는 이미 오후다").isEqualTo(LocalTime.of(11, 0));
        assertThat(halves.get(1).from()).isEqualTo(LocalTime.of(12, 0));
        assertThat(halves.get(1).to()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("쪼갠 두 줄이 각각 제 확률을 든다 — 한 숫자를 두 줄에 찍으면 거짓이 된다")
    void eachHalfCarriesItsOwnPeak() {
        List<Integer> chances = new ArrayList<>(Collections.nCopies(24, 0));
        chances.set(10, 80);
        chances.set(11, 80);
        chances.set(12, 90);
        chances.set(13, 90);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(halves).hasSize(2);
        assertThat(halves.get(0).chance()).as("오전은 오전 것을 든다").isEqualTo(80);
        assertThat(halves.get(1).chance()).as("오후는 오후 것을 든다").isEqualTo(90);
    }

    @Test
    @DisplayName("문턱은 반나절이 아니라 그날 봉우리로 잰다 — 반나절마다 재면 필터가 조용히 느슨해진다")
    void thresholdStaysScopedToTheWholeDay() {
        // 그날 봉우리가 오후 90%다. 하루 기준 문턱은 max(50, 72) = 72이므로 오전 60%는 걸러진다.
        // 반나절마다 다시 재면 오전 문턱이 max(50, 48) = 50이 되어 그 60%가 새로 통과한다
        List<Integer> chances = new ArrayList<>(Collections.nCopies(24, 0));
        chances.set(9, 60);
        chances.set(10, 60);
        chances.set(14, 90);
        chances.set(15, 90);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(halves).hasSize(2);
        assertThat(halves.get(0).wet())
                .as("오전 60%는 그날 봉우리(90%)의 80%인 72%에 밀린다").isFalse();
        assertThat(halves.get(1).from()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("어떤 줄도 정오를 넘지 않는다 — WeatherFormatter.range()가 이 불변에 기대고 있다")
    void neverLetsAHalfCrossNoon() {
        // 종일 비가 오는 극단을 먹여 본다 — 쪼개지지 않으면 자정~밤 11시 한 줄이 된다
        List<Integer> chances = Collections.nCopies(24, 90);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, null, null).get(DAY);

        assertThat(halves).as("종일 비인데 줄이 없으면 이 단언이 공허하다").isNotEmpty();
        assertThat(halves).allSatisfy(half -> assertThat(half.from().getHour() / 12)
                .as("%s~%s가 정오를 넘는다", half.from(), half.to())
                .isEqualTo(half.to().getHour() / 12));
    }

    // --- 도우미 ---

    @Test
    @DisplayName("출처가 「맑음」이라 한 시간은 확률 100%여도 비가 아니다 — 젖은 줄에 「맑음」이 찍혔었다")
    void neverCallsAFairWeatherHourRain() {
        // 실측(2026-08-25 미금역). 확률이 봉우리 100%까지 오르는데 그 시간의 코드는
        // 1(대체로 맑음)이고 강수량은 0.0mm다 — 셋 중 둘이 「안 온다」고 말한다.
        // 예전에는 확률만 보고 비로 쳤고, 이름 붙일 강수 코드가 없어 맑음·흐림이 그 자리에
        // 앉았다. 하루 요약이 「뇌우」인 날이면 바로 아래 줄이 「오전 맑음」이 된다
        List<Integer> chances = Arrays.asList(
                0, 0, 0, 4, 16, 32, 49, 67, 86, 98, 100, 95,     // 00~11시
                88, 81, 71, 59, 41, 20, 4, 0, 0, 0, 0, 0);       // 12~23시
        List<BigDecimal> amounts = mm(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0.1, 0, 0, 0, 0, 0, 0, 0.1, 0, 0, 0);
        List<Integer> codes = Arrays.asList(
                0, 2, 3, 3, 3, 3, 3, 3, 3, 3, 1, 1,
                1, 51, 1, 1, 1, 2, 3, 3, 51, 3, 1, 1);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, amounts, codes).get(DAY);

        assertThat(halves).allSatisfy(half -> assertThat(!half.wet() || half.kind().precipitating())
                .as("%s 줄이 젖었는데 이름이 %s다 — 강수가 아닌 낱말은 젖은 줄에 못 앉는다",
                        half.half(), half.kind())
                .isTrue());
        assertThat(halves).satisfiesExactly(
                morning -> assertThat(morning.wet())
                        .as("확률 100%지만 코드도 양도 「안 온다」고 말한다").isFalse(),
                afternoon -> {
                    assertThat(afternoon.from()).as("코드 51에 0.1mm인 13시만 남는다")
                            .isEqualTo(LocalTime.of(13, 0));
                    assertThat(afternoon.kind()).isEqualTo(SkyCondition.DRIZZLE);
                });
    }

    @Test
    @DisplayName("확률 0%는 「안 온다」는 값이다 — 양만 보고 비로 치면 화면이 「최대 0%」라 적는다")
    void neverCallsAZeroPercentHourRain() {
        // 같은 실측의 20시다. 확률 0%인데 0.1mm가 함께 오는 자리가 있다.
        // 그 시간을 비로 치면 우산 그림 옆에 (최대 0%)가 찍힌다 — 제 말을 제가 뒤집는다
        List<Integer> chances = Arrays.asList(0, 0, 0);
        List<BigDecimal> amounts = mm(0, 0.1, 0);
        List<Integer> codes = Arrays.asList(3, 51, 3);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 3), chances, amounts, codes).get(DAY);

        assertThat(halves).singleElement().satisfies(morning -> {
            assertThat(morning.wet()).isFalse();
            assertThat(morning.chance()).as("마른 반나절도 제 봉우리는 든다 — 화면에만 안 나간다")
                    .isZero();
        });
    }

    @Test
    @DisplayName("코드 혼자서는 못 자른다 — 양이 잡힌 시간은 그 양이 이긴다")
    void letsAMeasuredAmountOverrideAFairWeatherCode() {
        // 실측(2026-08-20 성남 17시)에 코드 3(흐림)인데 0.2mm가 함께 온 자리가 있었다.
        // 코드만으로 자르면 이어지던 비의 꼬리가 잘린다
        List<Integer> chances = Arrays.asList(80, 80, 68);
        List<BigDecimal> amounts = mm(1.1, 0.4, 0.2);
        List<Integer> codes = Arrays.asList(55, 51, 3);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 3), chances, amounts, codes).get(DAY);

        assertThat(halves).singleElement().satisfies(morning -> {
            assertThat(morning.to()).as("0.2mm가 온 시간은 흐림 코드에도 남는다")
                    .isEqualTo(LocalTime.of(2, 0));
            assertThat(morning.kind())
                    .as("이름은 강수 코드 중에서만 고른다 — 흐림이 최댓값이 되면 안 된다")
                    .isEqualTo(SkyCondition.DRIZZLE);
        });
    }

    @Test
    @DisplayName("강수량 배열이 없으면 코드가 거부권을 못 쥔다 — null은 0이 아니라 모름이다")
    void neverLetsAMissingAmountVetoRain() {
        // ⚠️ codex 적대적 리뷰가 잡은 반례다. 「코드와 양이 함께 안 온다고 말해야 한다」고
        //    적어 두고는 amount == null까지 「양이 안 온다」로 셌다. 그래서 강수량 배열이
        //    통째로 없거나 짧게 온 응답에서 확률 100%·코드 0(맑음)인 시간이 전부 잘려
        //    「맑음 / 강수확률 100% / ☀️ 오전 맑음 / ☀️ 오후 맑음」이 나왔다
        List<Integer> chances = Collections.nCopies(24, 100);
        List<Integer> codes = Collections.nCopies(24, 0);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, null, codes).get(DAY);

        assertThat(halves).allSatisfy(half -> assertThat(half.wet())
                .as("%s — 확률 100%%인데 양을 모른다고 마른 것이 되면 안 된다", half.half())
                .isTrue());
    }

    @Test
    @DisplayName("양이 짧게 와도 마찬가지다 — 배열 길이가 슬롯보다 짧으면 그 시간은 「모름」이다")
    void treatsAShortAmountArrayAsUnknown() {
        List<Integer> chances = Collections.nCopies(24, 100);
        List<Integer> codes = Collections.nCopies(24, 0);
        // 앞 두 시간만 값이 있다. 나머지 스물두 시간은 at()이 null을 준다
        List<BigDecimal> amounts = mm(0, 0);

        List<HalfDay> halves = HalfDays.byDay(hours(DAY, 24), chances, amounts, codes).get(DAY);

        assertThat(halves).satisfiesExactly(
                morning -> assertThat(morning.wet())
                        .as("00·01시는 0.0mm라 잘려도 02시부터는 모름이라 남는다").isTrue(),
                afternoon -> assertThat(afternoon.wet()).isTrue());
    }

    private static SkyCondition kindOf(int code) {
        return HalfDays.byDay(hours(DAY, 1), List.of(80), null, List.of(code))
                .get(DAY).get(0).kind();
    }

    private static List<LocalDateTime> hours(LocalDate day, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(hour -> day.atTime(hour, 0)).toList();
    }

    private static List<BigDecimal> mm(double... values) {
        return Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }
}
