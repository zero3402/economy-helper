package io.saiden.economyhelper.market.weather.accu;

import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherClient;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AccuWeather 일일예보 — <b>이중화의 1순위</b>({@code WeatherService.ORDER}).
 *
 * <p>실제로 호출해 확인한 것들이다(2026-08-18, 미금역).
 *
 * <ul>
 *   <li><b>{@code details=true}가 없으면 강수확률이 안 온다.</b> 기본 응답에는
 *       {@code HasPrecipitation}까지만 있다 — 확률이 빠지면 화면이 강수량 mm로 강등된다
 *   <li><b>{@code metric=true}가 없으면 화씨다.</b> 응답의 {@code Unit}이 {@code F}로 온다
 *   <li>{@code Date}는 지점 현지 오프셋이 붙어 온다({@code 2026-08-18T07:00:00+09:00}) —
 *       우리 달력으로 다시 계산하지 않고 그 오프셋의 날짜를 그대로 쓴다
 *   <li>낮과 밤이 따로 온다. <b>낮을 쓴다</b> — 아침 알람에서 하루를 계획하는 값이 밤이 아니다
 * </ul>
 *
 * <p><b>1day가 아니라 5day를 부른다.</b> 호출 수는 같은데 1day는 언제나 오늘 하루뿐이라
 * '내일'을 못 준다. 5day를 받아 요청한 범위로 잘라 쓴다.
 *
 * <p><b>무료 등급은 하루 50회다.</b> 지점 키를 30일 캐시해({@link AccuLocationApi}) 조회당
 * 1회로 낮추는 것이 실질 방어이고, 한도를 넘기면 503이 와서 {@code WeatherService}가
 * Open-Meteo로 넘어간다. 그 실패는 서킷브레이커가 세므로 헛호출이 무한히 반복되지 않는다.
 */
