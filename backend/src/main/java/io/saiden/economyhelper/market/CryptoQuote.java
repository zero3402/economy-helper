package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 코인 한 종목의 <b>두 거래소 시세</b>.
 *
 * <p>등락률·전일종가는 업비트가 주지만 담지 않는다. {@code CLAUDE.md}가 요구하는 건 "현재 가격"이고,
 * 토스는 등락률을 주지 않아 {@code /stock}에는 넣을 수 없다 — 코인에만 붙이면 명령마다
 * 정보 밀도가 달라진다.
 *
 * <p>원화 환산값은 담지 않는다. 환산은 <b>표시 시점의 관심사</b>고, {@code formatStock}이 환율을
 * 인자로 받는 것과 같은 이유다 — 모델에 넣어 두면 시세와 환율의 시각이 엇갈린 채로 굳는다.
 *
 * <p>바이낸스 심볼은 담지 않는다. {@code BTCUSDT}는 티커에 {@code USDT}를 붙인 것뿐이라
 * 화면에 적어도 알려 주는 것이 없고, 업비트에 없는 코인은 이름 자리에 이미 티커가 찍힌다.
 *
 * @param name   화면에 쓸 이름. <b>업비트 한글명이 있으면 그것, 없으면 티커</b>({@code BNB}).
 *               지어낸 한글 표기({@code 비앤비})를 쓰지 않는 이유는 아무도 그렇게 부르지 않아서다
 * @param market 업비트 마켓 코드({@code KRW-BTC}). <b>업비트 미상장이면 {@code null}</b>
 * @param at     체결 시각
 */
public record CryptoQuote(String name, String market, Instant at, Quote upbit, Quote binance) {

    /**
     * 아이콘을 찾을 때 쓰는 티커.
     *
     * <p>업비트 마켓 코드가 있으면 거기서 떼고, 없으면 이름이 곧 티커다 — 업비트에 없는
     * 코인은 이름 자리에 티커를 쓰기 때문이다.
     */
    public String ticker() {
        return market == null ? name : market.substring(market.indexOf('-') + 1);
    }

    /**
     * 거래소 한 곳의 값.
     *
     * <p><b>값이 없을 때 왜 없는지를 함께 들고 있어야 한다.</b> 예전에는 {@code null} 하나로
     * "그 거래소에 없다"와 "조회가 실패했다"를 뭉갰는데, 둘은 사용자에게 다른 말이다 —
     * 전자는 영영 안 나오는 것이고 후자는 잠시 뒤 다시 치면 되는 것이다.
     */
    public record Quote(BigDecimal price, State state) {

        public enum State {
            /** 값이 있다. */
            OK,
            /** 그 거래소에 상장돼 있지 않다. 다시 시도해도 소용없다. */
            NOT_LISTED,
            /** 거래소가 응답하지 않았다. 장애·지역차단·브레이커 열림을 모두 포함한다. */
            FAILED
        }

        public static final Quote NOT_LISTED = new Quote(null, State.NOT_LISTED);
        public static final Quote FAILED = new Quote(null, State.FAILED);

        public static Quote of(BigDecimal price) {
            return price == null ? FAILED : new Quote(price, State.OK);
        }

        public boolean hasPrice() {
            return state == State.OK && price != null;
        }
    }
}
