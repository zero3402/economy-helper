package io.saiden.economyhelper.market.weather;


/**
 * 하늘 상태 — <b>두 출처의 말을 하나로 모은다.</b>
 *
 * <p>Open-Meteo는 WMO 코드(숫자)를 주고 AccuWeather는 아이콘 번호(1~44)를 준다.
 * 그대로 두면 폴백이 일어난 날에만 표기가 달라져, 값이 아니라 <b>우리 사정</b>이 화면에
 * 드러난다. 이중화는 상대가 죽었을 때 답이 나가게 하는 장치이지 사용자가 알아야 할 일이
 * 아니므로, 두 어휘를 여기서 같은 한국어로 모은다.
 *
 * <p><b>이모지를 쓰지 않는다.</b> 이 프로젝트는 등락률 한 곳만 예외로 두고 글로 적는다 —
 * 거기서 이모지를 쓰는 이유는 상승 빨강·하락 파랑이 관습이라 색 자체가 정보이기 때문이고,
 * 하늘 상태에는 그런 관습이 없다.
 *
 * <p><b>모르는 값은 지어내지 않는다.</b> 새 코드가 생기거나 출처가 어휘를 늘리면
 * {@link #UNKNOWN}으로 떨어지고 화면에는 그 줄만 빠진다 — 아무 날씨나 골라 찍는 것보다 낫다.
 */
public enum SkyCondition {

    CLEAR("맑음"),
    MOSTLY_CLEAR("대체로 맑음"),
    PARTLY_CLOUDY("구름 조금"),
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
