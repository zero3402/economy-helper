package io.saiden.economyhelper.market.weather.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.market.weather.GeoLocation;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 지명 → 좌표. <b>전 세계를 다룬다.</b>
 *
 * <p><b>여기가 이름을 확정한다.</b> LLM({@code WeatherResolver})은 사용자가 친 말을 지오코딩이
 * 찾을 만한 지명으로 옮기기만 하고, 그 지명이 실재하는지와 좌표가 무엇인지는 이 클래스가
 * 정한다 — LLM이 좌표를 지어내게 두지 않는 것이 요점이다({@code StockResolver}가 종목코드를
 * 시세 API에 다시 물어 환각을 거르는 것과 같은 구조).
 *
 * <p><b>{@code language=ko}로 부른다.</b> 지명과 나라가 한국어로 돌아온다 — 실측으로
 * {@code Buenos Aires}가 {@code 부에노스아이레스}/{@code 아르헨티나}로 왔다. 화면 표기가
 * 국내 종목·코인과 갈리지 않게 하는 것과 같은 판단이다.
 *
 * <p><b>못 찾은 것은 실패가 아니다.</b> 없는 지명을 물으면 빈 결과가 정상 응답이므로
 * {@link Optional#empty()}로 돌려준다 — 예외를 던지면 서킷브레이커에 애먼 실패가 쌓인다.
 * 상대가 죽어 응답 자체가 오지 않는 것만 예외다.
 *
 * <p><b>이중화 상대가 없다.</b> met.no에는 지명 검색이 없다. 다만 오전 6시 알람은 좌표가
 * 설정에 박혀 있어 이 경로를 아예 타지 않으므로, 여기가 죽어도 알람은 나간다.
 */
@Component
public class GeocodingApi {

    private static final Logger log = LoggerFactory.getLogger(GeocodingApi.class);

    private final RestClient restClient;

    public GeocodingApi(RestClient.Builder builder,
                        @Value("${economy-helper.weather.open-meteo.geocoding-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * @param query       찾을 지명. LLM이 다듬은 것이거나, LLM이 실패했으면 사용자 원문이다
     * @param countryCode ISO 3166-1 alpha-2. 같은 지명이 여러 나라에 있을 때 좁힌다
     *                    (실측: {@code Buenos Aires}가 아르헨티나·니카라과·파나마에 있다).
     *                    모르면 {@code null}
     * @return 1순위 후보. 못 찾으면 {@link Optional#empty()}
     */
    @Cacheable(cacheNames = "geocode", key = "#a0 + '|' + #a1", unless = "#result == null")
    @CircuitBreaker(name = "weatherGeocoding")
    public Optional<GeoLocation> find(String query, String countryCode) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Results response = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/search")
                            .queryParam("name", query)
                            // 여러 개를 받아 봐야 고를 기준이 없다. 지오코딩은 인구순으로 주고
                            // 그게 사람이 기대하는 순서다 — 1순위를 그대로 쓴다
                            .queryParam("count", 1)
                            .queryParam("language", "ko")
                            .queryParam("format", "json");
                    if (countryCode != null && !countryCode.isBlank()) {
                        uriBuilder.queryParam("countryCode", countryCode);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(Results.class);

        // results 키 자체가 없는 것이 "못 찾음"의 정상 응답이다
        if (response == null || response.results() == null || response.results().isEmpty()) {
            log.info("[weather] '{}'({})에 해당하는 지명을 찾지 못했습니다", query, countryCode);
            return Optional.empty();
        }
        return Optional.of(toLocation(response.results().get(0)));
    }

    /**
     * 시간대를 못 읽으면 UTC로 둔다.
     *
     * <p>지오코딩은 늘 {@code timezone}을 주지만, 없다고 KST로 채우면 <b>남의 하루를 우리
     * 달력으로 자르게 된다.</b> UTC는 적어도 어느 한쪽으로 치우치지 않는다.
     */
    private static GeoLocation toLocation(Place place) {
        ZoneId zone = place.timezone() == null ? ZoneId.of("UTC") : ZoneId.of(place.timezone());
        return new GeoLocation(place.name(), place.country(),
                place.latitude(), place.longitude(), zone);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Results(List<Place> results) {}

    /**
     * @param country {@code language=ko}면 한국어다({@code 아르헨티나}). 없을 수 있다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Place(String name, String country, double latitude, double longitude, String timezone) {}
}
