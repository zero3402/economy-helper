package io.saiden.economyhelper.market.weather;

import io.saiden.economyhelper.market.weather.HalfDay.Half;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시간별 값을 <b>반나절 둘</b>로 접는다 — I/O를 모르는 순수 클래스다. 스프링 없이 단위 테스트한다.
 *
 * <p><b>왜 접는가.</b> 24시간을 그대로 나열하면 텔레그램 한 통이 숫자 벽이 된다. 사람이 하루를
 * 계획하는 단위는 반나절이므로 오전 한 줄, 오후 한 줄로 줄인다.
 *
 * <p><b>반나절마다 반드시 하나가 나온다.</b> 젖었으면 가장 센 토막을, 말랐으면 그 시간대의
 * 하늘을 든다. 젖은 구간만 내보내면 「오전」이 두 줄 나오거나 아예 없는 날이 생긴다.
 *
 * <p><b>기준은 확률과 강수량을 함께 본다.</b> 하나만 보면 둘 다 틀린다 — 확률만 보면 여름 오후의
 * 낮은 확률이 종일 토막을 만들고, 강수량만 보면 예보가 {@code 0.0mm}로 주는 시간이 통째로 빠진다.
 */
public final class HalfDays {

    /**
     * 이 확률 미만은 비로 치지 않는다.
     *
     * <p>여름 오후는 확률이 종일 10~30%로 깔린다. 그것까지 비라고 적으면 하루가 통째로 젖은 것이
     * 되어 <b>아무것도 말해 주지 않는다.</b> 반대로 너무 높이 잡으면 진짜 소나기가 빠진다.
     */
    private static final int MIN_CHANCE = 50;

    /** 이 양 이상이면 확률이 낮아도 비다 — 짧고 센 소나기가 여기 걸린다. */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.1");

    /**
     * <b>봉우리의 몇 할까지가 그 비인가.</b>
     *
     * <p>문턱을 {@link #MIN_CHANCE} 하나로 두면 토막이 너무 넓어진다. 그래서 그날 봉우리에
     * 상대적으로 자른다 — 0.8이면 80% 봉우리에서 64%가 경계다. 더 조이면 봉우리 한두 시간만 남아
     * 앞뒤로 젖은 시간을 마른 것처럼 말하게 되고, 더 풀면 종일 젖은 것이 된다.
     *
     * <p>⚠️ <b>절대 문턱을 없애는 것이 아니다.</b> 봉우리가 20%인 날에 그 80%인 16%를 경계로
     * 삼으면 마른 날에도 비가 생긴다 — 둘 중 <b>큰 쪽</b>을 쓴다.
     */
    private static final BigDecimal PEAK_RATIO = new BigDecimal("0.8");

    private HalfDays() {
    }

