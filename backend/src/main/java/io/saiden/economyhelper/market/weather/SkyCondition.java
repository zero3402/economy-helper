package io.saiden.economyhelper.market.weather;


/**
 * 하늘 상태 — <b>두 출처의 말을 하나로 모은다.</b>
 *
 * <p>Open-Meteo는 WMO 코드(숫자)를 주고 AccuWeather는 아이콘 번호(1~44)를 준다.
 * 그대로 두면 폴백이 일어난 날에만 표기가 달라져, 값이 아니라 <b>우리 사정</b>이 화면에
 * 드러난다. 이중화는 상대가 죽었을 때 답이 나가게 하는 장치이지 사용자가 알아야 할 일이
 * 아니므로, 두 어휘를 여기서 같은 한국어로 모은다.
 *
 * <p>⚠️ <b>강수 시각 줄만 예외로 그림을 든다</b>(☔/❄️/🌨️/⛈️) — 그 표는 이 열거형이 아니라
 * {@code WeatherFormatter}에 있다. 하늘 상태 어휘는 여기서 깨끗하게 남기고, 「눈에 우산을
 * 붙이지 않는다」는 판단만 화면 쪽이 든다.
 *
 * <p><b>이모지를 쓰지 않는다.</b> 이 프로젝트는 등락률 한 곳만 예외로 두고 글로 적는다 —
 * 거기서 이모지를 쓰는 이유는 상승 빨강·하락 파랑이 관습이라 색 자체가 정보이기 때문이고,
 * 하늘 상태에는 그런 관습이 없다.
 *
 * <p><b>모르는 값은 지어내지 않는다.</b> 새 코드가 생기거나 출처가 어휘를 늘리면
 * {@link #UNKNOWN}으로 떨어지고 화면에는 그 줄만 빠진다 — 아무 날씨나 골라 찍는 것보다 낫다.
 *
 * <p>⚠️ <b>선언 순서가 곧 심각도다</b> — {@code WeatherSource}에서 「선언 순서가 곧 이중화
 * 순서」인 것과 같은 자리이고, 여기는 그것을 적지 않고 있었다. 두 곳이 {@code compareTo}로
 * 이 순서를 읽는다: {@code Weather.Daily.skyAgreeingWith}가 요약을 반나절 쪽으로 <b>올릴</b> 때
 * {@code max}를 쓰고, {@code HalfDays.skyOf}가 같은 횟수일 때 뒤쪽을 고른다.
 *
 * <p><b>지켜야 하는 것은 하나다 — 강수({@link #precipitating})는 전부 비강수 뒤에 온다.</b>
 * 안 지키면 조용히 뒤집힌다: 비강수 어휘 하나를 맨 끝에 더하면 반나절이 「비」라고 말하는 날
 * {@code max}가 그것을 집어 요약이 <b>「맑음인데 종일 비」</b>가 된다 — {@code skyAgreeingWith}가
 * 막으려고 있는 바로 그 모순이다. {@link #precipitating}은 {@code default}가 없어 새 값이
 * 컴파일을 멈추지만, <b>순서는 어디에 넣어도 컴파일이 된다.</b>
 * 그래서 {@code SkyConditionTest.precipitatingSortsAboveDry}가 그 그물이다.
 */
public enum SkyCondition {

    CLEAR("맑음"),
    MOSTLY_CLEAR("대체로 맑음"),
    PARTLY_CLOUDY("구름 조금"),
    /**
     * ⚠️ <b>기상청 때문에 생긴 값이다.</b> 기상청 {@code SKY}는 하늘을 셋으로만 가르는데
     * (맑음 {@code 1} · 구름많음 {@code 3} · 흐림 {@code 4}) 가운데 것이 우리 어휘에 없었다.
     * {@code PARTLY_CLOUDY}(「구름 조금」)에 붙이면 <b>「많음」을 「조금」이라 적는</b> 셈이고,
     * {@code CLOUDY}(「흐림」)에 붙이면 흐린 날과 구분이 사라진다 — 둘 다 사실을 바꾼다.
     *
     * <p>WMO에는 대응이 없다({@code 2}가 「구름 조금」, {@code 3}이 「흐림」이다).
     * 그래서 <b>기상청이 답한 날에만</b> 나온다.
     */
    MOSTLY_CLOUDY("구름 많음"),
    CLOUDY("흐림"),
    FOG("안개"),
    DRIZZLE("이슬비"),
    RAIN("비"),
    FREEZING_RAIN("어는 비"),
    SHOWERS("소나기"),
    SNOW("눈"),
    SLEET("진눈깨비"),
    SNOW_SHOWERS("소낙눈"),
    THUNDERSTORM("뇌우"),
    HAIL_THUNDERSTORM("우박 동반 뇌우"),

    /** 어느 출처에서도 해석하지 못한 값. 화면에서는 이 줄을 비운다. */
    UNKNOWN(null);

    private final String label;

    SkyCondition(String label) {
        this.label = label;
    }

    /** 화면에 쓸 한국어. {@link #UNKNOWN}이면 {@code null}이고 호출자가 줄을 뺀다. */
    public String label() {
        return label;
    }

    public boolean known() {
        return label != null;
    }

