package io.saiden.economyhelper.market.weather.metno;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherClient;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * met.no Locationforecast 2.0 — <b>2순위다.</b>
 *
 * <p>Open-Meteo가 죽었을 때 답이 나가게 하는 것이 이 클라이언트의 전부다. 인증이 없어
 * 시크릿이 늘지 않는다는 점이 1순위와 같다.
 *
 * <p>⚠️ <b>연락처가 든 {@code User-Agent}를 요구한다.</b> 없으면 403이다. met.no 이용 약관이
 * 문제가 생겼을 때 연락할 수단을 요구하기 때문이고, 저장소 주소가 그 역할을 한다.
 *
 * <p><b>시간별 값을 하루로 접는다.</b> 이 API는 일일 요약을 주지 않고 시계열만 준다
 * ({@code /complete}). 그래서 <b>그 지역의 달력</b>으로 묶어 최저·최고를 뽑는다 — KST로 묶으면
 * 남의 하루가 둘로 쪼개진다.
 *
 * <p><b>강수확률이 없다.</b> {@code probability_of_precipitation}은 북유럽(MEPS 모델) 안에서만
 * 오고, 2026-08-17에 성남 좌표로 {@code /complete}를 호출해 확인했다 — 없었다. 그래서 이
 * 출처가 답한 날은 화면 표기가 <b>강수량(mm)</b>으로 바뀐다. 없는 확률을 지어내지 않는다.
 *
 * <p>예보 길이도 짧다 — 실측으로 ~9일이다(1순위는 16일). 요청 범위를 다 못 채우면 받은
 * 만큼만 담는다. 화면에 실제 날짜가 적히므로 어디까지인지 사용자가 본다.
 */
@Component
public class MetNoClient implements WeatherClient {

    private final RestClient restClient;

    public MetNoClient(RestClient.Builder builder,
                       @Value("${economy-helper.weather.met-no.base-url}") String baseUrl,
                       @Value("${economy-helper.weather.met-no.user-agent}") String userAgent) {
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("User-Agent", userAgent).build();
    }

    @Override
    public WeatherSource source() {
        return WeatherSource.MET_NO;
    }

    /** 예보만 준다 — 아카이브가 없어 지나간 날은 호출해 봐야 빈손이다. */
    @Override
    public boolean supports(WeatherPeriod period, LocalDate today) {
        return !period.past(today);
    }

    @Override
    @Cacheable(cacheNames = "weather",
            key = "'metno:' + #a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()",
            unless = "#result == null")
    @RateLimiter(name = "weatherMetNo")
    @CircuitBreaker(name = "weatherMetNo")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        Forecast response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/weatherapi/locationforecast/2.0/complete")
                        .queryParam("lat", place.latitude())
                        .queryParam("lon", place.longitude())
                        .build())
                .retrieve()
                .body(Forecast.class);

        if (response == null || response.properties() == null
                || response.properties().timeseries() == null
                || response.properties().timeseries().isEmpty()) {
            throw new IllegalStateException("met.no 응답에 시계열이 없습니다");
        }

        List<Weather.Daily> days = fold(response.properties().timeseries(), place, period);
        if (days.isEmpty()) {
            // 요청한 날이 예보 범위 밖이라 한 건도 안 남았다. 빈 답을 내보내느니 던져서
            // 상위가 "못 찾음"으로 답하게 한다
            throw new IllegalStateException("met.no 예보에 요청한 날짜가 없습니다");
        }
        return new Weather(place, days, source());
    }

    /**
     * 시계열을 <b>그 지역의 하루</b>로 접는다.
     *
     * <p>요청 범위 밖의 날은 버린다 — 이 API는 늘 전 구간을 주므로 걸러야 물어본 만큼만 나간다.
     */
    private static List<Weather.Daily> fold(List<Entry> timeseries, GeoLocation place,
                                            WeatherPeriod period) {
        Map<LocalDate, Accumulator> byDate = new LinkedHashMap<>();
        for (Entry entry : timeseries) {
            if (entry.time() == null || entry.data() == null) {
                continue;
            }
            LocalDate date = Instant.parse(entry.time()).atZone(place.zone()).toLocalDate();
            if (date.isBefore(period.from()) || date.isAfter(period.to())) {
                continue;
            }
            byDate.computeIfAbsent(date, ignored -> new Accumulator()).add(entry);
        }

        List<Weather.Daily> days = new ArrayList<>(byDate.size());
        byDate.forEach((date, sum) -> days.add(sum.toDaily(date)));
        return List.copyOf(days);
    }

    /** 하루치를 쌓는 자리. 최저·최고는 훑으며 갱신하고 강수는 더한다. */
    private static final class Accumulator {
        private BigDecimal low;
        private BigDecimal high;
        private BigDecimal rain = BigDecimal.ZERO;
        private SkyCondition sky = SkyCondition.UNKNOWN;

        void add(Entry entry) {
            BigDecimal temperature = entry.temperature();
            if (temperature != null) {
                low = low == null || temperature.compareTo(low) < 0 ? temperature : low;
                high = high == null || temperature.compareTo(high) > 0 ? temperature : high;
            }
            // 1시간 값이 있으면 그것만 쓴다. 앞부분은 매시간, 뒷부분은 6시간 간격으로 오는데
            // 둘을 함께 더하면 앞부분이 두 번 세어진다
            Window window = entry.data().nextOneHour() != null
                    ? entry.data().nextOneHour()
                    : entry.data().nextSixHours();
            if (window != null) {
                if (window.details() != null && window.details().precipitationAmount() != null) {
                    rain = rain.add(window.details().precipitationAmount());
                }
                // 하늘 상태는 먼저 읽은 것을 지킨다 — 하루 안에서 바뀌는데 아무거나 덮어쓰면
                // 마지막(늦은 밤) 값이 그날을 대표하게 된다
                if (!sky.known() && window.summary() != null) {
                    sky = SkyCondition.ofSymbolCode(window.summary().symbolCode());
                }
            }
        }

        Weather.Daily toDaily(LocalDate date) {
            // 확률이 아니라 양이다 — met.no는 북유럽 밖에서 확률을 주지 않는다
            return Weather.Daily.withAmount(date, sky, low, high, rain);
        }
    }

    // --- Locationforecast 2.0 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Forecast(Properties properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Properties(List<Entry> timeseries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(String time, Data data) {

        /** 그 시각의 기온. 없을 수 있다. */
        BigDecimal temperature() {
            return data == null || data.instant() == null || data.instant().details() == null
                    ? null
                    : data.instant().details().airTemperature();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(Instantaneous instant,
                @JsonProperty("next_1_hours") Window nextOneHour,
                @JsonProperty("next_6_hours") Window nextSixHours) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Instantaneous(Details details) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Details(@JsonProperty("air_temperature") BigDecimal airTemperature) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Window(Summary summary, WindowDetails details) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Summary(@JsonProperty("symbol_code") String symbolCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WindowDetails(@JsonProperty("precipitation_amount") BigDecimal precipitationAmount) {}
}
