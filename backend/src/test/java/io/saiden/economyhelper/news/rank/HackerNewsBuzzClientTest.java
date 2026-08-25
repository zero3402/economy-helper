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
        @DisplayName("링크가 목적지를 감추는 매체는 조회하지 않는다 — AP는 맞힐 수가 없다")
        void skipsSourcesWhoseLinksHideTheDestination() {
            // AP의 구글 뉴스 프록시 주소는 HN에 올라간 실제 AP 주소와 절대 같아지지 않는다.
            // 예전에는 news.google.com을 도메인으로 물어 브리핑마다 헛호출을 한 번 태웠다
            Article ap = new Article(NewsSource.AP, "제목", null,
                    "https://news.google.com/rss/articles/CBMiK2h0dHBz", NOW, 0);
            CountingApi api = new CountingApi(Map.of());
            HackerNewsBuzzClient client = new HackerNewsBuzzClient(api, Duration.ofDays(7));

            assertThat(client.buzzByLink(List.of(ap), NOW)).isEmpty();
            assertThat(api.calls).as("맞힐 수 없는 조회는 아예 나가지 않는다").isZero();
        }

        @Test
        @DisplayName("HN이 던져도 랭킹은 계속된다 — 강등이 여기 있어야 브레이커가 실패를 본다")
        void survivesHackerNewsOutage() {
            // ⚠️ 예전에는 HackerNewsApi가 스스로 삼켜 빈 맵을 돌려줬고, 그래서 이 테스트도
            //    빈 맵을 먹였다. 그 설계에서는 그 메서드의 @CircuitBreaker가 정상 반환을 보고
            //    **성공을 세** 절대 열리지 않았다. 이제 API는 던지고 강등은 이 클래스가 한다 —
            //    브레이커가 실패를 먼저 세고, 사용자에게 보이는 결과는 똑같이 빈손이다
            HackerNewsBuzzClient client =
                    new HackerNewsBuzzClient(new ExplodingApi(), Duration.ofDays(7));

            assertThat(client.buzzByLink(List.of(article("https://ft.com/c")), NOW)).isEmpty();
        }

        @Test
        @DisplayName("한 도메인이 실패해도 나머지 도메인의 buzz는 살아남는다")
        void oneBadDomainDoesNotSinkTheRest() {
            // 루프 **안**에서 잡는 이유다. 밖에서 한 번만 잡으면 첫 실패가 뒤의 매체를 통째로 버린다
            Article good = article("https://cnbc.com/a");
            Article bad = article("https://ft.com/b");
            HackerNewsBuzzClient client = new HackerNewsBuzzClient(
                    new FailingDomainApi("ft.com", Map.of("cnbc.com/a", 12)), Duration.ofDays(7));

            assertThat(client.buzzByLink(List.of(good, bad), NOW))
                    .containsExactly(Map.entry(good.link(), 12));
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

    /**
     * 링크가 목적지를 가리키는 매체로 만든다.
     *
     * <p>⚠️ 예전에는 {@link NewsSource#AP}였다. AP는 구글 뉴스 프록시라 링크가 목적지를
     * 감추고, 그래서 지금은 조회에서 아예 빠진다 — 그 매체로 만든 픽스처는 "HN 조회가
     * 어떻게 도는가"를 볼 수 없다. 임의로 고른 값이 규칙이 생기자 틀린 값이 된 자리다.
     */
    private static Article article(String link) {
        return new Article(NewsSource.CNBC, "제목", null, link, NOW, 0);
    }

    /** 실패를 던지는 API — 강등이 {@link HackerNewsBuzzClient}에 있음을 보인다. */
    private static final class ExplodingApi extends HackerNewsApi {
        private ExplodingApi() {
            super(RestClient.builder(), "https://example.invalid", 100);
        }

        @Override
        public Map<String, Integer> storiesForDomain(String domain, Instant since) {
            throw new IllegalStateException("HN이 죽었다");
        }
    }

    /** 지목한 도메인만 실패시킨다 — 도메인별로 잡는지 보려면 하나는 성공해야 한다. */
    private static final class FailingDomainApi extends HackerNewsApi {
        private final String failing;
        private final Map<String, Integer> canned;

        private FailingDomainApi(String failing, Map<String, Integer> canned) {
            super(RestClient.builder(), "https://example.invalid", 100);
            this.failing = failing;
            this.canned = canned;
        }

        @Override
        public Map<String, Integer> storiesForDomain(String domain, Instant since) {
            if (failing.equals(domain)) {
                throw new IllegalStateException(domain + " 조회 실패");
            }
            return canned;
        }
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
