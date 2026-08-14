package io.saiden.economyhelper.news.feed;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 수집의 장애 격리를 고정한다 — 여기가 "한 매체가 죽어도 나머지는 발송된다"의 실제 지점이다.
 *
 * <p>WireMock으로 매체를 흉내 내므로 외부 네트워크를 타지 않는다.
 */
class FeedFetcherTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("정상 응답이면 파싱해서 돌려준다")
    void parsesSuccessfulResponse() throws IOException {
        stubFeed("/bloomberg", 200, fixture("cnbc.xml"));

        List<Article> articles = fetcher(Map.of(
                NewsSource.CNBC, feed("/bloomberg", FeedType.RSS)))
                .fetch(NewsSource.CNBC);

        assertThat(articles).hasSize(20);
        assertThat(articles.get(0).source()).isEqualTo(NewsSource.CNBC);
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

    private FeedFetcher fetcher(Map<NewsSource, Feed> feeds, CircuitBreakerRegistry registry) {
        Map<NewsSource, Feed> copy = new EnumMap<>(NewsSource.class);
        copy.putAll(feeds);
        EconomyHelperProperties properties = new EconomyHelperProperties(
                copy,
                new Ranking(new Weights(0.35, 0.25, 0.25, 0.15), Duration.ofHours(6)),
                null,   // 수집은 digest 설정을 쓰지 않는다
                null,   // 캐시 TTL도 마찬가지 (여기선 @Cacheable이 프록시 없이 지나간다)
                null);  // 시세 API 설정도 수집과 무관하다

        return new FeedFetcher(
                RestClient.builder(),
                properties,
                registry,
                List.of(new RssFeedClient(), new GoogleNewsFeedClient()));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = FeedFetcherTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("픽스처 %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