    /**
     * 시간별 값을 날짜별 반나절 목록으로.
     *
     * <p>⚠️ <b>날짜와 반나절을 시각 문자열 그대로 가른다.</b> Open-Meteo를 {@code timezone=auto}로
     * 부르므로 이미 그 지역 현지시다 — 여기서 시간대를 다시 계산하면 남의 하루를 우리 달력으로
     * 자르게 된다.
     *
     * @param times   정시 목록. 병렬 배열의 축이다
     * @param chances 확률(%). 지나간 날은 {@code null}
     * @param amounts 강수량(mm). 없을 수 있다
     * @param skies   하늘. 비의 종류도, 마른 반나절의 하늘도 여기서 읽는다.
     *                <b>출처의 코드가 아니라 우리 어휘로 받는다</b> — 이 클래스는 WMO도
     *                기상청 코드도 몰라야 한다. 옮기는 것은 각 출처의 몫이다
     * @return 날짜별 목록. 각 날은 <b>오전·오후 순</b>이고, 시간별 값이 있는 반나절만 담긴다
     */
    public static Map<LocalDate, List<HalfDay>> byDay(List<LocalDateTime> times,
                                                      List<Integer> chances,
                                                      List<BigDecimal> amounts,
                                                      List<SkyCondition> skies) {
        Map<LocalDate, List<HalfDay>> byDay = new TreeMap<>();
        if (times == null || times.isEmpty()) {
            return byDay;
        }
        Map<LocalDate, List<Integer>> slotsByDay = new TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime at = times.get(i);
            if (at != null) {
                slotsByDay.computeIfAbsent(at.toLocalDate(), day -> new ArrayList<>()).add(i);
            }
        }
        slotsByDay.forEach((day, slots) -> {
            List<HalfDay> halves = fold(times, chances, amounts, skies, slots,
                    thresholdsOf(chances, amounts, slots));
            if (!halves.isEmpty()) {
                byDay.put(day, halves);
            }
        });
        return byDay;
    }

    /**
     * 하루치 슬롯을 반나절 둘로.
     *
     * <p>⚠️ <b>문턱은 그날 것을 반나절 둘이 함께 쓴다.</b> 반나절마다 봉우리를 다시 재면
     * 오전 문턱이 내려가, 하루 기준으로는 걸러지던 시간대가 오전에만 새로 통과한다 —
     * 같은 날 같은 비를 반나절에 따라 다르게 판정하게 된다.
     */
    private static List<HalfDay> fold(List<LocalDateTime> times, List<Integer> chances,
                                      List<BigDecimal> amounts, List<SkyCondition> skies,
                                      List<Integer> slots, Thresholds cut) {
        Map<Half, List<Integer>> byHalf = new EnumMap<>(Half.class);
        for (int slot : slots) {
            LocalDateTime at = at(times, slot);
            if (at != null) {
                byHalf.computeIfAbsent(Half.of(at.toLocalTime()), half -> new ArrayList<>())
                        .add(slot);
            }
        }
        List<HalfDay> halves = new ArrayList<>(2);
        for (Half half : Half.values()) {
            List<Integer> inHalf = byHalf.get(half);
            if (inHalf != null && !inHalf.isEmpty()) {
                halves.add(summarize(half, times, chances, amounts, skies, inHalf, cut));
            }
        }
        return List.copyOf(halves);
    }

    /**
     * 반나절 하나를 한 줄로 — <b>젖었으면 가장 센 토막, 말랐으면 하늘.</b>
     *
     * <p>토막이 여럿이어도 하나만 남긴다. 둘을 다 적으면 「오전」이 되풀이되고, 사이의 마른
     * 시간까지 한 범위로 이으면 오지 않는 비를 적는 셈이 된다. 그래서 <b>가장 센 것</b>을 고른다 —
     * 우산을 챙길지는 그 하나가 정한다.
     */
    private static HalfDay summarize(Half half, List<LocalDateTime> times, List<Integer> chances,
                                     List<BigDecimal> amounts, List<SkyCondition> skies,
                                     List<Integer> slots, Thresholds cut) {
        Stretch strongest = null;
        Stretch current = null;
        for (int slot : slots) {
            if (wet(at(chances, slot), at(amounts, slot), at(skies, slot), cut)) {
                current = current == null ? new Stretch(slot) : current;
                current.extend(slot, at(chances, slot), at(amounts, slot), at(skies, slot));
            } else if (current != null) {
                strongest = stronger(strongest, current);
                current = null;
            }
        }
        strongest = stronger(strongest, current);

        if (strongest == null) {
            // 마른 반나절도 제 봉우리 확률을 든다 — 화면에는 안 나가고 하루 요약이 쓴다.
            // 안 담으면 양쪽이 다 마른 날에 일별 출처의 확률이 그대로 남아, 한 블록에
            // 「소나기 61%(AccuWeather)」와 「오전·오후 마름(Open-Meteo)」이 함께 선다
            return HalfDay.dry(half, skyOf(skies, slots), peakChanceOf(chances, slots));
        }
        LocalTime from = times.get(strongest.first).toLocalTime();
        LocalTime to = times.get(strongest.last).toLocalTime();
        SkyCondition kind =
                strongest.heaviest == null ? SkyCondition.UNKNOWN : strongest.heaviest;
        return strongest.maxChance != null
                ? HalfDay.withChance(from, to, kind, strongest.maxChance)
                : HalfDay.withAmount(from, to, kind, strongest.totalAmount);
    }

    /**
     * 둘 중 <b>센 쪽</b> — 확률이 높은 것, 확률이 없으면 양이 많은 것.
     *
     * <p>확률과 양을 섞어 견주지 않는다. 한 응답 안에서는 둘 중 하나만 오므로 섞일 일이 없고,
     * 굳이 섞으면 단위가 다른 값을 한 자로 재는 셈이 된다.
     */
    private static Stretch stronger(Stretch left, Stretch right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        if (left.maxChance != null && right.maxChance != null) {
            return right.maxChance > left.maxChance ? right : left;
        }
        BigDecimal leftAmount = left.totalAmount == null ? BigDecimal.ZERO : left.totalAmount;
        BigDecimal rightAmount = right.totalAmount == null ? BigDecimal.ZERO : right.totalAmount;
        return rightAmount.compareTo(leftAmount) > 0 ? right : left;
    }

    /**
     * 그 반나절의 최대 확률 — <b>문턱과 상관없이</b> 시간별이 말한 봉우리다.
     *
     * <p>{@link #thresholdsOf}가 재는 것과 값은 같지만 쓰임이 다르다. 저쪽은 「무엇을 비로 칠까」를
     * 정하고, 이쪽은 마른 반나절이 <b>하루 요약에 넘겨 줄 숫자</b>를 든다.
     */
    private static Integer peakChanceOf(List<Integer> chances, List<Integer> slots) {
        Integer peak = null;
        for (int slot : slots) {
            Integer chance = at(chances, slot);
            if (chance != null && (peak == null || chance > peak)) {
                peak = chance;
            }
        }
        return peak;
    }

    /**
     * 마른 반나절의 하늘 — <b>가장 흔한 것</b>으로 부른다.
     *
     * <p>가장 무거운 것으로 부르지 않는다. 대체로 맑은 오전에 이슬비 코드가 한 시간 끼면
     * 그 반나절이 「이슬비」가 되는데, 문턱을 못 넘어 비로 치지 않기로 한 값이 이름만 비가 되는
     * 셈이다. 동률이면 무거운 쪽을 든다 — 덜 알리는 것보다 낫다.
     */
    private static SkyCondition skyOf(List<SkyCondition> skies, List<Integer> slots) {
        List<SkyCondition> inHalf = new ArrayList<>(slots.size());
        for (int slot : slots) {
            inHalf.add(skyAt(skies, slot));
        }
        return commonestSky(inHalf);
    }

    /**
     * 여럿 중 <b>가장 흔한 하늘</b>. 동률이면 무거운 쪽이다.
     *
     * <p>{@link #skyOf}가 반나절에 쓰고 기상청 단기예보가 <b>하루 요약</b>에 쓴다. 규칙이
     * 하나인데 구현이 둘이면 갈라진다 — 실제로 그렇게 두 곳에 있었고, 씨앗({@code UNKNOWN})과
     * 동률 처리까지 똑같이 적혀 있었다.
     *
     * <p>인자가 이미 우리 어휘이므로 「{@code HalfDays}는 어느 출처의 코드도 모른다」를
     * 어기지 않는다.
     *
     * @return 비었으면 {@link SkyCondition#UNKNOWN}
     */
    public static SkyCondition commonestSky(List<SkyCondition> skies) {
        Map<SkyCondition, Integer> counts = new EnumMap<>(SkyCondition.class);
        SkyCondition best = SkyCondition.UNKNOWN;
        int bestCount = 0;
        if (skies == null) {
            return best;
        }
        for (SkyCondition sky : skies) {
            // ⚠️ 모르는 값은 세지 않는다. UNKNOWN이 열거의 맨 뒤라 동률 규칙(「무거운 쪽」)이 모르는
            //    값을 고르던 자리다 — 아는 것이 하나도 없을 때만 UNKNOWN이 남는다
            if (sky == null || !sky.known()) {
                continue;
            }
            int count = counts.merge(sky, 1, Integer::sum);
            if (count > bestCount || (count == bestCount && sky.compareTo(best) > 0)) {
                best = sky;
                bestCount = count;
            }
        }
        return best;
    }

    /** 이어진 젖은 시간 한 덩어리. 반나절 안에서만 이어진다. */
    private static final class Stretch {

        private final int first;
        private int last;
        private Integer maxChance;
        private BigDecimal totalAmount;
        /**
          * ⚠️ 아직 아무것도 못 고른 상태를 {@code null}로 둔다 —
          * {@link SkyCondition#UNKNOWN}으로 초기화하면 그것이 enum의 <b>마지막</b>이라
          * {@code compareTo}가 무엇에도 안 밀려 종류가 영영 안 잡힌다.
          */
        private SkyCondition heaviest;

        private Stretch(int first) {
            this.first = first;
            this.last = first;
        }

        /**
         * 종류는 그 덩어리에서 <b>가장 무거운 것</b>으로 부른다 — 이슬비로 시작해 뇌우로 끝나는
         * 구간을 「이슬비」라 적으면 사용자가 우산만 들고 나간다.
         *
         * <p>⚠️ <b>강수 코드 중에서만 고른다.</b> 덩어리 안에 맑음·흐림 시간이 섞일 수 있는데
         * ({@link #wet}이 거부하는 것은 그 시간 하나이고, 앞뒤가 젖어 있으면 덩어리는 이어진다)
         * 그 코드가 최댓값이 되면 <b>비 오는 줄의 이름이 「흐림」·「맑음」</b>이 된다.
         * <b>젖은 줄의 이름은 강수여야 한다</b> — 하나도 못 고르면
         * {@link SkyCondition#UNKNOWN}으로 남아 화면이 낱말 없이 시각만 적는다.
         *
         * <p><b>무거움은 {@code SkyCondition}의 선언 순서로 잰다</b> — 출처 코드의 크기가
         * 아니다. WMO 정수로 견주던 때는 {@code 66}(진눈깨비)이 {@code 65}(비)보다 무겁다고
         * 셌는데, 그 순서는 WMO가 표를 매긴 사정일 뿐이고 우리 화면의 경고 세기가 아니다.
         * {@code Weather.Daily.skyAgreeingWith}가 이미 {@code compareTo}로 재고 있었다.
         */
        private void extend(int slot, Integer chance, BigDecimal amount, SkyCondition sky) {
            last = slot;
            if (chance != null && (maxChance == null || chance > maxChance)) {
                maxChance = chance;
            }
            if (amount != null) {
                totalAmount = totalAmount == null ? amount : totalAmount.add(amount);
            }
            if (sky != null && sky.precipitating()
                    && (heaviest == null || sky.compareTo(heaviest) > 0)) {
                heaviest = sky;
            }
        }
    }

    /**
     * 그날의 문턱.
     *
     * @param chance 이 확률 미만은 비가 아니다
     * @param amount 확률이 낮아도 이 양 이상이면 비다
     */
    private record Thresholds(int chance, BigDecimal amount) {
    }

    /**
     * 그날 봉우리에서 문턱을 낸다.
     *
     * <p><b>지나간 날은 좁히지 않는다.</b> 확률이 하나도 없는 날이 그것인데, 거기 담긴 숫자는
     * 「올 가능성」이 아니라 <b>실제로 온 양</b>이다. 두 시간 왔으면 두 시간 온 것이라 봉우리
     * 언저리만 남기는 것은 사실을 깎는 일이 된다.
     */
    private static Thresholds thresholdsOf(List<Integer> chances, List<BigDecimal> amounts,
                                           List<Integer> slots) {
        Integer peakChance = null;
        BigDecimal peakAmount = null;
        for (int slot : slots) {
            Integer chance = at(chances, slot);
            if (chance != null && (peakChance == null || chance > peakChance)) {
                peakChance = chance;
            }
            BigDecimal amount = at(amounts, slot);
            if (amount != null && (peakAmount == null || amount.compareTo(peakAmount) > 0)) {
                peakAmount = amount;
            }
        }
        if (peakChance == null) {
            return new Thresholds(MIN_CHANCE, MIN_AMOUNT);
        }
        int chanceCut = Math.max(MIN_CHANCE, BigDecimal.valueOf(peakChance)
                .multiply(PEAK_RATIO).setScale(0, RoundingMode.HALF_UP).intValue());
        BigDecimal amountCut = peakAmount == null
                ? MIN_AMOUNT
                : MIN_AMOUNT.max(peakAmount.multiply(PEAK_RATIO));
        return new Thresholds(chanceCut, amountCut);
    }

    /**
     * 이 시간에 비가 오는가 — 그날 문턱을 넘길 만큼 확률이 높거나 양이 잡히면.
     *
     * <p>⚠️ <b>다만 출처가 「안 온다」고 말한 시간은 아니다.</b> 확률·양·코드 셋 중
     * <b>코드가 거부권</b>을 쥔다. 실측(2026-08-25 미금역)에서 10~12시가 <b>확률 88~100%인데
     * 코드 {@code 1}(대체로 맑음)에 강수량 {@code 0.0mm}</b>였다 — 셋 중 둘이 「안 온다」고
     * 말하는 시간이다. 그것을 비로 쳤더니 {@link #summarize}가 이름을 붙일 강수 코드를 못 찾아
     * 화면에 <b>{@code ☁️ 오전 8시~11시 흐림 (최대 100%)}</b>가 찍혔고, 코드가 {@code 0}인 날은
     * <b>「맑음」</b>이 됐다 — 하루 요약이 「뇌우」인 날 바로 아래에서.
     *
     * <p>⚠️ <b>{@code null}은 {@code 0}이 아니다.</b> 강수량이 <b>안 온 것</b>은 「0mm였다」가
     * 아니라 「모른다」이므로 거부권을 세워 주지 않는다 — 세웠더니 강수량 배열이 없는 응답에서
     * 확률 100%가 통째로 잘렸다.
     *
     * <p>⚠️ <b>코드 혼자서는 못 자른다.</b> 양이 잡힌 시간은 그 양이 이긴다 — 실측
     * (2026-08-20 성남 17시)에 코드 {@code 3}(흐림)인데 {@code 0.2mm}가 함께 온 자리가 있었다.
     * 「안 온다」고 말하려면 <b>코드와 양이 함께</b> 말해야 한다.
     *
     * <p>⚠️ <b>확률 {@code 0%}도 「안 온다」는 값이다.</b> 같은 실측의 20시가 확률 {@code 0%}에
     * {@code 0.1mm}였는데, 양만 보고 비로 치면 화면이 <b>{@code (최대 0%)}</b>라고 적는다 —
     * 우산 그림 옆에 0%를 적는 것은 제 말을 제가 뒤집는 일이다. 시세의 {@code Price.positive}·
     * 목표가 {@code 0}과 같은 자리다.
     */
    private static boolean wet(Integer chance, BigDecimal amount, SkyCondition sky,
                               Thresholds cut) {
        // ⚠️ **양이 없는 것과 양이 0인 것은 다르다.** 한동안 `amount == null`까지 「양이 안
        //    온다고 말한다」로 셌는데, null은 0이 아니라 **모름**이다. 강수량 배열이 통째로
        //    없거나 짧게 온 응답에서 확률 100%·코드 0(맑음)인 시간이 전부 잘려
        //    「맑음 / 강수확률 100% / ☀️ 오전 맑음 / ☀️ 오후 맑음」이 나왔다 — 바로 위에 적은
        //    「코드와 양이 함께 말해야 한다」를 스스로 어긴 것이다.
        //    거부권은 **양이 있고 그 양이 문턱 아래일 때만** 선다
        boolean amountSaysDry = amount != null && amount.compareTo(MIN_AMOUNT) < 0;
        if ((amountSaysDry && saysDry(sky)) || (chance != null && chance == 0)) {
            return false;
        }
        if (chance != null && chance >= cut.chance()) {
            return true;
        }
        return amount != null && amount.compareTo(cut.amount()) >= 0;
    }

    /**
     * 출처가 이 시간을 <b>강수가 아니라고 말했는가.</b>
     *
     * <p>「모른다」와 「아니다」를 가른다 — {@link SkyCondition#precipitating()}은 둘 다
     * {@code false}지만 여기서는 {@link SkyCondition#UNKNOWN}이 거부권을 갖지 못한다.
     * 어휘를 못 읽었다고 강수 시각이 통째로 죽으면, 출처가 코드를 하나 늘리는 날 이 줄이
     * 조용히 사라진다. 재분석(지나간 날)은 코드를 함께 주므로 그쪽도 이 규칙이 그대로 통한다.
     */
    private static boolean saysDry(SkyCondition sky) {
        return sky != null && sky != SkyCondition.UNKNOWN && !sky.precipitating();
    }

    /** 하늘은 못 읽은 자리를 {@link SkyCondition#UNKNOWN}으로 메운다 — {@code null}을 흘리지 않는다. */
    private static SkyCondition skyAt(List<SkyCondition> skies, int slot) {
        SkyCondition sky = at(skies, slot);
        return sky == null ? SkyCondition.UNKNOWN : sky;
    }

    /** 병렬 배열이 짧거나 없을 때 {@code null}. */
    private static <T> T at(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }
}
