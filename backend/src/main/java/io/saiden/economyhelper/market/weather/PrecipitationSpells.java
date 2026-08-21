package io.saiden.economyhelper.market.weather;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시간별 값을 <b>토막으로 접는다</b> — I/O를 모르는 순수 클래스다
 * ({@code UpbitMarketIndex}·{@code PopularityScorer}와 같은 자리). 스프링 없이 단위 테스트한다.
 *
 * <p><b>왜 접는가.</b> 24시간을 그대로 나열하면 텔레그램 한 통이 숫자 벽이 된다. 사람이 알고
 * 싶은 것은 「몇 시부터 몇 시까지」이므로 연속한 시간을 하나로 묶는다.
 *
 * <p><b>기준은 확률과 강수량을 함께 본다.</b> 하나만 보면 둘 다 틀린다 — 확률만 보면 여름 오후의
 * 낮은 확률이 종일 토막을 만들고, 강수량만 보면 예보가 {@code 0.0mm}로 주는 시간이 통째로
 * 빠진다(실측: 확률 60%인 13시의 강수량이 {@code 0.4mm}, 확률 68%인 17시는 {@code 0.0mm}였다).
 */
public final class PrecipitationSpells {

    /**
     * 이 확률 미만은 토막으로 만들지 않는다.
     *
     * <p>여름 오후는 확률이 종일 10~30%로 깔린다. 그것까지 적으면 「오전 9시~밤 11시 비」가 되어
     * <b>아무것도 말해 주지 않는다.</b> 반대로 너무 높이 잡으면 진짜 소나기가 빠진다.
     * 실측(성남시)에서 13~19시가 60~80%였고 그 앞뒤가 47%·45%로 떨어졌다 — 그 사이를 가른다.
     */
    private static final int MIN_CHANCE = 50;

    /** 이 양 이상이면 확률이 낮아도 토막이다 — 짧고 센 소나기가 여기 걸린다. */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.1");

    /**
     * <b>봉우리의 몇 할까지가 그 비인가.</b>
     *
     * <p>문턱을 {@link #MIN_CHANCE} 하나로 두면 토막이 너무 넓어진다 — 실측(2026-08-20 성남)에서
     * 13시 60%부터 19시 45%까지가 한 토막이 됐는데, 정작 몰린 시간은 14~17시(68~80%)였다.
     * 「오후 1시~7시」는 우산을 언제 챙길지를 <b>여섯 시간의 폭</b>으로 말하는 것이라
     * 「비옴」에서 별로 나아가지 못한다.
     *
     * <p>그래서 그날 봉우리에 상대적으로 자른다. 0.8이면 80% 봉우리에서 64%가 경계가 되어
     * 실측의 14~17시가 남는다. 더 조이면(0.9) 봉우리 한두 시간만 남아 앞뒤로 젖은 시간을
     * 마른 것처럼 말하게 되고, 더 풀면(0.7) 원래의 여섯 시간으로 돌아간다.
     *
     * <p>⚠️ <b>절대 문턱을 없애는 것이 아니다.</b> 봉우리가 20%인 날에 그 80%인 16%를
     * 경계로 삼으면 마른 날에도 토막이 생긴다 — 둘 중 <b>큰 쪽</b>을 쓴다.
     */
    private static final BigDecimal PEAK_RATIO = new BigDecimal("0.8");

    private PrecipitationSpells() {
    }

