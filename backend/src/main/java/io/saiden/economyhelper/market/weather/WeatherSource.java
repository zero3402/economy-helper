package io.saiden.economyhelper.market.weather;

/**
 * 날씨를 가져온 곳 — <b>화면에 이름을 그대로 적는다.</b>
 *
 * <p>폴백으로 Open-Meteo가 답했는데 AccuWeather라고 적으면 거짓말이 된다. {@code FxSource}가
 * {@code 수출입은행 매매기준율}과 {@code 유럽중앙은행}을 구분해 적는 것과 같은 이유다.
 *
 * <p><b>선언 순서가 곧 이중화 순서다</b>({@code WeatherService.ORDER}). 화면의 출처 줄도
 * 이 순서로 정렬하므로({@code MessageFormatter.weatherSourcesOf}) 둘을 함께 고쳐야 한다.
 *
 * <p><b>과거는 다른 출처로 센다.</b> 같은 Open-Meteo지만 예보와 재분석은 성격이 다르다 —
 * 예보 격자가 ~1km인 데 비해 ERA5 재분석은 ~11km라 지점이 "그 동네"로 뭉개진다
 * (실측: 서현역 {@code 37.3851,127.1233} → {@code 37.434,127.101}). 한 이름으로 묶으면
 * 그 차이가 화면에서 사라진다.
 */
public enum WeatherSource {

    /**
     * 1순위. 유일하게 API 키가 필요하고, 무료 등급은 <b>하루 50회·5일까지</b>다.
     *
     * <p>5일을 넘는 기간은 아예 부르지 않는다({@code AccuWeatherClient.supports}) — 못 하는
     * 일을 시켜 실패를 쌓지 않는다. 그 기간은 Open-Meteo가 맡는다.
     */
    ACCU_WEATHER("AccuWeather", true),

    /**
     * 2순위. 키가 없어 한도에 걸리지 않고 예보가 16일까지다 — 받쳐 주는 자리에 맞다.
     *
     * <p>강수확률을 주므로 1순위가 죽어도 화면 표기가 낮아지지 않는다.
     */
    OPEN_METEO("Open-Meteo", true),

    /** 지난 날짜. 재분석 실측이라 예보가 아니고, 이중화 상대가 없다(다른 곳에는 아카이브가 없다). */
    OPEN_METEO_ARCHIVE("Open-Meteo Archive", false);

    private final String displayName;
    private final boolean forecast;

    WeatherSource(String displayName, boolean forecast) {
        this.displayName = displayName;
        this.forecast = forecast;
    }

    /** 텔레그램 메시지에 노출할 이름. */
    public String displayName() {
        return displayName;
    }

    /**
     * 예보인가, 지나간 날의 실측인가.
     *
     * <p>기준 줄의 꼬리가 여기서 갈린다 — {@code (예보)} 또는 {@code (실측)}.
     * {@code FxSource.intraday()}가 {@code (고시)}를 붙일지 정하는 것과 같은 자리다.
     */
    public boolean forecast() {
        return forecast;
    }
}
