package io.saiden.economyhelper.market.weather.openmeteo;

import io.saiden.economyhelper.config.CacheNames;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherClient;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 지나간 날 — Open-Meteo 재분석(ERA5).
 *
 * <p><b>예보 클라이언트와 굳이 나눈 이유가 있다.</b> 호스트가 다르기도 하지만, 무엇보다
 * <b>격자가 다르다</b> — 예보가 ~1km인 데 비해 재분석은 ~11km라 지점이 뭉개진다(실측:
 * 서현역 {@code 37.3851,127.1233}을 물었더니 {@code 37.434,127.101}로 답했다). 한 클래스로
 * 묶어 같은 출처 이름을 달면 그 차이가 화면에서 사라진다.
 *
 * <p><b>이중화 상대가 없다.</b> AccuWeather 무료 등급에는 아카이브가 없다. 여기가 죽으면 지난
 * 날짜는 답하지 못하고, 그때는 지어내지 않고 못 찾았다고 답한다.
 *
 * <p>강수는 확률이 아니라 <b>실제로 온 양</b>이다 — 지나간 날에 "올 확률"은 말이 되지 않는다.
 */
@Component
public class OpenMeteoArchiveClient implements WeatherClient {

    private static final String DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum";

    private final RestClient restClient;

    public OpenMeteoArchiveClient(
            RestClient.Builder builder,
            @Value("${economy-helper.weather.open-meteo.archive-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * 하루 안의 강수 시각. <b>일일 값과 한 응답으로 온다</b> — 호출이 늘지 않는다.
     * 지나간 날은 확률이라는 개념이 없어 실제로 온 양만 받는다.
     */
    private static final String HOURLY_FIELDS = "precipitation,weather_code";

    @Override
    public WeatherSource source() {
        return WeatherSource.OPEN_METEO_ARCHIVE;
    }

    /** 지나간 날만 맡는다 — 오늘을 걸치면 예보 쪽이 답해야 격자가 섞이지 않는다. */
    @Override
    public boolean supports(WeatherPeriod period, LocalDate today) {
        return period.past(today);
    }

    @Override
    // ⚠️ 예보와 한 캐시를 쓰므로 접두사로 가른다 — OpenMeteoForecastClient의 주석 참조
    @Cacheable(cacheNames = CacheNames.WEATHER,
            key = "'oma:' + #a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()")
    @Retry(name = "weatherOpenMeteoArchive")
    @CircuitBreaker(name = "weatherOpenMeteoArchive")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        return OpenMeteoRequest.daily(restClient, "/v1/archive", DAILY_FIELDS, HOURLY_FIELDS,
                place, period, source());
    }
}
