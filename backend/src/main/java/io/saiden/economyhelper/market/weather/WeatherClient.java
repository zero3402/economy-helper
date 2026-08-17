package io.saiden.economyhelper.market.weather;

import java.time.LocalDate;

/**
 * 날씨 출처 하나.
 *
 * <p>구현체가 둘 이상인 곳에만 인터페이스를 둔다는 규칙에 맞는다 — Open-Meteo와 met.no가
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
     * <p>요청한 범위를 <b>다 못 줄 수는 있다</b> — met.no는 ~9일까지라 16일치를 물으면 앞부분만
     * 온다. 그건 실패가 아니므로 받은 만큼 담아 돌려준다. 화면에는 실제 날짜가 적히므로
     * 어디까지인지 사용자가 본다.
     *
     * @throws RuntimeException 조회 실패. 서킷브레이커가 이걸 세고 {@link WeatherService}가 폴백한다
     */
    Weather forecast(GeoLocation place, WeatherPeriod period);

    /**
     * 이 출처가 그 범위를 다룰 수 있는가.
     *
     * <p><b>지난 날짜에서 갈린다.</b> met.no는 예보만 주고 아카이브가 없어서, 과거를 물으면
     * 호출해 봐야 빈손이다. 못 하는 줄 알면서 부르면 서킷브레이커에 애먼 실패가 쌓이고
     * 사용자는 그만큼 더 기다린다.
     */
    boolean supports(WeatherPeriod period, LocalDate today);
}
