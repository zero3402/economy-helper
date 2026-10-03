package io.saiden.economyhelper.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 전 값 대비 등락률 — <b>언제나 % 단위다</b>({@code 1.5}가 1.5%).
 *
 * <p><b>이 타입이 있는 이유는 단위다.</b> 업비트는 비율({@code -0.0070571945})로, 나머지는 %로 준다.
 * 둘 다 {@code BigDecimal}이면 비율이 %자리에 그대로 들어가도 컴파일러가 모른다 — 비율은
 * {@link #fromRatio}를 거쳐야만 이 타입이 된다. {@code 0}은 "보합"이라는 값이고, 모르면
 * 이 타입 자체가 없다({@code null}).
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
public record PercentChange(BigDecimal percent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public PercentChange {
        if (percent == null) {
            throw new IllegalArgumentException("등락률이 없습니다 — 모르면 PercentChange 자체를 두지 않는다");
        }
    }

    /** 출처가 이미 %로 준 값. <b>{@code null}이면 {@code null}</b> — 모르는 것을 0%로 채우지 않는다. */
    public static PercentChange ofNullable(BigDecimal percent) {
        return percent == null ? null : new PercentChange(percent);
    }

    /**
     * @return {@code (latest - previous) / previous × 100}. 둘 중 하나라도 없거나
     *         {@code previous}가 0이면 <b>빈 값</b> — 0%는 "보합"이라는 값이므로
     *         못 구한 것을 0으로 채우면 화면이 거짓말을 한다
     */
    public static Optional<PercentChange> between(BigDecimal latest, BigDecimal previous) {
        if (latest == null || previous == null || previous.signum() == 0) {
            return Optional.empty();
        }
        // ⚠️ **반올림은 한 번만 한다** — 곱하고 한 번에 나눈다. 두 번 접으면 오차가 타고 올라간다:
        //    11579 → 11590은 정확히 0.094999568%인데 두 번 접으면 0.10%가 나온다(정답 0.09%)
        return Optional.of(new PercentChange(latest.subtract(previous)
                .multiply(HUNDRED)
                .divide(previous, 2, RoundingMode.HALF_UP)));
    }

    /**
     * 비율을 %로 옮긴다 — 업비트가 {@code -0.0070571945} 꼴로 준다. 반올림하지 않는다.
     *
     * <p>바이낸스·FMP·공공데이터포털·한국투자증권은 이미 %라 이 변환이 필요 없다. 출처마다 단위가
     * 다르다는 사실이 화면까지 새어 나가지 않도록 클라이언트 쪽에서 여기를 거친다.
     *
     * @return {@code ratio}가 {@code null}이면 {@code null}
     */
    public static PercentChange fromRatio(BigDecimal ratio) {
        return ratio == null ? null : new PercentChange(ratio.multiply(HUNDRED));
    }
}