    /**
     * 시간별 값을 날짜별 토막 목록으로.
     *
     * <p>⚠️ <b>날짜로 가르는 것은 시각 문자열 그대로다.</b> Open-Meteo를 {@code timezone=auto}로
     * 부르므로 이미 그 지역 현지시이고, 여기서 시간대를 다시 계산하면 남의 하루를 우리 달력으로
     * 자르게 된다 — 일 단위에서 이미 세운 규칙이다.
     *
     * @param times   정시 목록. 병렬 배열의 축이다
     * @param chances 확률(%). 지나간 날은 {@code null}
     * @param amounts 강수량(mm). 없을 수 있다
     * @param codes   WMO 코드. 종류를 여기서 가른다
     */
    public static Map<LocalDate, List<PrecipitationSpell>> byDay(List<LocalDateTime> times,
                                                        List<Integer> chances,
                                                        List<BigDecimal> amounts,
                                                        List<Integer> codes) {
        Map<LocalDate, List<PrecipitationSpell>> byDay = new TreeMap<>();
        if (times == null || times.isEmpty()) {
            return byDay;
        }
        // 하루씩 따로 접는다 — 자정을 넘겨 이어 붙이면 「23시~1시」가 어느 날 것인지 알 수 없다
        Map<LocalDate, List<Integer>> slotsByDay = new TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime at = times.get(i);
            if (at != null) {
                slotsByDay.computeIfAbsent(at.toLocalDate(), day -> new ArrayList<>()).add(i);
            }
        }
        slotsByDay.forEach((day, slots) -> {
            List<PrecipitationSpell> spells =
                    fold(times, chances, amounts, codes, slots, thresholdsOf(chances, amounts, slots));
            if (!spells.isEmpty()) {
                byDay.put(day, spells);
            }
        });
        return byDay;
    }

    /**
     * 하루치 슬롯을 연속 구간으로 접는다 — 문턱은 그날 봉우리가 정한다.
     *
     * <p><b>정오에서도 끊는다.</b> {@code byDay}가 자정에서 끊는 것과 같은 이유다 —
     * 「오전 8시~오후 5시 비」는 아홉 시간의 폭이라 「비옴」에서 별로 나아가지 못한다.
     * 오전과 오후를 따로 적으면 사용자가 우산을 언제 챙길지 두 번 판단할 수 있다.
     *
     * <p><b>쪼개는 것이 화면이 아니라 여기인 이유.</b> 토막은 그 구간의 <b>최대 확률</b>과
     * <b>합계 강수량</b>을 든다. 화면에서 한 토막을 두 줄로 그리면 두 줄에 같은 숫자가 찍혀
     * 「오전에도 80%, 오후에도 80%」가 되는데, 실제로는 오전 55%·오후 80%일 수 있다 —
     * 그건 값을 다른 것인 척 하는 일이다. 여기서 끊으면 {@link #spell}이 반나절마다 다시
     * 세므로 두 숫자가 각각 사실이 된다.
     *
     * <p>⚠️ <b>문턱은 그날 것을 그대로 쓴다.</b> 반나절마다 봉우리를 다시 재면 안 된다 —
     * 오전 봉우리가 60%인 날 그 80%는 48%이고, 절대 문턱 50%에 밀려 결국 50%가 되는데,
     * 그러면 하루 기준(80%의 80% = 64%)에서는 걸러지던 55% 시간대가 오전에만 새로 통과한다.
     * 같은 날 같은 비를 반나절에 따라 다르게 판정하는 것이라 {@link #PEAK_RATIO}가 말하는
     * 「그날 봉우리」가 아니게 된다. <b>끊는 것만 하고 문턱은 건드리지 않는다.</b>
     */
    private static List<PrecipitationSpell> fold(List<LocalDateTime> times, List<Integer> chances,
                                        List<BigDecimal> amounts, List<Integer> codes,
                                        List<Integer> slots, Thresholds cut) {
        List<PrecipitationSpell> spells = new ArrayList<>();
        int start = -1;
        for (int position = 0; position < slots.size(); position++) {
            int slot = slots.get(position);
            boolean wet = wet(at(chances, slot), at(amounts, slot), cut);
            if (wet && start < 0) {
                start = position;
            }
            boolean last = position == slots.size() - 1;
            // 다음 슬롯이 오후면 여기가 오전 토막의 끝이다
            boolean noonBoundary = wet && !last
                    && morning(times, slots, position) && !morning(times, slots, position + 1);
            if (start >= 0 && (!wet || last || noonBoundary)) {
                int end = wet && (last || noonBoundary) ? position : position - 1;
                spells.add(spell(times, chances, amounts, codes, slots, start, end));
                start = -1;
            }
        }
        return spells;
    }

    /**
     * 그 슬롯이 오전인가.
     *
     * <p>⚠️ <b>시간대를 다시 계산하지 않는다.</b> Open-Meteo를 {@code timezone=auto}로 부르므로
     * {@code times}의 시각이 이미 그 지역 현지시다 — 여기서 {@code ZoneId}를 끼우면 남의 정오를
     * 우리 시계로 옮기게 된다({@code byDay}가 날짜를 가를 때와 같은 규칙이다).
     */
    private static boolean morning(List<LocalDateTime> times, List<Integer> slots, int position) {
        LocalDateTime at = at(times, slots.get(position));
        return at != null && at.getHour() < 12;
    }

    /**
     * 한 구간을 토막 하나로.
     *
     * <p><b>종류는 그 구간에서 가장 무거운 것으로 부른다.</b> 이슬비로 시작해 뇌우로 끝나는
     * 구간을 「이슬비」라 적으면 사용자가 우산만 들고 나간다 — {@link SkyCondition#ofWmoCode}의
     * 코드 값이 대체로 심각도 순이라 최댓값이 그 구실을 한다.
     */
    private static PrecipitationSpell spell(List<LocalDateTime> times, List<Integer> chances,
                                   List<BigDecimal> amounts, List<Integer> codes,
                                   List<Integer> slots, int start, int end) {
        Integer maxChance = null;
        BigDecimal total = null;
        Integer heaviest = null;
        for (int position = start; position <= end; position++) {
            int slot = slots.get(position);
            Integer chance = at(chances, slot);
            if (chance != null && (maxChance == null || chance > maxChance)) {
                maxChance = chance;
            }
            BigDecimal amount = at(amounts, slot);
            if (amount != null) {
                total = total == null ? amount : total.add(amount);
            }
            Integer code = at(codes, slot);
            if (code != null && (heaviest == null || code > heaviest)) {
                heaviest = code;
            }
        }
        SkyCondition kind = SkyCondition.ofWmoCode(heaviest);
        return maxChance != null
                ? PrecipitationSpell.withChance(times.get(slots.get(start)).toLocalTime(),
                        times.get(slots.get(end)).toLocalTime(), kind, maxChance)
                : PrecipitationSpell.withAmount(times.get(slots.get(start)).toLocalTime(),
                        times.get(slots.get(end)).toLocalTime(), kind, total);
    }

    /**
     * 그날의 문턱.
     *
     * @param chance 이 확률 미만은 토막이 아니다
     * @param amount 확률이 낮아도 이 양 이상이면 토막이다
     */
    private record Thresholds(int chance, BigDecimal amount) {
    }

    /**
     * 그날 봉우리에서 문턱을 낸다.
     *
     * <p><b>지나간 날은 좁히지 않는다.</b> 확률이 하나도 없는 날이 그것인데, 거기 담긴 숫자는
     * 「올 가능성」이 아니라 <b>실제로 온 양</b>이다. 실제로 두 시간 왔으면 두 시간 온 것이라
     * 봉우리 언저리만 남기는 것은 사실을 깎는 일이 된다. 예보에서 가장자리를 떼는 것과 뜻이 다르다.
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
        int chanceCut = Math.max(MIN_CHANCE,
                BigDecimal.valueOf(peakChance).multiply(PEAK_RATIO).setScale(0, RoundingMode.HALF_UP).intValue());
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

    /** 병렬 배열이 짧거나 없을 때 {@code null} — {@code DailyBlock.at}과 같은 규칙이다. */
    private static <T> T at(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }
}
