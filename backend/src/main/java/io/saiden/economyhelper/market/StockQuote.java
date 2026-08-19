package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 종목·지수 시세 한 건. 국내(공공데이터포털)와 미국(FMP)이 같은 타입으로 들어온다.
 *
 * <p><b>{@code realtime}이 값의 신선도를 말한다.</b> 1순위인 한국투자증권은 국내도 미국도
 * 현재가를 주지만, 국내 2순위인 공공데이터포털은 <b>전일 종가</b>만 준다. <b>그래서 이 값은
 * 폴백이 일어났는지를 그대로 드러낸다</b> — 그 차이를 메시지가 기준 줄로 밝힌다
 * ({@code FxRate.source().intraday()}가 하는 일과 같다). 낡은 값을 숨기면 거짓말이 된다.
 *
 * <p><b>화면이 쓰는 것만 담는다.</b> 종목코드·시가총액·지수 여부를 함께 들고 다니던 때가
 * 있었는데 아무도 읽지 않았다. 동명 후보를 시가총액으로 가르는 일은 이 타입이 만들어지기 전
 * {@code StockPrice} 단계에서 끝나고({@code DataGoStockClient}), 지수 여부는 {@code currency}가
 * {@link Money#NONE}인 것과 무조건 같은 말이었다 — 같은 사실을 두 값으로 들면 언젠가 어긋난다.
 * {@code market}은 그 반대다: 화면이 무리를 가르는 데 실제로 읽으므로 값에 담는다.
 *
 * @param market    국내인가 미국인가. <b>무리를 가르는 값</b>이다 — {@link Market} 참조
 * @param currency  가격의 통화. 지수는 통화가 없으므로 {@link Money#NONE}이고,
 *                  그것이 곧 "지수다"라는 뜻이다 — 단위도 원화 환산도 붙이지 않는다
 * @param source    조회처. 화면의 <b>출처</b> 자리에 그대로 나간다 — {@code FxRate.source()}와
 *                  같은 역할이다. 화면이 {@code realtime}으로 출처를 넘겨짚지 않게 값에 담는다
 * @param at        이 값의 시각 — {@code realtime}이면 조회 시각, 아니면 종가일 00시(KST)
 * @param realtime  현재가면 true, 종가면 false
 * @param changePercent 전일 대비 등락률(%). <b>{@code null}일 수 있다</b> — 못 구했다고
 *                      시세까지 막지는 않는다. 지수도 등락률이 있다
 */
public record StockQuote(String name, BigDecimal price, BigDecimal changePercent, Money currency,
                         Market market, StockSource source, Instant at, boolean realtime) {

    /**
     * 어느 시장인가 — <b>화면의 무리를 이것으로 가른다.</b>
     *
     * <p>예전에는 {@code realtime}으로 갈랐다. 국내가 전일 종가뿐이고 미국만 현재가였던 시절에는
     * 그게 곧 지역이었기 때문이다. <b>국내에 실시간 출처(KIS)가 붙는 순간 그 가정이 깨진다</b> —
     * 삼성전자와 코스피가 「미국」 무리에 찍힌다. 그래서 지역을 값에 담는다.
     *
     * <p>{@code currency}로는 못 가른다. 국내 지수와 미국 지수가 둘 다 {@link Money#NONE}이다.
     */
    public enum Market {
        DOMESTIC("국내"), US("미국");

        private final String title;

        Market(String title) {
            this.title = title;
        }

        /** 무리 제목에 굵게 찍히는 이름. */
        public String title() {
            return title;
        }
    }

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
