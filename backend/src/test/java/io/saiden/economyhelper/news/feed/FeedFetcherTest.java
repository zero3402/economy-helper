package io.saiden.economyhelper.news.feed;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.config.EconomyHelperProperties.Ranking;
import io.saiden.economyhelper.config.EconomyHelperProperties.Weights;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import io.saiden.economyhelper.support.TestRetries;
import io.saiden.economyhelper.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import io.saiden.economyhelper.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 수집의 장애 격리를 고정한다 — 여기가 "한 매체가 죽어도 나머지는 발송된다"의 실제 지점이다.
 *
 * <p>WireMock으로 매체를 흉내 내므로 외부 네트워크를 타지 않는다.
 */
class FeedFetcherTest extends WireMockTest {

    /** 픽스처를 뜬 날. 나이 컷오프가 픽스처를 통째로 버리지 않도록 여기에 맞춘다. */
    private static final java.time.Clock CLOCK =
            java.time.Clock.fixed(java.time.Instant.parse("2026-08-14T09:00:00Z"),
                    java.time.ZoneOffset.UTC);
    private static final Duration MAX_AGE = Duration.ofDays(3);

    /**
     * 코인 매체 픽스처를 뜬 날 — 나중에 붙은 매체라 픽스처 날짜가 위 {@link #CLOCK}보다 늦다.
     *
     * <p>옛 픽스처의 날짜에 맞춰 다시 뜨는 대신 시계를 따로 둔다. 「외부 API는 실호출로
     * 계약을 확정하고 그 응답을 줄여 스텁 본문으로 쓴다」가 이 저장소의 테스트 규칙이므로
     * <b>실제로 받은 값의 날짜를 고쳐 쓰지 않는다.</b>
     */
    private static final java.time.Clock CRYPTO_CLOCK =
            java.time.Clock.fixed(java.time.Instant.parse("2026-08-27T01:00:00Z"),
                    java.time.ZoneOffset.UTC);

    @Test
    @DisplayName("정상 응답이면 파싱해서 돌려준다")
    void parsesSuccessfulResponse() throws IOException {
        stubFeed("/cnbc", 200, fixture("cnbc.xml"));

        List<Article> articles = fetcher(Map.of(
                NewsSource.CNBC, feed("/cnbc", FeedType.RSS)))
                .fetch(NewsSource.CNBC);

        // 픽스처 30건 중 기준 시각(2026-08-14 09:00 UTC)에서 사흘 이내인 것만 남는다.
        // 건수 자체는 RssFeedClientTest가 본다 — 여기서는 수집이 값을 돌려준다는 것만 본다
        assertThat(articles).isNotEmpty();
    }

    @Test
    @DisplayName("자기 이름을 대고 요청한다 — 브라우저인 척하지도, UA를 비우지도 않는다")
    void identifiesItselfWithoutPretendingToBeABrowser() throws IOException {
        stubFeed("/cnbc", 200, fixture("cnbc.xml"));

        fetcher(Map.of(NewsSource.CNBC, feed("/cnbc", FeedType.RSS))).fetch(NewsSource.CNBC);

        // UA를 아예 안 보내면 야후가 자바 기본값을 429로 막는다. 그렇다고 크롬을 흉내 낼
        // 이유는 없다 — 이 단언이 그 둘 사이를 고정한다
        server.verify(getRequestedFor(urlPathEqualTo("/cnbc"))
                .withHeader("User-Agent", equalTo("economy-helper/1.0")));
    }

    @Test
    @DisplayName("그 매체 피드에서 그 매체 기사만 남긴다 — Yahoo가 실어 온 남의 기사는 뺀다")
    void keepsOnlyTheOutletsOwnArticles() throws IOException {
        stubFeed("/yahoo", 200, fixture("yahoo-finance.xml"));

        List<Article> articles = fetcher(Map.of(
                NewsSource.YAHOO_FINANCE, feed("/yahoo", FeedType.RSS)))
                .fetch(NewsSource.YAHOO_FINANCE);

        // 실물 픽스처 48건 중 8건이 wsj.com·investors.com이고(둘 다 페이월),
        // 남은 40건 중 2건이 2024년 기사라 나이 컷오프에서 다시 빠진다
        assertThat(articles).hasSize(38);
        assertThat(articles).allSatisfy(article ->
                assertThat(article.link()).contains("yahoo.com"));
    }

