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
     * @param codes   WMO 코드. 비의 종류도, 마른 반나절의 하늘도 여기서 읽는다
     * @return 날짜별 목록. 각 날은 <b>오전·오후 순</b>이고, 시간별 값이 있는 반나절만 담긴다
     */
    public static Map<LocalDate, List<HalfDay>> byDay(List<LocalDateTime> times,
                                                      List<Integer> chances,
                                                      List<BigDecimal> amounts,
                                                      List<Integer> codes) {
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
            List<HalfDay> halves = fold(times, chances, amounts, codes, slots,
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
                                      List<BigDecimal> amounts, List<Integer> codes,
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
                halves.add(summarize(half, times, chances, amounts, codes, inHalf, cut));
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
                                     List<BigDecimal> amounts, List<Integer> codes,
                                     List<Integer> slots, Thresholds cut) {
        Stretch strongest = null;
        Stretch current = null;
        for (int slot : slots) {
            if (wet(at(chances, slot), at(amounts, slot), cut)) {
                current = current == null ? new Stretch(slot) : current;
                current.extend(slot, at(chances, slot), at(amounts, slot), at(codes, slot));
            } else if (current != null) {
                strongest = stronger(strongest, current);
                current = null;
            }
        }
        strongest = stronger(strongest, current);

        if (strongest == null) {
            return HalfDay.dry(half, skyOf(codes, slots));
        }
        LocalTime from = times.get(strongest.first).toLocalTime();
        LocalTime to = times.get(strongest.last).toLocalTime();
        SkyCondition kind = SkyCondition.ofWmoCode(strongest.heaviestCode);
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
     * 마른 반나절의 하늘 — <b>가장 흔한 것</b>으로 부른다.
     *
     * <p>가장 무거운 것으로 부르지 않는다. 대체로 맑은 오전에 이슬비 코드가 한 시간 끼면
     * 그 반나절이 「이슬비」가 되는데, 문턱을 못 넘어 비로 치지 않기로 한 값이 이름만 비가 되는
     * 셈이다. 동률이면 무거운 쪽을 든다 — 덜 알리는 것보다 낫다.
     */
    private static SkyCondition skyOf(List<Integer> codes, List<Integer> slots) {
        Map<SkyCondition, Integer> counts = new EnumMap<>(SkyCondition.class);
        SkyCondition best = SkyCondition.UNKNOWN;
        int bestCount = 0;
        for (int slot : slots) {
            SkyCondition sky = SkyCondition.ofWmoCode(at(codes, slot));
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
        private Integer heaviestCode;

        private Stretch(int first) {
            this.first = first;
            this.last = first;
        }

        /**
         * 종류는 그 덩어리에서 <b>가장 무거운 것</b>으로 부른다 — 이슬비로 시작해 뇌우로 끝나는
         * 구간을 「이슬비」라 적으면 사용자가 우산만 들고 나간다.
         */
        private void extend(int slot, Integer chance, BigDecimal amount, Integer code) {
            last = slot;
            if (chance != null && (maxChance == null || chance > maxChance)) {
                maxChance = chance;
            }
            if (amount != null) {
                totalAmount = totalAmount == null ? amount : totalAmount.add(amount);
            }
            if (code != null && (heaviestCode == null || code > heaviestCode)) {
                heaviestCode = code;
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

    /** 이 시간에 비가 오는가 — 그날 문턱을 넘길 만큼 확률이 높거나 양이 잡히면. */
    private static boolean wet(Integer chance, BigDecimal amount, Thresholds cut) {
        if (chance != null && chance >= cut.chance()) {
            return true;
        }
        return amount != null && amount.compareTo(cut.amount()) >= 0;
    }

    /** 병렬 배열이 짧거나 없을 때 {@code null}. */
    private static <T> T at(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }
}
