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
 * Open-Meteo 예보 — <b>1순위다.</b>
 *
 * <p>인증이 없고 IP 제한도 없어 배포 환경에서 그대로 돈다 — Frankfurter를 고른 것과 같은
 * 이유다. 2026-08-17 실측에서 16일치가 강수확률까지 빠짐없이 왔다.
 *
 * <p><b>강수확률을 주는 유일한 경로다.</b> met.no는 북유럽 밖에서 확률을 주지 않고 재분석은
 * 지나간 날이라 확률이라는 개념이 없다. 그래서 평상시 답이 가장 쓸모 있으려면 이 클라이언트가
 * 1순위여야 한다.
 *
 * <p><b>{@code timezone=auto}로 부른다.</b> 일일 값이 <b>그 지점의 지역시</b>로 잘려야 한다 —
 * 부에노스아이레스를 KST로 자르면 남의 하루가 둘로 쪼개진다. 실측으로 나이로비가
 * {@code Africa/Nairobi}로 돌아오는 것을 확인했다.
 */
@Component
public class OpenMeteoForecastClient implements WeatherClient {

    /**
     * 한 번에 받을 항목. <b>일일 값만 받는다</b> — 현재 기온은 쓰지 않기로 했으므로 부르지도 않는다.
     * 안 쓸 값을 받아 오면 응답만 무거워지고, 언젠가 화면에 새어 나온다.
     */
    private static final String DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max";

    private final RestClient restClient;

    public OpenMeteoForecastClient(
            RestClient.Builder builder,
            @Value("${economy-helper.weather.open-meteo.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public WeatherSource source() {
        return WeatherSource.OPEN_METEO;
    }

    /**
     * 지나간 날은 다루지 않는다.
     *
     * <p>예보 API도 {@code past_days}로 최근 과거를 주긴 하지만, 그건 재분석이 아니라 그때의
     * 예보 이력이라 성격이 다르다. 지나간 날은 {@link OpenMeteoArchiveClient}가 실측으로 답한다.
     */
    @Override
    public boolean supports(WeatherPeriod period, LocalDate today) {
        return !period.past(today);
    }

    @Override
    @Cacheable(cacheNames = "weather",
            key = "#a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()",
            unless = "#result == null")
    @CircuitBreaker(name = "weatherOpenMeteo")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        Forecast response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/forecast")
                        .queryParam("latitude", place.latitude())
                        .queryParam("longitude", place.longitude())
                        .queryParam("start_date", period.from())
                        .queryParam("end_date", period.to())
                        .queryParam("daily", DAILY_FIELDS)
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .body(Forecast.class);

        if (response == null || response.daily() == null || response.daily().isEmpty()) {
            // 던져야 WeatherService가 다음 출처로 넘어간다 — 빈 값을 돌려주면 폴백이 안 일어난다
            throw new IllegalStateException("Open-Meteo 응답에 일일 예보가 없습니다");
        }
        return new Weather(place, response.daily().toDays(), source());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Forecast(DailyBlock daily) {}
}
