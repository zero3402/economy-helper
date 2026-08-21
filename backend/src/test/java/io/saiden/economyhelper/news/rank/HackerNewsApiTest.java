package io.saiden.economyhelper.news.rank;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * HN Algolia 조회의 <b>HTTP 절반</b> — 무엇을 묻고, 실패를 어떻게 알리는가.
 *
 * <p><b>이 파일이 없었다.</b> {@code toBuzzMap}은 {@code HackerNewsBuzzClientTest}가 순수
 * 함수로 시험했지만, 실제 호출은 언제나 페이크가 덮어써서 <b>우리가 보내는 쿼리를 아무도 보지
 * 않았다</b> — 형제 클라이언트에는 다 있는 그물이 여기만 비어 있었다. 그래서
 * {@code restrictSearchableAttributes}가 빠지거나 {@code numericFilters}의 시각이 어긋나도
 * 테스트는 초록이다.
 *
 * <p><b>가장 중요한 것은 {@link #failureReachesTheCaller()}다.</b> 이 조회의 실패는
 * <b>던져서</b> 알려야 한다 — 삼켜서 빈 맵을 주면 {@code @CircuitBreaker}가 그것을 성공으로
 * 기록하고, 그러면 브레이커가 <b>영원히 열리지 않는다.</b> 실제로 그 상태였고
 * {@code application.yml}의 {@code failureRateThreshold: 30}·{@code waitDurationInOpenState: 300s}가
 * 아무 일도 하지 않는 죽은 값이었다. 강등(buzz를 0으로 보기)은 여기가 아니라
 * {@link HackerNewsBuzzClient}가 한다 — 그래야 브레이커가 실패를 먼저 본다.
 * {@code FeedFetcher}가 같은 함정을 javadoc으로 경고해 뒀는데 옆 파일이 그것에 물렸다.
 */
class HackerNewsApiTest {

    private static final String PATH = "/api/v1/search";

    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetServer() {
        server.resetAll();
    }

    private HackerNewsApi api() {
        return new HackerNewsApi(RestClient.builder(), server.baseUrl(), 100);
    }

    @Test
    @DisplayName("실패는 호출자에게 던진다 — 삼키면 브레이커가 실패를 성공으로 기록한다")
    void failureReachesTheCaller() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        // 여기서 빈 맵이 돌아오면 @CircuitBreaker는 정상 반환을 보고 성공을 센다.
        // 그러면 "반복 실패 시 호출 자체를 끊는다"는 그 브레이커의 존재 이유가 사라진다
        assertThatThrownBy(() -> api().storiesForDomain("cnbc.com", Instant.EPOCH))
                .isInstanceOf(HttpServerErrorException.class);

        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("URL로만 좁혀 묻는다 — 본문에 도메인만 언급한 글이 섞이면 buzz가 부풀려진다")
    void asksOnlyForUrlMatches() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(json("""
                {"hits":[]}""")));

        api().storiesForDomain("cnbc.com", Instant.ofEpochSecond(1_700_000_000L));

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("query", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("cnbc.com"))
                .withQueryParam("restrictSearchableAttributes",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("url"))
                .withQueryParam("tags", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("story"))
                // 창을 넘긴 옛 반응이 섞이면 오래된 기사가 위로 올라간다
                .withQueryParam("numericFilters", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("created_at_i>1700000000"))
                .withQueryParam("hitsPerPage", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("100")));
    }

    @Test
    @DisplayName("추천과 댓글을 더해 URL별로 접는다")
    void foldsPointsAndCommentsByUrl() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(json("""
                {"hits":[
                  {"url":"https://www.cnbc.com/a/","points":10,"num_comments":5},
                  {"url":"https://cnbc.com/b","points":1,"num_comments":2}
                ]}""")));

        Map<String, Integer> buzz = api().storiesForDomain("cnbc.com", Instant.EPOCH);

        assertThat(buzz)
                .as("정규화한 URL이 키여야 피드 링크와 맞는다")
                .containsExactlyInAnyOrderEntriesOf(Map.of("cnbc.com/a", 15, "cnbc.com/b", 3));
    }

    @Test
    @DisplayName("한 기사가 여러 번 올라오면 반응이 가장 큰 쪽을 쓴다")
    void keepsTheLoudestSubmission() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(json("""
                {"hits":[
                  {"url":"https://cnbc.com/a","points":1,"num_comments":1},
                  {"url":"https://cnbc.com/a","points":40,"num_comments":2}
                ]}""")));

        assertThat(api().storiesForDomain("cnbc.com", Instant.EPOCH))
                .containsExactly(Map.entry("cnbc.com/a", 42));
    }

    @Test
    @DisplayName("외부 URL이 없는 자체 글(Ask HN)은 뺀다 — 맞출 기사가 없다")
    void skipsSelfPosts() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(json("""
                {"hits":[
                  {"url":null,"points":99,"num_comments":99},
                  {"url":"https://cnbc.com/a","points":1,"num_comments":1}
                ]}""")));

        assertThat(api().storiesForDomain("cnbc.com", Instant.EPOCH))
                .containsExactly(Map.entry("cnbc.com/a", 2));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
