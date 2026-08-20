package io.saiden.economyhelper.market.weather;

import java.math.BigDecimal;
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
            List<PrecipitationSpell> spells = fold(times, chances, amounts, codes, slots);
            if (!spells.isEmpty()) {
                byDay.put(day, spells);
            }
        });
        return byDay;
    }

    /** 하루치 슬롯을 연속 구간으로 접는다. */
    private static List<PrecipitationSpell> fold(List<LocalDateTime> times, List<Integer> chances,
                                        List<BigDecimal> amounts, List<Integer> codes,
                                        List<Integer> slots) {
        List<PrecipitationSpell> spells = new ArrayList<>();
        int start = -1;
        for (int position = 0; position < slots.size(); position++) {
            int slot = slots.get(position);
            boolean wet = wet(at(chances, slot), at(amounts, slot));
            if (wet && start < 0) {
                start = position;
            }
            boolean last = position == slots.size() - 1;
            if (start >= 0 && (!wet || last)) {
                int end = wet && last ? position : position - 1;
                spells.add(spell(times, chances, amounts, codes, slots, start, end));
                start = -1;
            }
        }
        return spells;
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

    /** 이 시간에 비가 오는가 — 확률이 충분하거나 양이 잡힐 만큼이면. */
    private static boolean wet(Integer chance, BigDecimal amount) {
        if (chance != null && chance >= MIN_CHANCE) {
            return true;
        }
        return amount != null && amount.compareTo(MIN_AMOUNT) >= 0;
    }

    /** 병렬 배열이 짧거나 없을 때 {@code null} — {@code DailyBlock.at}과 같은 규칙이다. */
    private static <T> T at(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }
}
