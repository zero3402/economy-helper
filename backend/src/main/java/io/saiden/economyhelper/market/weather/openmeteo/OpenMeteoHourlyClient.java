package io.saiden.economyhelper.market.weather.openmeteo;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 강수 시각만 묻는다 — <b>1순위가 시간 단위를 못 줄 때의 보충</b>이다.
 *
 * <p><b>왜 따로 있나.</b> 1순위 AccuWeather는 하루를 <b>낮/밤 두 칸</b>으로만 준다(실측:
 * {@code Day}/{@code Night}에 {@code IconPhrase}·{@code HoursOfPrecipitation}까지 오지만 시각은
 * 없다). 그래서 「오후 1시~7시」를 만들 수 없다. 12시간별 엔드포인트가 무료 티어에 있긴 하나
 * <b>12시간뿐</b>이라 {@code 내일 서현}·{@code 일주일치 파리}를 못 덮고, 조회당 호출이 하나 늘어
 * <b>하루 50회 예산이 반</b>이 된다 — 그 예산은 30일 지점키 캐시까지 만들어 지켜 온 것이다.
 *
 * <p>Open-Meteo는 <b>키도 한도도 없고</b> 기간 전체를 시간별로 준다. 그래서 시각만 이쪽에 묻는다.
 *
 * <p>⚠️ <b>이것은 폴백이 아니라 보충이다.</b> 그래서 규칙이 하나 다르다 — 실패를 <b>삼킨다.</b>
 * 일별은 이미 손에 있으므로 여기서 예외를 올리면 <b>답이 통째로 죽는다.</b> 「실패를 삼키지
 * 않는다」는 {@code WeatherClient}의 규칙이고 그건 <b>폴백 상대</b>의 이야기다. 그래서 이 클래스는
 * {@code WeatherClient}를 구현하지 않는다 — 그 계약을 따를 수 없기 때문이다.
 *
 * <p>⚠️ <b>{@code weather} 캐시를 쓰지 않는다.</b> 그쪽은 {@code TypeReference<Weather>}로 못
 * 박혀 있어 다른 타입을 담으면 <b>쓸 때는 넘어가고 읽을 때 깨진다</b>(지수를 종목 캐시에 담았던
 * 그 사고와 같다). 「캐시 이름 하나에 타입 하나」 규칙대로 제 이름을 쓴다.
 */
@Component
public class OpenMeteoHourlyClient {

    /**
     * 확률과 양과 종류. 일별은 안 받는다 — 이 클래스가 필요한 것은 시각뿐이고,
     * 안 쓸 값을 받아 오면 응답만 무거워진다.
     */
    private static final String HOURLY_FIELDS = "precipitation_probability,precipitation,weather_code";

    private final RestClient restClient;

    public OpenMeteoHourlyClient(RestClient.Builder builder,
                                 @Value("${economy-helper.weather.open-meteo.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * @return 날짜별 강수 토막. <b>실패하면 빈 map</b> — 화면에서 그 줄만 빠진다
     */
    @Cacheable(cacheNames = CacheNames.PRECIPITATION_HOURS,
            key = "#a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()")
    // ⚠️ 브레이커·재시도 이름을 예보와 나눈다 — fmpOutlook을 시세와 가른 것과 같은 자리다.
    //    보충은 AccuWeather가 답할 때마다 불리고 알람은 지역 넷을 겹쳐 물으므로 창을 이쪽이
    //    거의 다 채운다. 한 이름이면 그 실패가 쌓여 열리는 순간 **2순위 폴백까지 함께 막히고**,
    //    그때 AccuWeather가 한도를 넘긴 날은 날씨가 통째로 빈손이 된다
    @Retry(name = "weatherOpenMeteoHourly")
    @CircuitBreaker(name = "weatherOpenMeteoHourly")
    public Map<LocalDate, List<HalfDay>> halves(GeoLocation place, WeatherPeriod period) {
        return OpenMeteoRequest.hourly(restClient, "/v1/forecast", HOURLY_FIELDS, place, period);
    }
}
