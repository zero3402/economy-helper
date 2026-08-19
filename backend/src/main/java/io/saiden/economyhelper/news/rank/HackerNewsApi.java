package io.saiden.economyhelper.news.rank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HackerNewsApi.class);

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
     * <p>실패해도 예외를 던지지 않고 빈 맵을 준다 — buzz는 랭킹의 부속 신호일 뿐이라
     * HN이 죽었다고 뉴스 발송 전체가 멈추면 안 된다. 서킷브레이커는 반복 실패 시
     * 호출 자체를 끊으려고 얹는다.
     */
    @CircuitBreaker(name = "hackerNews")
    @Cacheable(cacheNames = "hn-buzz", key = "#domain", unless = "#result.isEmpty()")
    public Map<String, Integer> storiesForDomain(String domain, Instant since) {
        try {
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
        } catch (Exception e) {
            log.warn("[{}] HN 조회 실패 — buzz를 0으로 강등합니다: {}", domain, e.toString());
            return Map.of();
        }
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