    @Test
    @DisplayName("오래된 기사는 뺀다 — 신선도 가중치는 랭킹 네 항 중 하나라 옛 기사를 못 막는다")
    void dropsArticlesOlderThanTheCutoff() throws IOException {
        stubFeed("/yahoo", 200, fixture("yahoo-finance.xml"));

        List<Article> articles = fetcher(Map.of(
                NewsSource.YAHOO_FINANCE, feed("/yahoo", FeedType.RSS)))
                .fetch(NewsSource.YAHOO_FINANCE);

        // 실물 Yahoo 피드에는 2024~2025년 에버그린 기사가 섞여 온다
        Instant cutoff = CLOCK.instant().minus(MAX_AGE);
        assertThat(articles).allSatisfy(article ->
                assertThat(article.publishedAt()).isAfter(cutoff));
    }

    @Test
    @DisplayName("컷오프가 전부 걸러내도 빈 리스트일 뿐 터지지 않는다 — 연휴에 브리핑이 죽으면 안 된다")
    void survivesWhenEverythingIsTooOld() throws IOException {
        // 픽스처 기준 시각에서 한 달을 흘려보낸 시계
        FeedFetcher stale = new FeedFetcher(RestClient.builder(),
                properties(Map.of(NewsSource.CNBC, feed("/cnbc", FeedType.RSS))),
                CircuitBreakerRegistry.ofDefaults(),
                TestRetries.registry(),
                java.time.Clock.fixed(CLOCK.instant().plus(Duration.ofDays(30)),
                        java.time.ZoneOffset.UTC),
                MAX_AGE,
                List.of(new RssFeedClient(), new GoogleNewsFeedClient()));
        stubFeed("/cnbc", 200, fixture("cnbc.xml"));

        assertThat(stale.fetch(NewsSource.CNBC)).isEmpty();
    }

    @Test
    @DisplayName("403이면 예외가 아니라 빈 리스트 — 호출자가 나머지 매체를 계속 돌 수 있다")
    void returnsEmptyOnForbidden() {
        stubFeed("/blocked", 403, "<html>Access Denied</html>");

        List<Article> articles = fetcher(Map.of(
                NewsSource.YAHOO_FINANCE, feed("/blocked", FeedType.RSS)))
                .fetch(NewsSource.YAHOO_FINANCE);

        assertThat(articles).isEmpty();
    }

    @Test
    @DisplayName("응답이 RSS가 아니어도 빈 리스트로 강등한다")
    void returnsEmptyOnGarbageBody() {
        stubFeed("/garbage", 200, "이건 XML이 아닙니다");

        assertThat(fetcher(Map.of(NewsSource.BBC, feed("/garbage", FeedType.RSS)))
                .fetch(NewsSource.BBC))
                .isEmpty();
    }

    @Test
    @DisplayName("설정에 없는 매체는 조용히 건너뛴다")
    void returnsEmptyWhenSourceNotConfigured() {
        assertThat(fetcher(Map.of()).fetch(NewsSource.INVESTING)).isEmpty();
    }

