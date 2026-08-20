package io.saiden.economyhelper.market;

/**
 * 시세 출처 — <b>화면에 밝힌다.</b>
 *
 * <p>{@link FxSource}와 같은 자리, 같은 이유다. 모든 통이 <b>제목 / 값 / 출처 / 시각</b> 뼈대를
 * 쓰는데 증시 통만 출처 자리가 비어 있었다. 뉴스는 매체명이, 환율은 고시 주체가, 코인은 거래소
 * 이름이 그 자리에 있다.
 *
 * <p><b>조회처가 곧 값의 성격이다.</b> 폴백이 일어나면 실시간이 전일 종가로 내려앉는다 —
 * 값이 왜 그 모양인지가 출처에 적혀 있고, 값이 이상해 보일 때 어디를 봐야 하는지도 그렇다.
 *
 * <p>벤더 상표가 아니라 <b>데이터를 낸 주체</b>를 적는다. 국내 종목·지수는 공공데이터포털이
 * 배관일 뿐이고 시세를 내는 곳은 금융위원회다.
 *
 * <p><b>선언 순서가 곧 이중화 순서다</b> — 화면의 출처 줄이 이 순서로 정렬된다
 * ({@code StockFormatter.sourcesOf}). 한 무리에 둘이 찍히면 1순위가 위에 온다.
 */
public enum StockSource {

    /**
     * 1순위. 국내·미국을 <b>둘 다 실시간</b>으로 준다.
     *
     * <p>대신 앱키가 필요하고 초당 한도가 있다(모의는 초당 1~2건). 받쳐 주는 자리는 제약이
     * 적은 쪽에 맡긴다 — 국내는 공공데이터포털, 미국은 FMP다.
     */
    KIS("한국투자증권"),

    /** 국내 2순위. 공공데이터포털의 금융위원회 시세 — <b>전일 종가만</b> 준다. */
    DATA_GO("금융위원회"),

    /** 미국 2순위. Financial Modeling Prep — 하루 250회 한도가 있다. */
    FMP("Financial Modeling Prep");

    private final String displayName;

    StockSource(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
