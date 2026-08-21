package io.saiden.economyhelper.market.weather.accu;

import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.market.weather.GeoLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 좌표 → AccuWeather 지점 키.
 *
 * <p><b>AccuWeather는 위경도로 바로 예보를 주지 않는다.</b> 먼저 이 API로 지점 키를 받고 그
 * 키로 예보를 부른다 — 즉 <b>조회 한 번에 호출이 두 번</b>이다. 무료 등급이 하루 50회뿐이라
 * 그대로 두면 아침 알람(네 지점)만으로 하루 8회를 쓴다.
 *
 * <p>그래서 <b>30일 캐시한다.</b> 좌표에 대응하는 지점 키는 낡지 않는다 — 지오코딩 결과를
 * 30일 잡아 두는 것과 같은 이유이고({@code geocode}), 캐시가 더워지면 조회당 1회로 내려간다.
 *
 * <p>실측(2026-08-18): 미금역 {@code 37.35,127.10889} → 키 {@code 2331758}(구미1동).
 * 동 단위까지 짚어 준다.
 *
 * <p><b>키가 쿼리 파라미터에 실린다.</b> {@code FmpApi}·{@code KeximFxClient}와 같은 규칙을
 * 따른다 — RestClient 예외를 그대로 흘리지 않고 URL 없는 자체 예외로 바꿔 던진다.
 */
@Component
public class AccuLocationApi {

    private static final Logger log = LoggerFactory.getLogger(AccuLocationApi.class);

    private static final String PATH = "/locations/v1/cities/geoposition/search";

    private final RestClient restClient;
    private final String apiKey;

    public AccuLocationApi(RestClient.Builder builder,
                           @Value("${economy-helper.weather.accu-weather.base-url}") String baseUrl,
                           @Value("${economy-helper.weather.accu-weather.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    /**
     * @return 이 좌표의 지점 키
     * @throws IllegalStateException 키가 없거나, 호출이 실패했거나, 짚어 준 지점이 없을 때.
     *                               던져야 {@code WeatherService}가 다음 출처로 넘어간다
     */
    @Cacheable(cacheNames = CacheNames.ACCU_LOCATION,
            key = "#a0.latitude() + ',' + #a0.longitude()")
    @CircuitBreaker(name = "weatherAccuWeather")
    public String keyOf(GeoLocation place) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("AccuWeather API 키가 없습니다");
        }
        Place found = request(place);
        if (found == null || found.key() == null || found.key().isBlank()) {
            throw new IllegalStateException(
                    "AccuWeather에 해당 좌표의 지점이 없습니다: " + place.name());
        }
        log.debug("[accu] {} → 지점 {}({})", place.name(), found.key(), found.localizedName());
        return found.key();
    }

    private Place request(GeoLocation place) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH)
                            .queryParam("apikey", apiKey)
                            .queryParam("q", place.latitude() + "," + place.longitude())
                            .build())
                    .retrieve()
                    .body(Place.class);
        } catch (RuntimeException e) {
            // 원래 예외 메시지에는 apikey가 박힌 URL이 들어간다 — 그대로 흘리면 키가 유출된다
            log.warn("[accu] {} 지점 조회 실패: {}", place.name(), AccuFailure.reasonOf(e));
            throw new IllegalStateException(
                    "AccuWeather 지점 조회 실패 (" + place.name() + "): " + AccuFailure.reasonOf(e));
        }
    }

    /**
     * 필요한 것만 받는다. 응답에는 {@code Country}·{@code TimeZone}·{@code Rank} 등이 더 오지만
     * 표준 시간대는 이미 {@code GeoLocation}이 들고 있다.
     *
     * @param key           예보를 부를 때 쓰는 지점 키
     * @param localizedName {@code 구미1동} — 로그로 어디를 짚었는지 확인하는 용도다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Place(@com.fasterxml.jackson.annotation.JsonProperty("Key") String key,
                 @com.fasterxml.jackson.annotation.JsonProperty("LocalizedName") String localizedName) {}
}
