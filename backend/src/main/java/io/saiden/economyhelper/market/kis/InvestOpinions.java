package io.saiden.economyhelper.market.kis;

import io.saiden.economyhelper.market.StockOutlook.Rating;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 증권사별 의견 여러 줄을 <b>한 줄로 접는다</b> — I/O를 모르는 순수 클래스다
 * ({@code PrecipitationSpells}·{@code UpbitMarketIndex}와 같은 자리라 스프링 없이 단위 테스트한다).
 *
 * <p><b>왜 접어야 하나.</b> KIS {@code invest-opinion}은 컨센서스를 주지 않는다. 기간 안의
 * <b>발표 건</b>을 그대로 준다 — 실측(2026-08-20)에서 삼성전자 한 종목이 8개월 치로 90행이었다.
 * 그대로 세면 자주 내는 증권사가 여러 표를 갖는다.
 *
 * <p>그래서 <b>증권사마다 가장 최근 것 하나</b>만 남긴다. FMP가 이미 합쳐서 주는 값을
 * (`grades-consensus`) 국내에서는 우리가 만드는 셈이고, 그래서 두 시장의 숫자가 같은 뜻이 된다.
 */
final class InvestOpinions {

    private InvestOpinions() {
    }

    /**
     * 한 증권사가 낸 한 건.
     *
     * @param broker 증권사 이름 — 같은 곳의 옛 발표를 걷어내는 키다
     * @param date   발표일 {@code yyyyMMdd}. 문자열 비교로 최신을 고른다(고정 폭이라 안전하다)
     * @param label  증권사가 쓴 의견 그대로. <b>{@code BUY}·{@code 매수}·{@code Outperform}처럼
     *               제각각이다</b> — 정규화는 {@link Rating#ofLabel}이 한다
     * @param target 목표가. {@code 0}이나 빈 값일 수 있다
     */
    record Opinion(String broker, String date, String label, BigDecimal target) {
    }

    /** 접은 결과. 각 칸은 따로 없을 수 있다. */
    record Consensus(BigDecimal targetPrice, Rating rating, Integer analystCount) {
    }

    /**
     * @return 증권사별 최신 건만 남겨 낸 평균 목표가와 최빈 의견. 쓸 만한 행이 없으면 빈 값
     */
    static Optional<Consensus> of(List<Opinion> rows) {
        List<Opinion> latest = latestPerBroker(rows);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal target = averageTarget(latest);
        Rating rating = mostCommonRating(latest);
        if (target == null && rating == null) {
            return Optional.empty();
        }
        return Optional.of(new Consensus(target, rating, latest.size()));
    }

    /** 증권사마다 가장 최근 발표 하나. 이름이 없는 행은 셀 수 없으므로 버린다. */
    private static List<Opinion> latestPerBroker(List<Opinion> rows) {
        Map<String, Opinion> byBroker = new LinkedHashMap<>();
        for (Opinion row : rows) {
            if (row == null || row.broker() == null || row.broker().isBlank()) {
                continue;
            }
            byBroker.merge(row.broker(), row, InvestOpinions::newer);
        }
        return List.copyOf(byBroker.values());
    }

    private static Opinion newer(Opinion one, Opinion other) {
        if (one.date() == null) {
            return other;
        }
        if (other.date() == null) {
            return one;
        }
        return one.date().compareTo(other.date()) >= 0 ? one : other;
    }

    /**
     * 목표가 평균.
     *
     * <p><b>{@code 0}은 값이 아니다.</b> 목표가를 안 낸 발표가 {@code 0}으로 오는데, 그것을
     * 평균에 넣으면 실제보다 낮은 목표가가 화면에 나간다 — {@code Price.positive}가 시세에서
     * 같은 함정을 막는다.
     */
    private static BigDecimal averageTarget(List<Opinion> rows) {
        List<BigDecimal> targets = rows.stream()
                .map(Opinion::target)
                .filter(target -> target != null && target.signum() > 0)
                .toList();
        if (targets.isEmpty()) {
            return null;
        }
        return targets.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(targets.size()), 0, RoundingMode.HALF_UP);
    }

    /**
     * 가장 많은 의견.
     *
     * <p><b>모르는 글자는 세지 않는다</b>({@link Rating#ofLabel}이 빈 값을 준다). 넘겨짚어
     * 중립으로 떨어뜨리면 매도 의견이 조용히 사라진다.
     *
     * <p><b>같은 수면 보수적인 쪽을 고른다.</b> 매수 셋·중립 셋이면 「매수」라고 단정할 근거가
     * 없다 — 열거형 순서가 적극 매수→적극 매도라 뒤쪽이 보수적이다.
     */
    private static Rating mostCommonRating(List<Opinion> rows) {
        Map<Rating, Integer> counts = new LinkedHashMap<>();
        for (Opinion row : rows) {
            Rating.ofLabel(row.label()).ifPresent(rating -> counts.merge(rating, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<Rating, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
