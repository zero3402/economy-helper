package io.saiden.economyhelper.shared.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 가격·환율 한 값 — <b>만들어졌으면 값이다.</b> {@code null}·0·음수는 이 타입이 될 수 없다.
 *
 * <p><b>{@code 0}은 값이 아니다.</b> 출처들이 "없다"를 에러가 아니라 0이나 빈 문자열로 준다 —
 * KIS는 지수 심볼이 틀리면 {@code rt_cd=0}에 {@code 0.00}을 주고(실측 {@code DJI}·{@code DJIA}),
 * 공공데이터포털은 종가 필드를 빈 문자열로 준다. 이걸 값으로 받으면 <b>두 가지가 함께</b> 깨진다:
 * 화면에 「코스피 0」이 찍히고, 성공으로 반환되니 <b>이중화의 폴백이 아예 돌지 않는다.</b>
 *
 * <p>시세 클라이언트 전부({@code KisStockApi}·{@code KisFxClient}·{@code FmpStockClient}·
 * {@code DataGoStockClient})가 벤더 숫자를 여기서 이 타입으로 바꾼다 — 그 뒤로는 아무도
 * 「0이 아닌가」를 다시 묻지 않는다. 목표가 {@code 0}도 같은 규칙이다({@link #of}).
 *
 * <p>등락률에는 쓰지 않는다 — 등락률의 {@code 0}은 "보합"이라는 <b>값</b>이고 {@code null}이
 * "모른다"다({@link PercentChange}). 여기서 가리는 것은 <b>가격·환율</b>뿐이다.
 */
public record Price(BigDecimal value) {

    public Price {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다: " + value);
        }
    }

    /** 값이면 담고, {@code null}·0·음수면 빈 값 — 벤더가 「모른다」를 {@code 0.00}으로 주는 자리다. */
    public static Optional<Price> of(BigDecimal value) {
        return value != null && value.signum() > 0 ? Optional.of(new Price(value)) : Optional.empty();
    }

    /**
     * 값이면 담아 돌려주고, 아니면 <b>던진다.</b>
     *
     * <p>빈 값을 돌려주면 다음 출처가 시도되지 않고 그대로 빈손이 나간다
     * ({@code docs/design.md} 4.1) — 그래서 예외여야 한다.
     *
     * @param what 로그와 예외 메시지에 적을 대상. {@code "지수 코스피"}처럼 무엇의 값인지 밝힌다
     */
    public static Price require(BigDecimal price, String what) {
        return of(price).orElseThrow(
                () -> new IllegalStateException(what + " 응답에 값이 없습니다: " + price));
    }
}
