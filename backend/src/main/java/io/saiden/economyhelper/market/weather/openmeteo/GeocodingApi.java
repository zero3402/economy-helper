package io.saiden.economyhelper.market.weather.openmeteo;

import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.saiden.economyhelper.market.weather.GeoLocation;
import java.time.ZoneId;
import java.util.Comparator;
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
 * <p><b>이중화 상대가 없다.</b> AccuWeather 지점 검색은 지명이 아니라 좌표를 받는다. 다만 오전 6시 알람은 좌표가
 * 설정에 박혀 있어 이 경로를 아예 타지 않으므로, 여기가 죽어도 알람은 나간다.
 */
@Component
public class GeocodingApi {

    private static final Logger log = LoggerFactory.getLogger(GeocodingApi.class);

    /**
     * 한 번에 받아 볼 후보 수.
     *
     * <p>열이면 충분하다 — 실측에서 가장 나빴던 경우(경기도 성남시가 9번째)를 덮는다.
     * 응답이 몇 KB라 늘려도 비용이 사실상 같지만, 넓힐수록 엉뚱한 동명이 섞여 들어온다.
     *
     * <p><b>여럿을 받는 것만으로는 부족했다.</b> 고르는 규칙({@link #best})이 함께 맞아야 한다 —
     * 인구로 고르면서 없을 때 첫 결과로 떨어지던 동안, 국내는 후보에 인구가 아예 없어
     * 그 폴백만 돌고 있었다.
     */
    private static final int CANDIDATES = 10;

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
     * @return 1순위 후보. <b>이름은 상대가 준 날것이다</b> — 로마자로 왔을 때 한국어로
     *         바꾸는 것은 호출자가 {@link GeoLocation#labelledFor}로 한다.
     *         못 찾으면 {@link Optional#empty()}
     */
    @Cacheable(cacheNames = CacheNames.GEOCODE, key = "#a0 + '|' + #a1", unless = "#result == null")
    @Retry(name = "weatherGeocoding")
    @CircuitBreaker(name = "weatherGeocoding")
    public Optional<GeoLocation> find(String query, String countryCode) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Results response = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/search")
                            .queryParam("name", query)
                            // ⚠️ 여러 개를 받아 인구로 고른다. 1건만 받으면 안 된다 —
                            // 이 API는 인구순으로 주지 않는다. 2026-08-17 실측에서 '성남'의
                            // 경기도 성남시는 10건 중 9번째였고, count=1이던 시절에는
                            // 전라북도 남원시의 마을(35.54, 127.40)을 집었다. 분당에서 200km다.
                            .queryParam("count", CANDIDATES)
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
        Optional<Place> picked = best(response.results());
        if (picked.isEmpty()) {
            log.info("[weather] '{}'({}) 후보 {}건이 전부 인구 미상입니다 — 믿을 수 없어 버립니다",
                    query, countryCode, response.results().size());
            return Optional.empty();
        }
        return picked.map(GeocodingApi::toLocation);
    }

    /**
     * <b>인구가 붙은 후보 중 가장 큰 곳.</b> 하나도 없으면 <b>고르지 않는다.</b>
     *
     * <p>예전에는 없으면 첫 결과로 떨어졌는데({@code orElseGet(candidates.get(0))}) 그게
     * <b>국내를 통째로 망가뜨리고 있었다.</b> 이 API는 한국어 짧은 지명에 대해 인구 정보가
     * 없는 마을·지구만 물어 오므로, 인구 정렬이 <b>언제나 무력</b>했고 매번 API 임의 순서의
     * 첫 줄을 집었다. 실측(2026-08-19): {@code 서현} → 김포시(37.65, 126.60),
     * {@code 성남} → 전라북도(35.54, 127.40) — 분당에서 200km, {@code 강남} → 전라북도.
     *
     * <p>그래서 <b>인구가 곧 "이게 진짜 도시냐"의 신호</b>다. 없으면 빈손을 돌려주고 사용자에게
     * 다시 물어본다 — 틀린 좌표로 그럴듯한 답을 주는 것이 빈손보다 나쁘다는 규칙이
     * 이 저장소 전체에 걸려 있다({@code (종가)}·{@code 강수량 mm}와 같은 자리다).
     *
     * <p>대가는 <b>인구 통계가 없는 해외 소도시를 못 찾는 것</b>이다. 그때는 못 찾았다고
     * 답하고 사용자가 더 큰 지명으로 다시 친다 — 엉뚱한 나라 값을 주는 것보다 낫다.
     */
    private static Optional<Place> best(List<Place> candidates) {
        return candidates.stream()
                .filter(place -> place.population() != null && place.population() > 0)
                .max(Comparator.comparingLong(Place::population));
    }

    /**
     * <b>시간대를 못 읽으면 UTC로 둔다.</b>
     *
     * <p>지오코딩은 늘 {@code timezone}을 주지만, 없다고 KST로 채우면 <b>남의 하루를 우리
     * 달력으로 자르게 된다.</b> UTC는 적어도 어느 한쪽으로 치우치지 않는다.
     *
     * <p><b>이름은 상대가 준 것을 날것으로 담는다.</b> {@code language=ko}로 불러도 일부는
     * 로마자로 오는데({@code 제주시} → {@code Jejudo}, 실측) 그걸 한국어로 바꾸는 판단은
     * 여기가 하지 않는다 — {@link GeoLocation#labelledFor}가 <b>읽을 때</b> 한다.
     *
     * <p>⚠️ <b>여기서 정하면 안 된다.</b> 이 결과가 {@code geocode} 캐시에 30일 들어가므로
     * 파생된 표기가 캐시에 굳는다 — 담는 것은 상대가 준 것만, 만드는 것은 읽을 때다.
     */
    private static GeoLocation toLocation(Place place) {
        return new GeoLocation(place.name(), place.country(),
                place.latitude(), place.longitude(), zoneOf(place.timezone()));
    }

    /**
     * ⚠️ <b>「못 읽으면」에는 「모르는 이름」도 든다.</b> 위 규칙을 적어 두고도 {@code null}만
     * 막고 있었다 — {@code ZoneId.of}는 형식이 맞아도 <b>JDK의 tzdb에 없는 이름이면 던진다</b>
     * ({@code ZoneRulesException}). 그러면 그 도시의 {@code /weather}가 통째로 실패하고
     * (호출자는 「조회 실패」로 답한다) 멀쩡한 지오코딩 브레이커에 실패가 쌓인다.
     *
     * <p>드물지만 실재하는 길이다 — 새 시간대가 생기면 출처가 먼저 알고 JDK가 나중에 따라온다
     * ({@code America/Ciudad_Juarez}가 그런 예다). 지점을 못 쓰게 만들 만한 일이 아니다.
     */
    private static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone);
        } catch (java.time.DateTimeException e) {
            log.warn("[weather] 모르는 시간대 '{}' — UTC로 둡니다", timezone);
            return ZoneId.of("UTC");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Results(List<Place> results) {}

    /**
     * @param country    {@code language=ko}면 한국어다({@code 아르헨티나}). 없을 수 있다
     * @param population 인구. <b>작은 마을에는 아예 없다</b>({@code null}) — 그 없음 자체가
     *                   "여기가 사람이 물은 곳은 아니다"라는 신호라서 {@link #best}가 이걸로 고른다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Place(String name, String country, double latitude, double longitude, String timezone,
                 Long population) {}
}
