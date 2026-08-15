package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 종목·지수 시세 한 건. 국내(공공데이터포털)와 미국(FMP)이 같은 타입으로 들어온다.
 *
 * <p><b>{@code realtime}이 값의 신선도를 말한다.</b> 미국은 FMP가 현재가를 주지만
 * 국내는 공공데이터포털이 <b>전일 종가</b>만 준다 — 무료로 국내 현재가를 주는 곳이
 * 증권사 API(계좌 필요)뿐이라 어쩔 수 없다. 그 차이를 값에 담아 메시지에서 밝힌다
 * ({@code FxRate.source().intraday()}가 하는 일과 같다). 낡은 값을 숨기면 거짓말이 된다.
 *
 * @param code      {@code 005930} · {@code AAPL} · {@code ^IXIC}. 국내 지수는 코드가 없어 {@code null}
 * @param market    {@code KOSPI} · {@code NASDAQ} · {@code KOSPI시리즈}
 * @param currency  가격의 통화. 지수는 통화가 없으므로 {@link Money#NONE}
 * @param source    조회처. 화면의 <b>출처</b> 자리에 그대로 나간다 — {@code FxRate.source()}와
 *                  같은 역할이다. 화면이 {@code realtime}으로 출처를 넘겨짚지 않게 값에 담는다
 * @param at        이 값의 시각 — {@code realtime}이면 조회 시각, 아니면 종가일 00시(KST)
 * @param realtime  현재가면 true, 종가면 false
 * @param index     지수인가. 통화 단위도 원화 환산도 붙이지 않는다
 * @param changePercent 전일 대비 등락률(%). <b>{@code null}일 수 있다</b> — 못 구했다고
 *                      시세까지 막지는 않는다. 지수도 등락률이 있다
 * @param marketCap 시가총액. 화면용이 아니라 동명 후보를 가르는 내부 신호다 —
 *                  {@code 삼성}은 26건이 걸리는데 시총 1위가 삼성전자다
 */
public record StockQuote(String code, String name, String market,
                         BigDecimal price, BigDecimal changePercent, Money currency,
                         StockSource source, Instant at, boolean realtime,
                         boolean index, BigDecimal marketCap) {

    /**
     * 가격의 통화.
     *
     * <p>지수를 {@link #NONE}으로 따로 두는 이유는 <b>환산 대상이 아니기 때문</b>이다.
     * 코스피 6,345.53에 "원"을 붙이거나 나스닥 26,588에 원화를 병기하면 틀린 값이 된다.
     */
    public enum Money {
        KRW, USD, NONE;

        public boolean convertible() {
            return this == USD;
        }
    }
}
