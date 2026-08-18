package io.saiden.economyhelper.news;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.config.EconomyHelperProperties.Ranking;
import io.saiden.economyhelper.config.EconomyHelperProperties.Weights;
import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.news.rank.HackerNewsApi;
import io.saiden.economyhelper.news.rank.HackerNewsBuzzClient;
import io.saiden.economyhelper.news.rank.KeywordGroup;
import io.saiden.economyhelper.news.rank.PopularityScorer;
import io.saiden.economyhelper.news.rank.RankingWeights;
import io.saiden.economyhelper.news.rank.RelevanceScorer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 수집 실패가 발송 전체를 막지 않는지, 그리고 두 진입점이 공유할 로직이 맞는지 고정한다. */
class NewsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 예전 필터("키워드가 하나도 안 걸리면 제외")와 같은 뜻이 되도록 0보다 크게만 잡는다. */
    private static final double RELEVANCE_THRESHOLD = 0.01;
    /** 신선도 창 — 날짜가 아니라 경과 시간이다. 운영 기본값과 같은 24시간으로 둔다. */
    private static final Duration WINDOW = Duration.ofHours(24);

    @Test
    @DisplayName("스쳐 지나간 기사는 답이 아니다 — 관련 없는 걸 주느니 못 찾았다고 한다")
    void searchDropsArticlesTheLlmRejects() {
        NewsService service = service(Map.of(
                NewsSource.CNBC, List.of(article(NewsSource.CNBC, "Fed signals rate cut", 0))),
                rejectingSearchScorer());

        assertThat(service.search(groups("rate"), "금리")).isEmpty();
    }

    @Test
    @DisplayName("검색어 원문을 안 주면 LLM 검증을 건너뛴다 — 예전 경로가 그대로 남는다")
    void searchWithoutQuerySkipsVerification() {
        NewsService service = service(Map.of(
                NewsSource.CNBC, List.of(article(NewsSource.CNBC, "Fed signals rate cut", 0))),
                rejectingSearchScorer());

        assertThat(service.search(groups("rate"), null)).isNotEmpty();
    }

    @Test
    @DisplayName("상위 몇 건까지만 준다 — 걸린 게 많다고 다 쏟아내지 않는다")
    void capsSearchResults() {
        NewsService service = service(Map.of(NewsSource.CNBC, List.of(
                article(NewsSource.CNBC, "Fed signals rate cut", 0),
                article(NewsSource.CNBC, "Rate hike is off the table", 1),
                article(NewsSource.CNBC, "Traders price in a rate move", 2),
                article(NewsSource.CNBC, "Rate outlook shifts again", 3),
                article(NewsSource.CNBC, "Another rate story", 4))));

        assertThat(service.search(groups("rate"), "금리")).hasSize(3);
    }

    @Test
    @DisplayName("걸린 게 상한보다 적으면 그만큼만 — 자리를 채우려 관련 없는 기사를 끌어오지 않는다")
    void returnsFewerThanTheCapWhenThatIsAllThereIs() {
        NewsService service = service(Map.of(NewsSource.CNBC, List.of(
                article(NewsSource.CNBC, "Fed signals rate cut", 0))));

        assertThat(service.search(groups("rate"), "금리")).hasSize(1);
    }

    @Test
    @DisplayName("검색은 최근 창 안의 발행분만 남긴다 — 날짜가 아니라 경과 시간으로 자른다")
    void searchKeepsOnlyRecent() {
        NewsService service = service(Map.of(NewsSource.CNBC, List.of(
                article(NewsSource.CNBC, "Rate cut today", 0),
                aged(NewsSource.CNBC, "Old rate story", 1, Duration.ofHours(25)))));

        List<ScoredArticle> result = service.search(groups("rate"), "금리");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).article().title()).isEqualTo("Rate cut today");
    }

    @Test
    @DisplayName("23시간 전 기사는 살아남는다 — KST 달력으로 '어제'여도 하루가 안 지났다")
    void keepsArticleFromYesterdayInKstButWithinWindow() {
        // 실측(2026-08-15): /news 이더리움이 빈손이던 이유가 이것이었다. 가장 최근 기사가
        // 23시간 18분 전(08-14 21:36 KST)이라 날짜로 자르면 '어제'가 되어 사라졌다
        NewsService service = service(Map.of(NewsSource.YAHOO_FINANCE, List.of(
                aged(NewsSource.YAHOO_FINANCE, "Bitcoin and ethereum prices today", 0,
                        Duration.ofHours(23)))));

        assertThat(service.search(groups("ethereum"), "이더리움"))
                .singleElement()
                .satisfies(scored -> assertThat(scored.article().title()).contains("ethereum"));
    }

    @Test
    @DisplayName("브리핑은 전 매체를 통틀어 최근 창 안의 발행분 중 점수 상위 3건을 준다")
    void digestTakesTopThreeAcrossSourcesFromWindow() {
        NewsService service = service(Map.of(
                NewsSource.CNBC, List.of(
                        article(NewsSource.CNBC, "cnbc a", 0),
                        article(NewsSource.CNBC, "cnbc b", 1),
                        aged(NewsSource.CNBC, "cnbc old", 2, Duration.ofHours(25))),
                NewsSource.YAHOO_FINANCE, List.of(
                        article(NewsSource.YAHOO_FINANCE, "yahoo a", 0),
                        article(NewsSource.YAHOO_FINANCE, "yahoo b", 1))));

        List<ScoredArticle> digest = service.digest();

        assertThat(digest).as("창 안의 후보 4건 중 상위 3건").hasSize(3);
        assertThat(digest).as("창을 벗어난 기사는 빠진다")
                .allSatisfy(scored -> assertThat(scored.article().title()).doesNotContain("old"));
    }

    @Test
    @DisplayName("걸리는 기사가 없으면 빈 결과")
    void searchReturnsEmptyWhenNothingMatches() {
        NewsService service = service(Map.of(
                NewsSource.CNBC, List.of(article(NewsSource.CNBC, "Oil prices climb", 0))));

        assertThat(service.search(groups("비트코인"), null)).isEmpty();
    }

    @Test
    @DisplayName("키워드가 비면 전체를 훑지 않고 곧바로 빈 결과 — 토큰화는 QueryExpander의 몫이다")
    void searchRejectsEmptyKeywords() {
        assertThat(service(Map.of()).search(groups(), null)).isEmpty();
        assertThat(service(Map.of()).search(null, null)).isEmpty();
        assertThat(service(Map.of()).search(List.of(KeywordGroup.of()), null)).isEmpty();
    }

    /** 검색어와 무관하다고 답하는 LLM — 매칭은 됐지만 스쳐 지나간 기사의 상황이다. */
    private static RelevanceScorer rejectingSearchScorer() {
        return new RelevanceScorer(null, null) {
            @Override
            public Map<String, Double> scoreAll(List<Article> candidates) {
                return candidates.stream().collect(
                        java.util.stream.Collectors.toMap(Article::link, a -> 1.0, (x, y) -> x));
            }

            @Override
            public Map<String, Double> scoreAll(List<Article> candidates, String query) {
                return candidates.stream().collect(
                        java.util.stream.Collectors.toMap(Article::link, a -> 0.0, (x, y) -> x));
            }
        };
    }

    /** 표현마다 1항목 묶음 — 재테크 사전이 이 모양이다. */
    private static List<KeywordGroup> groups(String... terms) {
        return Arrays.stream(terms).map(KeywordGroup::of).toList();
    }

    private static NewsService service(Map<NewsSource, List<Article>> bySource) {
        return service(bySource, scorerMatching());
    }

    private static NewsService service(Map<NewsSource, List<Article>> bySource, RelevanceScorer relevance) {
        return new NewsService(
                new StubFetcher(bySource),
                new StubBuzzClient(),
                new PopularityScorer(new RankingWeights(0.35, 0.25, 0.25, 0.15), Duration.ofHours(6)),
                relevance,
                CLOCK,
                WINDOW,
                8,
                RELEVANCE_THRESHOLD,
                3,
                3);
    }

    /**
     * 주어진 단어가 걸리는 기사에만 1.0을 준다 — LLM 대신 결정적으로 동작한다.
     *
     * <p>이 클래스의 관심사는 "수집 → 후보 좁히기 → 임계값 → 랭킹" 흐름이지 채점 방식이 아니다.
     * 실제 LLM 경로는 {@code RelevanceScorerTest}가 따로 본다.
     */
    private static RelevanceScorer scorerMatching(String... terms0) {
        List<String> terms = List.of(terms0);
        return new RelevanceScorer(null, null) {
            @Override
            public Map<String, Double> scoreAll(List<Article> candidates) {
                return candidates.stream().collect(java.util.stream.Collectors.toMap(
                        Article::link,
                        a -> matches(a, terms) ? 1.0 : 0.0,
                        (x, y) -> x));
            }

            private boolean matches(Article article, List<String> keywords) {
                String haystack = article.text().toLowerCase(java.util.Locale.ROOT);
                return keywords.isEmpty() || keywords.stream().anyMatch(haystack::contains);
            }
        };
    }

    private static Article article(NewsSource source, String title, int feedRank) {
        return new Article(source, title, null,
                "https://example.com/" + source + "/" + title.hashCode(), NOW, feedRank);
    }

    /** 발행된 지 {@code age}만큼 지난 기사 — 창 경계를 재는 데 쓴다. */
    private static Article aged(NewsSource source, String title, int feedRank, Duration age) {
        return new Article(source, title, null,
                "https://example.com/" + source + "/" + title.hashCode(),
                NOW.minus(age), feedRank);
    }

    /** 실제 HTTP 없이 소스별 결과를 정해 준다. */
    private static final class StubFetcher extends FeedFetcher {
        private final Map<NewsSource, List<Article>> bySource;

        private StubFetcher(Map<NewsSource, List<Article>> bySource) {
            super(RestClient.builder(),
                    new EconomyHelperProperties(
                            new EnumMap<NewsSource, Feed>(NewsSource.class),
                            new Ranking(new Weights(1, 1, 1, 1), Duration.ofHours(6)),
                            null,
                            null,
                            null),
                    CircuitBreakerRegistry.ofDefaults(),
                    Clock.systemUTC(),
                    Duration.ofDays(3),
                    List.of());
            this.bySource = bySource;
        }

        @Override
        public List<Article> fetch(NewsSource source) {
            return bySource.getOrDefault(source, List.of());
        }
    }

    /** HN을 타지 않는다 — buzz가 0이어도 랭킹이 성립하는지 함께 확인하는 셈이다. */
    private static final class StubBuzzClient extends HackerNewsBuzzClient {
        private StubBuzzClient() {
            super(new HackerNewsApi(RestClient.builder(), "https://example.invalid", 100),
                    Duration.ofDays(7));
        }

        @Override
        public Map<String, Integer> buzzByLink(List<Article> articles, Instant now) {
            return Map.of();
        }
    }
}
