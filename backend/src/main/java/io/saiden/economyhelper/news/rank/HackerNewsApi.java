package io.saiden.economyhelper.news.rank;

import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HN Algolia 조회 — 매체 도메인 하나당 한 번.
 *
 * <p>{@link HackerNewsBuzzClient}와 분리한 이유는 {@code @Cacheable}·{@code @CircuitBreaker}가
 * 프록시 기반이라서다. 같은 클래스 안에서 부르면 프록시를 타지 않아 캐시도 서킷브레이커도
 * 조용히 무력화된다.
 *
 * <p>기사 하나씩 조회하면 피드당 20~100번을 호출하게 되므로 도메인 단위로 한 번만 긁고
 * URL로 맞춘다.
 */
@Component
public class HackerNewsApi {

    private final RestClient restClient;
    private final int hitsPerPage;

    public HackerNewsApi(RestClient.Builder builder,
                         @Value("${economy-helper.ranking.hacker-news.base-url}") String baseUrl,
                         @Value("${economy-helper.ranking.hacker-news.hits-per-page:100}") int hitsPerPage) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.hitsPerPage = hitsPerPage;
    }

    /**
     * 해당 도메인의 최근 스토리를 {@code 정규화URL -> points + num_comments}로 돌려준다.
     *
     * <p>{@code restrictSearchableAttributes=url}로 좁히지 않으면 본문에 도메인만 언급한 글이
     * 섞여 들어온다.
     *
     * <p><b>실패는 던진다 — 삼키지 않는다.</b> buzz가 랭킹의 부속 신호일 뿐인 것은 맞고 HN이
     * 죽었다고 발송이 멈춰선 안 되지만, <b>그 강등을 여기서 하면 안 된다.</b> 여기서 빈 맵을
     * 돌려주면 {@code @CircuitBreaker}가 <b>정상 반환을 보고 성공을 센다</b> — 실패율이 영원히
     * 0이라 브레이커가 절대 열리지 않는다. 그러면 HN이 죽어 있는 동안 랭킹이 돌 때마다 도메인을
     * 전부 새로 찌른다({@code unless = "#result.isEmpty()"} 때문에 빈 결과는 캐시되지도 않는다).
     * 실제로 그 상태였고 {@code application.yml}의 {@code failureRateThreshold: 30}·
     * {@code waitDurationInOpenState: 300s}가 아무 일도 하지 않는 죽은 값이었다.
     *
     * <p>그래서 강등은 <b>한 칸 위</b>에서 한다 — {@link HackerNewsBuzzClient}가 도메인마다
     * 잡아서 건너뛴다. 브레이커가 실패를 먼저 보고, 열린 뒤에는 {@code CallNotPermittedException}이
     * 같은 자리에서 잡혀 같은 강등이 일어난다. {@code FeedFetcher}가 {@code @Retry}에 대해
     * 적어 둔 「예외를 스스로 삼키면 발동조차 하지 않는다」가 브레이커에도 그대로 적용된다.
     */
    @CircuitBreaker(name = "hackerNews")
    @Cacheable(cacheNames = CacheNames.HN_BUZZ, key = "#domain", unless = "#result.isEmpty()")
    public Map<String, Integer> storiesForDomain(String domain, Instant since) {
        SearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/search")
                        .queryParam("query", domain)
                        .queryParam("restrictSearchableAttributes", "url")
                        .queryParam("tags", "story")
                        .queryParam("numericFilters", "created_at_i>" + since.getEpochSecond())
                        .queryParam("hitsPerPage", hitsPerPage)
                        .build())
                .retrieve()
                .body(SearchResponse.class);
        return toBuzzMap(response);
    }

    /**
     * 응답을 {@code 정규화URL -> buzz}로 접는다.
     *
     * <p>JSON 역직렬화는 Jackson에 맡기고 우리 로직만 순수 함수로 떼어 테스트한다.
     */
    static Map<String, Integer> toBuzzMap(SearchResponse response) {
        if (response == null || response.hits() == null) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        for (Hit hit : response.hits()) {
            if (hit == null || hit.url() == null || hit.url().isBlank()) {
                continue;  // Ask HN 같은 자체 글은 외부 URL이 없다
            }
            // 같은 기사가 여러 번 올라오면 반응이 가장 큰 쪽을 취한다
            result.merge(HackerNewsBuzzClient.normalizeUrl(hit.url()), hit.buzz(), Math::max);
        }
        return Map.copyOf(result);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Hit> hits) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hit(String url, int points, @JsonProperty("num_comments") int numComments) {

        /**
         * CLAUDE.md가 말한 "조회수 또는 댓글"을 대신하는 값 — <b>둘 중 하나가 아니라 둘을 더한다.</b>
         *
         * <p>어느 매체도 조회수·댓글 수를 공개하지 않아 HN 반응이 유일한 실측이고, 거기서는
         * 추천과 댓글이 둘 다 온다. 하나만 쓸 이유가 없다.
         */
        int buzz() {
            return points + numComments;
        }
    }
}
