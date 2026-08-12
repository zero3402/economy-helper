package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 종목 시세 한 건.
 *
 * <p><b>{@code basisDate}가 값의 일부다.</b> 공공데이터포털은 <b>전일 종가</b>를 준다 —
 * 오늘 날짜로 조회하면 결과가 0건이다. 기준일을 숨기면 사용자가 실시간 현재가로 오해하므로
 * 메시지에 반드시 드러낸다({@code FxRate}가 출처와 고시일을 담는 것과 같은 이유다).
 *
 * @param marketCap 시가총액. <b>화면용이 아니라</b> 동명 후보를 가르는 내부 신호다 —
 *                  {@code 삼성}은 26건이 걸리는데 시총 1위가 삼성전자다
 *                  ({@code CryptoQuote}에서 거래대금이 하는 역할과 같다)
 */
public record StockQuote(String code, String name, String market,
                         BigDecimal price, LocalDate basisDate, BigDecimal marketCap) {

    /**
     * 지수인가 — 종목코드가 없다.
     *
     * <p>지수는 통화 단위가 없어 "원"을 붙이면 안 되고, 종목코드를 괄호에 넣을 수도 없다.
     * 표기가 갈리는 지점이 여기 하나뿐이라 별도 타입을 만들지 않았다.
     */
    public boolean isIndex() {
        return code == null || code.isBlank();
    }
}
