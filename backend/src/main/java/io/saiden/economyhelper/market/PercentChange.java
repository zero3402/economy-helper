package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 전 값 대비 등락률(%).
 *
 * <p><b>출처가 주면 그것을 쓰고, 안 주는 자리만 우리가 낸다.</b> 환율 셋 중 한국투자증권은
 * 주고({@code prdy_ctrt}) 유럽중앙은행·수출입은행은 안 준다. 미국 종목도 KIS는 달러 등락률
 * 필드가 없어 여기서 낸다({@code t_xrat}은 원화 환산가 기준이라 쓰면 틀린 값이 나간다).
 * 그 계산을 한 곳에 둔다 — 두 곳에 흩어져 있으면 반올림 자리 하나가 어긋나도
 * 같은 화면의 두 값이 다르게 보인다.
 *
 * <p>중간 나눗셈을 소수 8자리로 잡는다. 원/달러는 값이 1,400 언저리라 하루 변동이
 * 소수 넷째 자리에서 갈리는데, 여기서 일찍 끊으면 표시 자리(둘째)까지 오차가 올라온다.
 */
public final class PercentChange {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PercentChange() {
    }

    /**
     * @return {@code (latest - previous) / previous × 100}. 셋 중 하나라도 없거나
     *         {@code previous}가 0이면 <b>{@code null}</b> — 0%는 "보합"이라는 값이므로
     *         못 구한 것을 0으로 채우면 화면이 거짓말을 한다
     */
    public static BigDecimal between(BigDecimal latest, BigDecimal previous) {
        if (latest == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return latest.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 비율을 %로 옮긴다 — 업비트가 {@code -0.0070571945} 꼴로 준다.
     *
     * <p>바이낸스·FMP·공공데이터포털·한국투자증권은 이미 %라 이 변환이 필요 없다. 출처마다 단위가
     * 다르다는 사실이 화면까지 새어 나가지 않도록 클라이언트 쪽에서 여기를 거친다.
     */
    public static BigDecimal fromRatio(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(HUNDRED);
    }
}
