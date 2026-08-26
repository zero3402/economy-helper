package io.saiden.economyhelper.market.weather;

import java.time.LocalDate;

/**
 * 날씨 출처 하나.
 *
 * <p>구현체가 둘 이상인 곳에만 인터페이스를 둔다는 규칙에 맞는다 — AccuWeather와 Open-Meteo가
 * 같은 자리를 번갈아 채운다({@code FxRateClient}와 같은 모양이다).
 *
 * <p><b>실패를 값으로 돌려주지 않는다.</b> {@link WeatherService}가 "이 출처가 죽었다"와
 * "이 지점에 값이 없다"를 예외로 가르기 때문이다. 조용히 빈 결과를 돌려주면 폴백이 일어나지
 * 않고 사용자는 이유 없이 빈손을 받는다.
 */
public interface WeatherClient {

    /** 이 클라이언트가 대표하는 출처. 로그와 화면의 출처 줄에 쓴다. */
    WeatherSource source();

    /**
     * 이 지점의 일일 날씨.
     *
     * <p>요청한 범위를 <b>다 못 줄 수는 있다</b> — 출처마다 예보 길이가 달라 뒷부분이 빌 수
     * 있다. 그건 실패가 아니므로 받은 만큼 담아 돌려준다. 화면에는 실제 날짜가 적히므로
     * 어디까지인지 사용자가 본다.
     *
     * @throws RuntimeException 조회 실패. 서킷브레이커가 이걸 세고 {@link WeatherService}가 폴백한다
     */
    Weather forecast(GeoLocation place, WeatherPeriod period);

    /**
     * 이 출처가 <b>그 지점의</b> 그 범위를 다룰 수 있는가.
     *
     * <p><b>지난 날짜와 예보 길이, 그리고 지점에서 갈린다.</b> 예보 출처는 아카이브가 없어
     * 과거를 물으면 호출해 봐야 빈손이고, AccuWeather 무료 등급은 5일을 넘기면 그렇다.
     * 못 하는 줄 알면서 부르면 서킷브레이커에 애먼 실패가 쌓이고 사용자는 그만큼 더 기다린다.
     *
     * <p><b>지점이 세 번째 축으로 붙었다.</b> 기상청은 <b>국내 격자 전용</b>이라 파리·도쿄를
     * 물으면 줄 것이 없다 — 그런데 좌표가 이 자리에 없어 「국내만 맡는다」를 말할 방법이 없었다.
     * 대안은 {@link #forecast}에서 던지는 것이었는데, 그러면 국외 조회마다 기상청 브레이커를
     * 태운다 — 이 메서드가 존재하는 이유를 정면으로 어긴다.
     *
     * <p>⚠️ <b>나라 이름으로 가르지 않는다.</b> 오전 6시 알람은 설정 좌표로
     * {@code GeoLocation}을 직접 만들어 지오코딩을 안 타므로 나라가 {@code null}이다.
     * 출처가 <b>제 격자 안인지</b>를 스스로 보는 것이 더 정직하고 그 경로에서도 통한다.
     */
    boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today);

    /**
     * 이 출처가 <b>하루 안의 강수 시각까지 함께 주는가</b>.
     *
     * <p>{@code true}면 {@code WeatherService}가 보충 호출을 아예 안 한다 — 이미 손에 있는 것을
     * 다시 묻지 않는다.
     *
     * <p>⚠️ <b>결과로 넘겨짚을 수 없는 값이다.</b> 「토막이 하나도 없다」는 <b>마른 날</b>과
     * <b>아직 안 받은 날</b> 둘 다에 참이라, 받은 것을 보고는 두 경우를 가를 수 없다.
     * 잘못 가르면 이미 손에 있는 것을 같은 파라미터로 한 번 더 묻게 되고, 화면은 어느 쪽이든
     * 같아서 드러나지도 않는다.
     *
     * <p>그래서 <b>출처가 스스로 밝힌다</b> — 「무엇을 받았나」가 아니라 「무엇을 주는 곳인가」다.
     * {@link #supports}가 「못 하는 출처는 부르지 않는다」를 정하는 것과 한 짝이다.
     *
     * @return 기본은 {@code false} — 밝히지 않은 출처는 못 주는 것으로 본다. 잘못 넘겨짚어
     *         시각 줄을 잃는 것보다 헛호출 한 번이 낫다
     */
    default boolean providesPrecipitationHours() {
        return false;
    }
}