@Component
public class AccuWeatherClient implements WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(AccuWeatherClient.class);

    /** 무료 등급의 일일예보 길이. 오늘을 포함해 닷새다. */
    private static final int MAX_DAYS = 5;

    private static final String PATH = "/forecasts/v1/daily/5day/";

    private final RestClient restClient;
    private final String apiKey;
    private final AccuLocationApi locations;

    public AccuWeatherClient(RestClient.Builder builder,
                             @Value("${economy-helper.weather.accu-weather.base-url}") String baseUrl,
                             @Value("${economy-helper.weather.accu-weather.api-key:}") String apiKey,
                             AccuLocationApi locations) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.locations = locations;
    }

    @Override
    public WeatherSource source() {
        return WeatherSource.ACCU_WEATHER;
    }

    /**
     * 지나간 날도, 닷새를 넘는 날도 맡지 않는다.
     *
     * <p>무료 등급이 5일까지다. 넘는 기간을 물어 봐야 뒷부분이 통째로 비는데, 그건 실패로도
     * 안 잡혀 조용히 짧은 답이 나간다 — 그럴 바에 부르지 않고 16일까지 주는 Open-Meteo에
     * 넘긴다. 하루 50회짜리 한도를 헛되이 쓰지 않는 효과도 있다.
     */
    @Override
    public boolean supports(WeatherPeriod period, LocalDate today) {
        return !period.past(today) && !period.to().isAfter(today.plusDays(MAX_DAYS - 1L));
    }

    @Override
    @Cacheable(cacheNames = CacheNames.WEATHER,
            key = "'accu:' + #a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()",
            unless = "#result == null")
    @CircuitBreaker(name = "weatherAccuWeather")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        if (apiKey.isBlank()) {
            // 부르기 전에 막는다 — 빈 키로 호출하면 한도만 축낸다
            throw new IllegalStateException("AccuWeather API 키가 없습니다");
        }
        String locationKey = locations.keyOf(place);
        Forecast response = request(locationKey, place);

        if (response == null || response.dailyForecasts() == null
                || response.dailyForecasts().isEmpty()) {
            // 던져야 WeatherService가 다음 출처로 넘어간다 — 빈 값을 돌려주면 폴백이 안 일어난다
            throw new IllegalStateException("AccuWeather 응답에 일일 예보가 없습니다");
        }

        List<Weather.Daily> days = slice(response.dailyForecasts(), period);
        if (days.isEmpty()) {
            throw new IllegalStateException("AccuWeather 예보에 요청한 날짜가 없습니다");
        }
        return new Weather(place, days, source());
    }

    /** 받은 닷새 중 요청한 범위만 남긴다. {@code supports}가 걸러 주지만 응답이 짧을 수도 있다. */
    private static List<Weather.Daily> slice(List<Daily> forecasts, WeatherPeriod period) {
        List<Weather.Daily> days = new ArrayList<>(forecasts.size());
        for (Daily daily : forecasts) {
            LocalDate date = daily.localDate();
            if (date == null || date.isBefore(period.from()) || date.isAfter(period.to())) {
                continue;
            }
            // ⚠️ 기온이 없으면 그 날은 값이 아니다. 예전에는 그대로 담아 화면에 '-°C / -°C'가
            //    성공으로 찍혔고, 성공이니 Open-Meteo 폴백도 돌지 않았다 — 「코스피 0」과 같은 부류다
            if (daily.low() == null || daily.high() == null) {
                log.warn("[weather] AccuWeather가 {}의 기온을 주지 않았습니다 — 다음 출처로 넘깁니다", date);
                continue;
            }
            // 확률이 없으면 강수 줄이 빠진다 — 그게 맞다. 이 응답에는 대신 쓸 강수량이 아예
            // 없어서(5일 예보는 확률만 준다) 없는 값을 만들어 낼 수가 없다. Open-Meteo가
            // 확률→강수량으로 내려가는 것(DailyBlock.toDays)과 갈리는 이유가 그것이다.
            // ⚠️ details=true를 빼면 확률이 통째로 안 오므로 이 자리가 매번 빈다 — request()의 주석 참조
            if (daily.precipitationChance() == null) {
                log.warn("[weather] AccuWeather가 {}의 강수확률을 주지 않았습니다 — 그 줄만 빠집니다", date);
            }
            days.add(Weather.Daily.withChance(date, SkyCondition.ofAccuWeatherIcon(daily.icon()),
                    daily.low(), daily.high(), daily.precipitationChance()));
        }
        return List.copyOf(days);
    }

    private Forecast request(String locationKey, GeoLocation place) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH + locationKey)
                            .queryParam("apikey", apiKey)
                            // 확률과 섭씨는 각각 이 둘이 있어야 온다
                            .queryParam("details", true)
                            .queryParam("metric", true)
                            .build())
                    .retrieve()
                    .body(Forecast.class);
        } catch (RuntimeException e) {
            // 예외 메시지에 apikey가 박힌 URL이 들어 있다 — 그대로 흘리면 키가 유출된다
            log.warn("[accu] {} 예보 조회 실패: {}", place.name(), AccuFailure.reasonOf(e));
            throw new IllegalStateException(
                    "AccuWeather 조회 실패 (" + place.name() + "): " + AccuFailure.reasonOf(e));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Forecast(@JsonProperty("DailyForecasts") List<Daily> dailyForecasts) {}

    /**
     * 하루치. 최저·최고는 바깥에, 하늘 상태와 강수확률은 {@code Day} 안에 있다.
     *
     * @param date 지점 현지 오프셋이 붙은 시각 — {@code 2026-08-18T07:00:00+09:00}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Daily(@JsonProperty("Date") String date,
                 @JsonProperty("Temperature") Range temperature,
                 @JsonProperty("Day") Half day) {

        /** 오프셋이 이미 그 지점의 것이라 그대로 자른다 — 우리 달력으로 다시 계산하지 않는다. */
        LocalDate localDate() {
            return date == null ? null : OffsetDateTime.parse(date).toLocalDate();
        }

        BigDecimal low() {
            return temperature == null ? null : Measure.valueOf(temperature.minimum());
        }

        BigDecimal high() {
            return temperature == null ? null : Measure.valueOf(temperature.maximum());
        }

        Integer icon() {
            return day == null ? null : day.icon();
        }

        Integer precipitationChance() {
            return day == null ? null : day.precipitationProbability();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Range(@JsonProperty("Minimum") Measure minimum,
                 @JsonProperty("Maximum") Measure maximum) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Measure(@JsonProperty("Value") BigDecimal value) {

        static BigDecimal valueOf(Measure measure) {
            return measure == null ? null : measure.value();
        }
    }

    /** 낮 또는 밤. 우리는 낮만 읽는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Half(@JsonProperty("Icon") Integer icon,
                @JsonProperty("PrecipitationProbability") Integer precipitationProbability) {}
}
