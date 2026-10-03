package io.saiden.economyhelper.weather.application.port.out;

import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.HalfDay;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 강수 시각 보충 — 1순위가 시간 단위를 못 줄 때 반나절 토막만 따로 받는다.
 *
 * <p><b>{@link WeatherClient}가 아니다.</b> 그 계약은 실패를 던지라고 요구하지만 보충은 폴백
 * 순서에 서지 않고, 실패를 삼키는 것은 호출자({@code WeatherService})의 몫이다.
 */
public interface HourlyPrecipitationClient {

    /** @return 날짜별 강수 토막. 실패는 예외로 올린다 */
    Map<LocalDate, List<HalfDay>> halves(GeoLocation place, WeatherPeriod period);
}
