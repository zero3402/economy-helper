package io.saiden.economyhelper.market.weather.kma;

import io.saiden.economyhelper.market.weather.SkyCondition;

/**
 * 기상청 어휘 → 우리 어휘. <b>단기예보는 코드로, 중기예보는 한글 문장으로</b> 온다.
 *
 * <p>{@code SkyCondition.ofWmoCode}와 같은 자리다 — 옮기는 일은 그 코드를 아는 쪽,
 * 곧 출처의 몫이다. {@code HalfDays}는 어느 출처의 코드도 몰라야 한다.
 *
 * <p>⚠️ <b>어휘가 준다 — 그것은 우리가 잃는 것이 아니라 그 출처가 말하지 않는 것이다.</b>
 * 기상청 단기예보에는 <b>이슬비·뇌우·안개가 없다</b>({@code SKY} 셋 × {@code PTY} 다섯뿐).
 * 그래서 국내를 기상청이 맡은 날에는 「뇌우」나 「안개」가 화면에 안 나온다 — 버그가 아니다.
 * 지어내지 않는 것이 규칙이고, 없는 것은 없는 대로 둔다.
 */
final class KmaSky {

    private KmaSky() {
    }

    /**
     * 단기예보 — {@code SKY}(하늘상태)와 {@code PTY}(강수형태)를 함께 읽는다.
     *
     * <p><b>{@code PTY}가 먼저다.</b> 비가 오는 시간의 {@code SKY}는 대개 「흐림」인데
     * 그것으로 부르면 <b>젖은 줄의 이름이 「흐림」</b>이 된다 — {@code HalfDays}가 막으려고
     * 있는 바로 그 모순이다.
     *
     * <p>{@code 5}·{@code 6}·{@code 7}은 초단기예보의 값이라 단기예보에는 오지 않지만
     * 함께 읽는다 — 출처가 어휘를 늘리는 날 조용히 {@link SkyCondition#UNKNOWN}이 되는 것보다
     * 낫고, 값의 뜻은 문서에 못 박혀 있다.
     *
     * @return 못 읽으면 {@link SkyCondition#UNKNOWN} — 「모른다」는 거부권이 없다
     */
    static SkyCondition of(String sky, String precipitationType) {
        SkyCondition falling = fallingOf(precipitationType);
        return falling != null ? falling : cloudOf(sky);
    }

    /** @return 강수가 아니면 {@code null} — 그때는 하늘을 읽는다 */
    private static SkyCondition fallingOf(String precipitationType) {
        return switch (trimmed(precipitationType)) {
            case "1" -> SkyCondition.RAIN;
            case "2", "6" -> SkyCondition.SLEET;          // 비/눈 · 빗방울눈날림
            case "3", "7" -> SkyCondition.SNOW;           // 눈 · 눈날림
            case "4" -> SkyCondition.SHOWERS;
            case "5" -> SkyCondition.DRIZZLE;             // 빗방울(초단기)
            default -> null;                              // "0"(없음)과 못 읽은 값
        };
    }

    private static SkyCondition cloudOf(String sky) {
        return switch (trimmed(sky)) {
            case "1" -> SkyCondition.CLEAR;
            case "3" -> SkyCondition.MOSTLY_CLOUDY;
            case "4" -> SkyCondition.CLOUDY;
            default -> SkyCondition.UNKNOWN;
        };
    }


    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
