package io.saiden.economyhelper.market.kis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 증권사별 발표 여러 줄을 <b>목표가 하나로 접는다</b> — I/O를 모르는 순수 클래스다
 * ({@code HalfDays}·{@code UpbitMarketIndex}와 같은 자리라 스프링 없이 단위 테스트한다).
 *
 * <p><b>왜 접어야 하나.</b> KIS {@code invest-opinion}은 컨센서스를 주지 않는다. 기간 안의
 * <b>발표 건</b>을 그대로 준다 — 실측(2026-08-20)에서 삼성전자 한 종목이 8개월 치로 90행이었다.
 * 그대로 평균하면 자주 내는 증권사가 여러 표를 갖는다.
 *
 * <p>그래서 <b>증권사마다 가장 최근 것 하나</b>만 남긴다. FMP가 컨센서스로 이미 합쳐 주는 값을
 * 국내에서는 우리가 만드는 셈이고, 그래야 두 시장의 숫자가 같은 뜻이 된다.
 *
 * <p>⚠️ <b>의견 글자는 더 이상 읽지 않는다.</b> 예전에는 같은 행의 {@code invt_opnn}을
 * 정규화해 최빈 의견을 냈다 — {@code invt_opnn_cls_code}가 등급이 아니라서(실측 5종목 378행에서
 * 코드 하나에 {@code Strong BUY}·{@code Hold}·{@code Outperform}이 함께 있었다) 증권사가 쓴
 * 글자를 우리가 표로 옮겼다. <b>투자의견을 화면에서 걷어내면서 그 표도 함께 지웠다.</b>
 * 다시 넣을 일이 있으면 그 함정부터 다시 확인해야 한다 — 코드로 읽으면 매도가 조용히 중립이 된다.
 */
final class InvestOpinions {

    private InvestOpinions() {
    }

    /**
     * 한 증권사가 낸 한 건.
     *
     * @param broker 증권사 이름 — 같은 곳의 옛 발표를 걷어내는 키다
     * @param date   발표일 {@code yyyyMMdd}. 문자열 비교로 최신을 고른다(고정 폭이라 안전하다)
     * @param target 목표가. {@code 0}이나 빈 값일 수 있다
     */
    record Opinion(String broker, String date, BigDecimal target) {
    }

    /**
     * @return 증권사별 최신 건만 남겨 낸 평균 목표가. 쓸 만한 행이 없으면 빈 값
     */
    static Optional<BigDecimal> averageTargetOf(List<Opinion> rows) {
        return Optional.ofNullable(averageTarget(latestPerBroker(rows)));
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
}
