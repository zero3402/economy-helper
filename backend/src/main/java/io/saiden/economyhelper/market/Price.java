package io.saiden.economyhelper.market;

import java.math.BigDecimal;

/**
 * 시세 값이 <b>값인지</b> 판단한다.
 *
 * <p><b>{@code 0}은 값이 아니다.</b> 출처들이 "없다"를 에러가 아니라 0이나 빈 문자열로 준다 —
 * KIS는 지수 심볼이 틀리면 {@code rt_cd=0}에 {@code 0.00}을 주고(실측 {@code DJI}·{@code DJIA}),
 * 공공데이터포털은 종가 필드를 빈 문자열로 준다. 이걸 값으로 받으면 <b>두 가지가 함께</b> 깨진다:
 * 화면에 「코스피 0」이 찍히고, 성공으로 반환되니 <b>이중화의 폴백이 아예 돌지 않는다.</b>
 *
 * <p><b>이 클래스가 따로 있는 이유는 같은 교훈을 한 곳에서만 배웠기 때문이다.</b>
 * {@code KisStockApi}가 실측으로 이 가드를 만들어 달았는데, 같은 함정에 놓인 형제 셋
 * ({@code KisFxClient}·{@code FmpStockClient}·{@code DataGoStockClient})에는 안 걸려 있었다.
 * 판단이 넷으로 갈려 있으면 하나만 고쳐지는 날이 온다.
 *
 * <p>등락률에는 쓰지 않는다 — 등락률의 {@code 0}은 "보합"이라는 <b>값</b>이고 {@code null}이
 * "모른다"다({@code DataGoStockClient.percent}). 여기서 가리는 것은 <b>가격·환율</b>뿐이다.
 */
public final class Price {

    private Price() {
    }

    /**
     * 값이면 그대로 돌려주고, 아니면 <b>던진다.</b>
     *
     * <p>빈 값을 돌려주면 다음 출처가 시도되지 않고 그대로 빈손이 나간다
     * ({@code ARCHITECTURE.md} 4-1) — 그래서 예외여야 한다.
     *
     * @param what 로그와 예외 메시지에 적을 대상. {@code "지수 코스피"}처럼 무엇의 값인지 밝힌다
     */
    public static BigDecimal require(BigDecimal price, String what) {
        if (!positive(price)) {
            throw new IllegalStateException(what + " 응답에 값이 없습니다: " + price);
        }
        return price;
    }

    /** {@code null}도 0도 음수도 값이 아니다. */
    public static boolean positive(BigDecimal price) {
        return price != null && price.signum() > 0;
    }
}
