package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.binance.BinanceSymbol;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 코인 한 종목의 <b>두 거래소 시세</b>.
 *
 * <p><b>등락률은 거래소마다 따로 담는다.</b> 업비트와 바이낸스는 다른 시장이라 같은 코인도
 * 등락률이 갈린다 — 한 값으로 뭉치면 어느 시장 이야기인지 알 수 없다.
 *
 * <p>원화 환산값은 담지 않는다. 환산은 <b>표시 시점의 관심사</b>고, {@code formatStock}이 환율을
 * 인자로 받는 것과 같은 이유다 — 모델에 넣어 두면 시세와 환율의 시각이 엇갈린 채로 굳는다.
 *
 * <p>바이낸스 심볼은 담지 않는다. {@code BTCUSDT}는 티커에 {@code USDT}를 붙인 것뿐이라
 * 화면에 적어도 알려 주는 것이 없고, 업비트에 없는 코인은 이름 자리에 이미 티커가 찍힌다.
 *
 * @param name   업비트 한글명이 있으면 그것, 없으면 티커({@code BNB}). 화면에는 {@link #ticker()}가
 *               나가지만, 후보를 가르거나 로그를 남길 때 사람이 읽을 이름이 여전히 필요하다
 * @param market 업비트 마켓 코드({@code KRW-BTC}). <b>업비트 미상장이면 {@code null}</b>
 * @param at     체결 시각
 */
public record CryptoQuote(String name, String market, Instant at, Quote upbit, Quote binance) {

    /**
     * 화면에 굵게 찍을 <b>티커</b>({@code BTC}).
     *
     * <p>업비트 상장이면 마켓 코드 뒷부분을 뗀다({@code KRW-BTC} → {@code BTC}).
     * 미상장이면 {@code market}이 {@code null}이고 그때는 {@code name}이 이미 대문자 티커다
     * ({@code BNB}) — 업비트에 없어 한글명을 확인할 곳이 없어서 그렇게 담긴다.
     */
    public String ticker() {
        return market == null ? name : market.substring(market.indexOf('-') + 1);
    }

    /**
     * 바이낸스 값의 <b>단위</b>({@code USDT}·{@code USD}).
     *
     * <p>테더만 {@code USD}로 갈린다({@code USDTUSD}). 화면이 스스로 판단하지 않고 값에게
     * 묻는다 — 단위와 심볼 유도가 다른 곳에 있으면 언젠가 어긋나 {@code 0.99906 USDT} 같은
     * 거짓말이 나간다.
     */
    public String binanceUnit() {
        return BinanceSymbol.quoteAssetOf(ticker());
    }

    /**
     * 거래소 한 곳의 값.
     *
     * <p><b>값이 없는 이유를 함께 들고 있다.</b> "그 거래소에 없다"와 "조회가 실패했다"는
     * 사용자에게 다른 말이다 — 전자는 영영 안 나오는 것이고 후자는 잠시 뒤 다시 치면 되는
     * 것이다. {@code null} 하나로 뭉치면 화면이 그 둘을 구분해 줄 수 없다.
     */
    public record Quote(BigDecimal price, BigDecimal changePercent, State state, Instant bannedUntil) {

        public enum State {
            /** 값이 있다. */
            OK,
            /** 그 거래소에 상장돼 있지 않다. 다시 시도해도 소용없다. */
            NOT_LISTED,
            /** 거래소가 응답하지 않았다. 장애·브레이커 열림을 포함한다. */
            FAILED,
            /**
             * <b>우리 IP가 밴돼 부르지 않았다</b>(바이낸스 418/429).
             *
             * <p>{@link #FAILED}와 갈라 두는 이유는 사용자에게 할 말이 다르기 때문이다 —
             * 저쪽은 "다시 쳐 보세요"이고 이쪽은 <b>다시 쳐도 소용없고 오히려 밴이 길어진다.</b>
             * 언제 풀리는지를 아는 유일한 실패이기도 하다.
             */
            BANNED
        }

        public static final Quote NOT_LISTED = new Quote(null, null, State.NOT_LISTED, null);
        public static final Quote FAILED = new Quote(null, null, State.FAILED, null);

        /**
         * @param changePercent 전일 대비 등락률(%). <b>{@code null}일 수 있다</b> —
         *                      값을 못 구했다고 시세까지 버리지는 않는다
         */
        public static Quote of(BigDecimal price, BigDecimal changePercent) {
            return price == null ? FAILED : new Quote(price, changePercent, State.OK, null);
        }

        /** @param until 밴이 풀리는 시각. 화면이 이것을 적는다 — 「잠시 후」는 아무것도 안 말한다 */
        public static Quote banned(Instant until) {
            return new Quote(null, null, State.BANNED, until);
        }

        public boolean hasPrice() {
            return state == State.OK && price != null;
        }
    }
}
