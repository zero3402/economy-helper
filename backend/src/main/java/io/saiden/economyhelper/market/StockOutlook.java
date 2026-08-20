package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/**
 * 한 종목에 대한 <b>전망</b> — 지금 얼마인가({@link StockQuote}) 옆에 붙는 값이다.
 *
 * <p><b>셋이 따로 논다.</b> 목표주가·투자의견·실적발표일은 출처가 갈리고 어떤 것은 아예 없다 —
 * 국내에는 무료 실적발표일 출처가 없고, FMP 무료 티어는 심볼별 허용목록이라 {@code ORCL}이
 * 402다(실측 2026-08-20). 그래서 <b>필드마다 {@code null}일 수 있고, 없는 것은 줄을 안 적는다.</b>
 * {@code 0}으로 찍으면 「보합」·「목표가 0원」이라는 <b>값</b>이 되어 모른다는 뜻이 아니게 된다.
 *
 * <p><b>보충이지 폴백이 아니다.</b> 전망 조회가 실패해도 시세는 그대로 나간다 —
 * {@code WeatherService.withPrecipitationHours}가 강수 시각을 다루는 방식과 같은 자리다.
 *
 * @param earningsDate  다음 실적발표 예정일. 국내는 대개 {@code null}이다
 * @param targetPrice   목표주가. 통화는 {@link StockQuote#currency()}를 따른다
 * @param rating        투자의견
 * @param analystCount  그 의견을 낸 곳의 수. 「매수」 한 줄보다 몇 곳인지가 무게를 말한다
 * @param source        조회처. 시세 출처와 다를 수 있어 화면이 따로 밝힌다
 * @param at            조회 시각
 */
public record StockOutlook(LocalDate earningsDate, BigDecimal targetPrice, Rating rating,
                           Integer analystCount, StockSource source, Instant at) {

    /** 하나도 못 구했으면 붙일 것이 없다 — 그때는 아예 안 붙인다. */
    public boolean isEmpty() {
        return earningsDate == null && targetPrice == null && rating == null;
    }

    /**
     * 투자의견.
     *
     * <p>⚠️ <b>KIS의 {@code invt_opnn_cls_code}는 등급이 아니다.</b> 실측(2026-08-20, 5종목
     * 378행)에서 코드 {@code 3} 하나에 {@code Strong BUY}(6)·{@code Hold}(6)·
     * {@code Outperform}(3)·{@code Buy}(16)가 함께 들어 있었다. 코드로 읽으면 「적극 매수」와
     * 「중립」이 같은 것이 된다. 그래서 <b>증권사가 쓴 글자를 우리가 정규화</b>하고,
     * 모르는 글자는 <b>세지 않는다</b> — 틀린 의견이 빈손보다 나쁘다.
     */
    public enum Rating {
        STRONG_BUY("적극 매수"),
        BUY("매수"),
        HOLD("중립"),
        SELL("매도"),
        STRONG_SELL("적극 매도");

        private final String label;

        Rating(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * 증권사가 쓴 글자 → 등급.
         *
         * <p>표에 없는 글자는 <b>빈 값</b>이다. 넘겨짚어 「중립」으로 떨어뜨리면 매도 의견이
         * 조용히 중립이 되고, 화면은 그것을 알 길이 없다.
         *
         * <p>실측으로 확인한 표기: {@code BUY}·{@code 매수}·{@code Buy}·{@code Strong BUY}·
         * {@code Hold}·{@code HOLD}·{@code Outperform}·{@code Trading BUY}. 나머지는
         * 같은 계열의 통용 표기를 함께 받아 둔다.
         */
        public static Optional<Rating> ofLabel(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String text = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
            return switch (text) {
                case "STRONGBUY", "적극매수" -> Optional.of(STRONG_BUY);
                case "BUY", "매수", "OUTPERFORM", "TRADINGBUY", "OVERWEIGHT", "ACCUMULATE" ->
                        Optional.of(BUY);
                case "HOLD", "중립", "NEUTRAL", "MARKETPERFORM", "EQUALWEIGHT", "보유" ->
                        Optional.of(HOLD);
                case "SELL", "매도", "UNDERPERFORM", "UNDERWEIGHT", "REDUCE" -> Optional.of(SELL);
                case "STRONGSELL", "적극매도" -> Optional.of(STRONG_SELL);
                default -> Optional.empty();
            };
        }
    }
}