    @Test
    @DisplayName("서킷브레이커가 소스별로 따로 열린다 — 한 매체 장애가 다른 매체를 끊지 않는다")
    void circuitBreakersAreIsolatedPerSource() throws IOException {
        stubFeed("/broken", 500, "boom");
        stubFeed("/healthy", 200, fixture("investing.xml"));

        // 기본 설정은 minimumNumberOfCalls=100이라 몇 번 호출로는 브레이커가 평가조차 되지 않는다.
        // 테스트에서 관측 가능하도록 창을 좁힌다.
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(5)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(5))
                        .build());

        FeedFetcher fetcher = fetcher(Map.of(
                NewsSource.YAHOO_FINANCE, feed("/broken", FeedType.RSS),
                NewsSource.INVESTING, feed("/healthy", FeedType.RSS)), registry);

        // 고장난 매체를 반복 호출해 그쪽 브레이커를 연다
        for (int i = 0; i < 10; i++) {
            fetcher.fetch(NewsSource.YAHOO_FINANCE);
        }

        assertThat(fetcher.fetch(NewsSource.INVESTING))
                .as("한 매체 브레이커가 열려도 다른 매체는 그대로 수집돼야 한다")
                .isNotEmpty();
        assertThat(registry.circuitBreaker("feed-YAHOO_FINANCE").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.circuitBreaker("feed-INVESTING").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("CoinDesk 피드를 읽는다 — 코인 다섯 자리를 채우려고 붙인 매체다")
    void parsesCoinDeskFeed() {
        stubFeed("/coindesk", 200, fixture("coindesk.xml"));

        List<Article> articles = fetcherAt(Map.of(
                NewsSource.COINDESK, feed("/coindesk", FeedType.RSS)), CRYPTO_CLOCK)
                .fetch(NewsSource.COINDESK);

        assertThat(articles).hasSize(4);
        assertThat(articles).allSatisfy(article ->
                assertThat(article.link()).contains("coindesk.com"));
        // 요약문이 처음부터 평문이다 — 번역 입력으로 그대로 쓸 수 있다
        assertThat(articles).allSatisfy(article ->
                assertThat(article.description()).isNotBlank());
    }

    @Test
    @DisplayName("Cointelegraph 요약문의 <p><img> 마크업은 걷힌다 — 번역 입력에 태그가 섞이면 안 된다")
    void stripsMarkupFromCointelegraphDescriptions() {
        // 실측(2026-08-27): 이 매체의 description은 <p style=…><img src=…> 블록으로 시작한다.
        // RssFeedClient.clean()이 이미 태그를 걷어내므로 파서를 손볼 것이 없다는 사실을 고정한다
        stubFeed("/cointelegraph", 200, fixture("cointelegraph.xml"));

        List<Article> articles = fetcherAt(Map.of(
                NewsSource.COINTELEGRAPH, feed("/cointelegraph", FeedType.RSS)), CRYPTO_CLOCK)
                .fetch(NewsSource.COINTELEGRAPH);

        assertThat(articles).hasSize(3);
        assertThat(articles).allSatisfy(article -> {
            assertThat(article.description()).doesNotContain("<").doesNotContain("img src");
            assertThat(article.description()).isNotBlank();
        });
    }

    @Test
    @DisplayName("코인 매체는 자기 기자가 쓴다 — 페이월 재게재 필터에 걸리지 않는다")
    void cryptoOutletsSurviveThePaywallSyndicationFilter() {
        // Investing.com이 Reuters 기사를 자기 도메인에 얹어 내는 것과 달라서, 이 둘은
        // author가 자사 기자다. 걸리면 피드가 통째로 비어 코인 자리가 안 찬다
        stubFeed("/coindesk", 200, fixture("coindesk.xml"));
        stubFeed("/cointelegraph", 200, fixture("cointelegraph.xml"));

        FeedFetcher fetcher = fetcherAt(Map.of(
                NewsSource.COINDESK, feed("/coindesk", FeedType.RSS),
                NewsSource.COINTELEGRAPH, feed("/cointelegraph", FeedType.RSS)), CRYPTO_CLOCK);

        assertThat(fetcher.fetch(NewsSource.COINDESK)).isNotEmpty();
        assertThat(fetcher.fetch(NewsSource.COINTELEGRAPH)).isNotEmpty();
    }

    private void stubFeed(String path, int status, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(
                aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/xml; charset=utf-8")
                        .withBody(body)));
    }

    private Feed feed(String path, FeedType type) {
        return new Feed(server.baseUrl() + path, type);
    }

    private FeedFetcher fetcher(Map<NewsSource, Feed> feeds) {
        return fetcher(feeds, CircuitBreakerRegistry.ofDefaults());
    }

    private EconomyHelperProperties properties(Map<NewsSource, Feed> feeds) {
        Map<NewsSource, Feed> copy = new EnumMap<>(NewsSource.class);
        copy.putAll(feeds);
        // 수집은 digest·캐시TTL·날씨·market 설정을 쓰지 않는다 — 안 채우는 것이 그 사실의 표현이다
        return TestProperties.builder()
                .feeds(copy)
                .ranking(new Ranking(new Weights(0.35, 0.25, 0.25, 0.15), Duration.ofHours(6)))
                .build();  // market 설정(KIS 지수 표)도 마찬가지
    }

    private FeedFetcher fetcher(Map<NewsSource, Feed> feeds, CircuitBreakerRegistry registry) {
        return fetcher(feeds, registry, CLOCK);
    }

    /** 픽스처를 뜬 날이 다른 매체용 — 시계만 갈아 끼운다. */
    private FeedFetcher fetcherAt(Map<NewsSource, Feed> feeds, java.time.Clock clock) {
        return fetcher(feeds, CircuitBreakerRegistry.ofDefaults(), clock);
    }

    private FeedFetcher fetcher(Map<NewsSource, Feed> feeds, CircuitBreakerRegistry registry,
                                java.time.Clock clock) {
        return new FeedFetcher(
                RestClient.builder(),
                properties(feeds),
                registry,
                TestRetries.registry(),
                clock,
                MAX_AGE,
                List.of(new RssFeedClient(), new GoogleNewsFeedClient()));
    }

    private static String fixture(String name) {
        return TestFixtures.text("fixtures/" + name);
    }
}
