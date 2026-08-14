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
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 수집 실패가 발송 전체를 막지 않는지, 그리고 두 진입점이 공유할 로직이 맞는지 고정한다. */
class NewsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 예전 필터("키워드가 하나도 안 걸리면 제외")와 같은 뜻이 되도록 0보다 크게만 잡는다. */
    private static final double RELEVANCE_THRESHOLD = 0.01;

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

        assertThat(service.search(groups("rate"))).isPresent();
    }

    @Test
    @DisplayName("걸리는 기사가 없으면 빈 결과")
    void searchReturnsEmptyWhenNothingMatches() {
        NewsService service = service(Map.of(
                NewsSource.CNBC, List.of(article(NewsSource.CNBC, "Oil prices climb", 0))));

        assertThat(service.search(groups("비트코인"))).isEmpty();
    }

    @Test
    @DisplayName("키워드가 비면 전체를 훑지 않고 곧바로 빈 결과 — 토큰화는 QueryExpander의 몫이다")
    void searchRejectsEmptyKeywords() {
        assertThat(service(Map.of()).search(groups())).isEmpty();
        assertThat(service(Map.of()).search(null)).isEmpty();
        assertThat(service(Map.of()).search(List.of(KeywordGroup.of()))).isEmpty();
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
                new PopularityScorer(RankingWeights.defaults(), Duration.ofHours(6)),
                relevance,
                CLOCK,
                8,
                RELEVANCE_THRESHOLD);
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
