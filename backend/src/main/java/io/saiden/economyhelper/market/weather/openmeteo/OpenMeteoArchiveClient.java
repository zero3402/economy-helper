package io.saiden.economyhelper.market.weather.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 * <p><b>이중화 상대가 없다.</b> met.no는 예보만 주고 아카이브가 없다. 여기가 죽으면 지난
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
    @Cacheable(cacheNames = "weather",
            key = "#a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()",
            unless = "#result == null")
    @CircuitBreaker(name = "weatherOpenMeteoArchive")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        Archive response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/archive")
                        .queryParam("latitude", place.latitude())
                        .queryParam("longitude", place.longitude())
                        .queryParam("start_date", period.from())
                        .queryParam("end_date", period.to())
                        .queryParam("daily", DAILY_FIELDS)
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .body(Archive.class);

        if (response == null || response.daily() == null || response.daily().isEmpty()) {
            throw new IllegalStateException("Open-Meteo 재분석 응답에 일일 값이 없습니다");
        }
        return new Weather(place, response.daily().toDays(), source());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Archive(DailyBlock daily) {}
}
