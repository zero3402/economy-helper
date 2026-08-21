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
 * Open-Meteo 예보 — <b>2순위다.</b>
 *
 * <p>인증이 없고 IP 제한도 없어 배포 환경에서 그대로 돈다 — Frankfurter를 고른 것과 같은
 * 이유다. 2026-08-17 실측에서 16일치가 강수확률까지 빠짐없이 왔다.
 *
 * <p><b>2순위이자, 긴 기간의 유일한 경로다.</b> 키가 없어 한도에 안 걸리므로 1순위
 * (AccuWeather)가 죽거나 하루 50회를 소진해도 이쪽은 답한다 — 받쳐 주는 쪽이 제약이 적어야
 * 이중화가 성립한다. 예보가 16일까지라, AccuWeather 무료가 못 주는 닷새 밖은 여기만 맡는다.
 *
 * <p>강수확률을 주므로 폴백해도 화면 표기가 낮아지지 않는다.
 *
 * <p><b>{@code timezone=auto}로 부른다.</b> 일일 값이 <b>그 지점의 지역시</b>로 잘려야 한다 —
 * 부에노스아이레스를 KST로 자르면 남의 하루가 둘로 쪼개진다. 실측으로 나이로비가
 * {@code Africa/Nairobi}로 돌아오는 것을 확인했다.
 */
@Component
public class OpenMeteoForecastClient implements WeatherClient {

    /**
     * 한 번에 받을 항목. <b>현재 기온은 안 받는다</b> — 쓰지 않기로 했으므로 부르지도 않는다.
     * (시간별 강수는 받는다 — 하루 안의 시각이라 현재값이 아니다. {@code HOURLY_FIELDS} 참고.)
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

    /**
     * 하루 안의 강수 시각. <b>일일 값과 한 응답으로 온다</b> — 호출이 늘지 않는다.
     * 예보는 확률과 양을 함께 준다.
     */
    private static final String HOURLY_FIELDS = "precipitation_probability,precipitation,weather_code";

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
    // ⚠️ 접두사가 출처를 가른다. 재분석과 한 캐시(weather)를 쓰는데 키 모양이 같아서,
    //    안 붙이면 자정 경계에서 섞인다 — 23:57에 '오늘 예보'로 담긴 항목이 00:00 이후에는
    //    과거 조회가 되어, TTL(10분)이 끝나기까지 실측 자리에 예보값이 나간다
    @Cacheable(cacheNames = CacheNames.WEATHER,
            key = "'om:' + #a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()")
    @Retry(name = "weatherOpenMeteo")
    @CircuitBreaker(name = "weatherOpenMeteo")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        return OpenMeteoRequest.daily(restClient, "/v1/forecast", DAILY_FIELDS, HOURLY_FIELDS,
                place, period, source());
    }
}
