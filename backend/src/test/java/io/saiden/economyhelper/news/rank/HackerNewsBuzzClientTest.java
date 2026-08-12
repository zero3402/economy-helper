package io.saiden.economyhelper.news.rank;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.news.rank.HackerNewsApi.Hit;
import io.saiden.economyhelper.news.rank.HackerNewsApi.SearchResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 네트워크 없이 돈다 — URL 정합과 응답 접기는 순수 함수로 떼어 놓았고,
 * 강등 경로는 {@link HackerNewsApi}를 스텁으로 갈아끼워 확인한다.
 *
 * <p>JSON 역직렬화 자체는 Jackson의 몫이라 여기서 검증하지 않는다.
 */
class HackerNewsBuzzClientTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Nested
    @DisplayName("URL 정규화 — HN과 피드의 URL 표기 차이를 흡수한다")
    class UrlNormalization {

        @Test
        @DisplayName("스킴·www·끝 슬래시가 달라도 같은 URL로 본다")
        void treatsCosmeticDifferencesAsEqual() {
            String canonical = HackerNewsBuzzClient.normalizeUrl("https://www.reuters.com/markets/oil/");

            assertThat(HackerNewsBuzzClient.normalizeUrl("http://reuters.com/markets/oil"))
                    .isEqualTo(canonical);
            assertThat(HackerNewsBuzzClient.normalizeUrl("HTTPS://WWW.Reuters.com/markets/oil//"))
                    .isEqualTo(canonical);
        }

        @Test
        @DisplayName("추적 파라미터와 프래그먼트를 떼어낸다")
        void stripsQueryAndFragment() {
            assertThat(HackerNewsBuzzClient.normalizeUrl(
                    "https://ft.com/content/abc?utm_source=hn&x=1#top"))
                    .isEqualTo("ft.com/content/abc");
        }

        @Test
        @DisplayName("호스트만 뽑아 HN 조회 단위로 쓴다")
        void extractsDomain() {
            assertThat(HackerNewsBuzzClient.domainOf("https://www.bloomberg.com/news/articles/2026-08-10/x"))
                    .isEqualTo("bloomberg.com");
            assertThat(HackerNewsBuzzClient.domainOf(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("응답 접기")
    class Folding {

        @Test
        @DisplayName("points + num_comments를 더해 buzz로 삼는다")
        void sumsPointsAndComments() {
            SearchResponse response = response(
                    new Hit("https://www.reuters.com/a", 900, 30),
                    new Hit("https://economist.com/b/", 52, 33));

            Map<String, Integer> folded = HackerNewsApi.toBuzzMap(response);

            assertThat(folded).containsEntry("reuters.com/a", 930);
            assertThat(folded).containsEntry("economist.com/b", 85);
        }

        @Test
        @DisplayName("외부 URL이 없는 글(Ask HN 등)은 건너뛴다")
        void skipsHitsWithoutUrl() {
            SearchResponse response = response(
                    new Hit(null, 500, 200),
                    new Hit("  ", 10, 1),
                    new Hit("https://ft.com/c", 3, 0));

            assertThat(HackerNewsApi.toBuzzMap(response))
                    .hasSize(1)
                    .containsEntry("ft.com/c", 3);
        }

        @Test
        @DisplayName("같은 기사가 여러 번 올라오면 반응이 큰 쪽을 취한다")
        void keepsLargestBuzzForDuplicateSubmissions() {
            SearchResponse response = response(
                    new Hit("https://ft.com/c", 3, 0),
                    new Hit("http://www.ft.com/c/", 300, 86));

            assertThat(HackerNewsApi.toBuzzMap(response)).containsEntry("ft.com/c", 386);
        }

        @Test
        @DisplayName("빈 응답은 예외 대신 빈 맵 — buzz는 부속 신호라 본류를 죽이면 안 된다")
        void degradesToEmptyOnMissingPayload() {
            assertThat(HackerNewsApi.toBuzzMap(null)).isEmpty();
            assertThat(HackerNewsApi.toBuzzMap(new SearchResponse(null))).isEmpty();
            assertThat(HackerNewsApi.toBuzzMap(new SearchResponse(List.of()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("기사 목록에 붙이기")
    class Matching {

        @Test
        @DisplayName("HN에 있는 기사만 담고, 도메인당 한 번만 조회한다")
        void mapsOnlyArticlesFoundOnHackerNews() {
            Article onHn = article("https://www.reuters.com/markets/oil?utm_source=rss");
            Article notOnHn = article("https://www.reuters.com/markets/gold");

            CountingApi api = new CountingApi(Map.of("reuters.com/markets/oil", 930));
            HackerNewsBuzzClient client = new HackerNewsBuzzClient(api, Duration.ofDays(7));

            Map<String, Integer> buzz = client.buzzByLink(List.of(onHn, notOnHn), NOW);

            assertThat(buzz).hasSize(1).containsEntry(onHn.link(), 930);
            assertThat(api.calls).as("도메인이 하나뿐이므로 한 번만 조회해야 한다").isEqualTo(1);
        }

        @Test
        @DisplayName("HN이 죽어 빈 맵을 줘도 조용히 넘어간다 — 랭킹은 나머지 지표로 계속된다")
        void survivesHackerNewsOutage() {
            HackerNewsBuzzClient client =
                    new HackerNewsBuzzClient(new CountingApi(Map.of()), Duration.ofDays(7));

            assertThat(client.buzzByLink(List.of(article("https://ft.com/c")), NOW)).isEmpty();
        }

        @Test
        @DisplayName("기사가 없으면 조회조차 하지 않는다")
        void skipsLookupWhenNoArticles() {
            CountingApi api = new CountingApi(Map.of());
            HackerNewsBuzzClient client = new HackerNewsBuzzClient(api, Duration.ofDays(7));

            assertThat(client.buzzByLink(List.of(), NOW)).isEmpty();
            assertThat(api.calls).isZero();
        }
    }

    private static SearchResponse response(Hit... hits) {
        return new SearchResponse(Arrays.asList(hits));
    }

    private static Article article(String link) {
        return new Article(NewsSource.REUTERS, "제목", null, link, NOW, 0);
    }

    /** 호출 횟수를 세는 스텁 — 도메인당 한 번만 부르는지 확인하려고 둔다. */
    private static final class CountingApi extends HackerNewsApi {
        private final Map<String, Integer> canned;
        private int calls;

        private CountingApi(Map<String, Integer> canned) {
            super(RestClient.builder(), "https://example.invalid", 100);
            this.canned = canned;
        }

        @Override
        public Map<String, Integer> storiesForDomain(String domain, Instant since) {
            calls++;
            return canned;
        }
    }
}