    /**
     * <b>하늘에서 무언가 떨어지는가.</b>
     *
     * <p>{@code HalfDays}가 이것으로 「젖은 시간」을 가르고 「젖은 반나절의 이름」을 고른다.
     * 없던 시절에는 그 둘이 <b>확률과 강수량만</b> 보고 정해졌는데, 그 둘은 무엇이 오는지를
     * 말해 주지 않는다 — 실측(2026-08-25 미금역)에서 10~12시가 <b>강수확률 88~100%인데
     * 코드는 {@code 1}(대체로 맑음)이고 강수량은 {@code 0.0mm}</b>였다. 그 시간이 젖은 것으로
     * 잡혀 화면에 <b>{@code ☁️ 오전 8시~11시 흐림 (최대 100%)}</b>가 찍혔고, 코드가 {@code 0}인
     * 날이면 그대로 <b>「맑음」</b>이 된다 — 하루 요약이 「뇌우」인 날 바로 아래에서.
     *
     * <p><b>{@link #UNKNOWN}은 「아니다」가 아니라 「모른다」</b>라서 여기서 {@code false}지만,
     * 호출자는 그 둘을 갈라 써야 한다 — 모르는 것을 마른 것으로 취급하면 해석 못 한 코드 하나에
     * 강수 시각이 통째로 사라진다({@code HalfDays.saysDry} 참고).
     *
     * <p>{@code default}를 두지 않는다. 어휘가 늘면 컴파일이 멈춰 <b>새 값이 비인지 아닌지</b>를
     * 사람이 한 번 정하게 만든다.
     */
    public boolean precipitating() {
        return switch (this) {
            case DRIZZLE, RAIN, FREEZING_RAIN, SHOWERS, SNOW, SLEET, SNOW_SHOWERS,
                    THUNDERSTORM, HAIL_THUNDERSTORM -> true;
            case CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, MOSTLY_CLOUDY, CLOUDY, FOG,
                    UNKNOWN -> false;
        };
    }

    /**
     * WMO 4677 날씨 코드 → 하늘 상태 (Open-Meteo).
     *
     * <p>코드 하나하나가 아니라 <b>묶음으로</b> 옮긴다. WMO는 강도를 코드로 가르지만
     * ({@code 61} 약한 비 · {@code 63} 비 · {@code 65} 강한 비) 하루치 요약에서 그 셋을
     * 갈라 봐야 읽는 사람이 할 일이 달라지지 않는다 — 최저·최고 기온과 강수확률이 이미
     * 그 자리를 채운다.
     */
    public static SkyCondition ofWmoCode(Integer code) {
        if (code == null) {
            return UNKNOWN;
        }
        return switch (code) {
            case 0 -> CLEAR;
            case 1 -> MOSTLY_CLEAR;
            case 2 -> PARTLY_CLOUDY;
            case 3 -> CLOUDY;
            case 45, 48 -> FOG;
            case 51, 53, 55 -> DRIZZLE;
            case 56, 57, 66, 67 -> FREEZING_RAIN;
            case 61, 63, 65 -> RAIN;
            case 71, 73, 75, 77 -> SNOW;
            case 80, 81, 82 -> SHOWERS;
            case 85, 86 -> SNOW_SHOWERS;
            case 95 -> THUNDERSTORM;
            case 96, 99 -> HAIL_THUNDERSTORM;
            default -> UNKNOWN;
        };
    }

    /**
     * AccuWeather {@code Icon}(1~44) → 하늘 상태.
     *
     * <p><b>낮/밤 쌍을 같은 것으로 본다.</b> AccuWeather는 같은 날씨에 낮과 밤 아이콘을 따로
     * 준다({@code 1 Sunny} / {@code 33 Clear}). 하루치 요약에는 그 구분이 의미가 없다 —
     * 하루를 한 줄로 요약하는 이 통에서는 낮 아이콘 하나면 된다.
     *
     * <p><b>구름 낀 소나기·뇌우도 소나기·뇌우다.</b> {@code 13 Mostly cloudy w/ showers}에서
     * 사용자에게 중요한 것은 구름이 아니라 비다. WMO 쪽에서 강도를 묶은 것과 같은 이유로,
     * 그래야 두 출처가 정말 같은 말로 나온다.
     *
     * <p>{@code 30 Hot}·{@code 31 Cold}·{@code 32 Windy}는 하늘 상태가 아니라 체감이라
     * {@link #UNKNOWN}으로 둔다 — 그 줄만 빠진다. 없는 값을 지어내지 않는다.
     */
    public static SkyCondition ofAccuWeatherIcon(Integer icon) {
        if (icon == null) {
            return UNKNOWN;
        }
        return switch (icon) {
            case 1, 33 -> CLEAR;
            case 2, 34 -> MOSTLY_CLEAR;
            case 3, 4, 5, 35, 36, 37 -> PARTLY_CLOUDY;
            case 6, 7, 8, 38 -> CLOUDY;
            case 11 -> FOG;
            case 12, 13, 14, 39, 40 -> SHOWERS;
            case 15, 16, 17, 41, 42 -> THUNDERSTORM;
            case 18 -> RAIN;
            case 19, 20, 21, 43 -> SNOW_SHOWERS;
            case 22, 23, 44 -> SNOW;
            case 24, 26 -> FREEZING_RAIN;
            case 25, 29 -> SLEET;
            default -> UNKNOWN;
        };
    }
}
