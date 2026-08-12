package io.saiden.economyhelper.news.rank;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.translate.GeminiApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 두 가지가 요점이다.
 *
 * <ol>
 *   <li><b>호출을 한 번으로 묶는다</b> — 기사마다 물으면 무료 티어를 태운다
 *   <li><b>실패하면 키워드 사전으로 내려간다</b> — 이 신호가 사라지면 일반 뉴스가 1위로 뽑힌다
 *       (Phase 1의 8단계에서 실제로 겪은 버그다)
 * </ol>
 */
class RelevanceScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static Article article(int rank, String title) {
        return new Article(NewsSource.FT, title, "본문", "https://example.com/" + rank, NOW, rank);
    }

    private static final List<Article> CANDIDATES = List.of(
            article(0, "Fed signals caution on rate cuts"),
            article(1, "Airport queues double after new EU border checks"),
            article(2, "Dollar strength pressures emerging markets"));

    @Test
    @DisplayName("후보 전체를 한 번의 호출로 채점한다 — 기사마다 물으면 무료 티어를 태운다")
    void scoresWholeBatchInOneCall() {
        RecordingApi api = new RecordingApi("{\"scores\":[0.95,0.1,0.8]}");

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES, List.of());

        assertThat(api.calls).hasValue(1);
        assertThat(scores).hasSize(3);
        assertThat(scores.get("https://example.com/0")).isEqualTo(0.95);
        assertThat(scores.get("https://example.com/1")).isEqualTo(0.1);
    }

    @Test
    @DisplayName("입력 순서대로 짝을 맞춘다 — 어긋나면 엉뚱한 기사가 1위가 된다")
    void mapsScoresInInputOrder() {
        RecordingApi api = new RecordingApi("{\"scores\":[0.1,0.2,0.9]}");

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES, List.of());

        assertThat(scores.get("https://example.com/2")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("응답 개수가 다르면 폴백한다 — 짝을 잘못 맞추느니 키워드가 낫다")
    void fallsBackWhenCountMismatches() {
        RecordingApi api = new RecordingApi("{\"scores\":[0.9,0.1]}");   // 3개인데 2개만 왔다

        Map<String, Double> scores =
                new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES, List.of(KeywordGroup.of("rate")));

        // 키워드 'rate'가 걸리는 1번 기사만 점수를 받는다
        assertThat(scores.get("https://example.com/0")).isGreaterThan(0.0);
        assertThat(scores.get("https://example.com/1")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Gemini가 죽으면 키워드 사전으로 내려간다 — 발송이 멈추면 안 된다")
    void fallsBackToKeywordsWhenLlmFails() {
        GeminiApi exploding = new RecordingApi(null) {
            @Override
            public String generate(String prompt) {
                throw new IllegalStateException("서킷브레이커 열림");
            }
        };

        Map<String, Double> scores = new RelevanceScorer(exploding, MAPPER)
                .scoreAll(CANDIDATES, List.of(KeywordGroup.of("dollar")));

        assertThat(scores).hasSize(3);
        assertThat(scores.get("https://example.com/2")).isGreaterThan(0.0);   // dollar가 걸린다
        assertThat(scores.get("https://example.com/1")).isEqualTo(0.0);       // 공항 기사는 안 걸린다
    }

    @Test
    @DisplayName("응답이 JSON이 아니어도 폴백한다")
    void fallsBackOnGarbageResponse() {
        RecordingApi api = new RecordingApi("죄송합니다, 채점할 수 없습니다");

        assertThat(new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES, List.of()))
                .hasSize(3);
    }

    @Test
    @DisplayName("0~1을 벗어난 값은 잘라낸다 — 가중 합이 망가지면 안 된다")
    void clampsOutOfRangeScores() {
        RecordingApi api = new RecordingApi("{\"scores\":[1.7,-0.5,0.5]}");

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES, List.of());

        assertThat(scores.get("https://example.com/0")).isEqualTo(1.0);
        assertThat(scores.get("https://example.com/1")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("후보가 없으면 호출하지 않는다")
    void skipsCallWhenNoCandidates() {
        RecordingApi api = new RecordingApi("{\"scores\":[]}");

        assertThat(new RelevanceScorer(api, MAPPER).scoreAll(List.of(), List.of())).isEmpty();
        assertThat(api.calls).hasValue(0);
    }

    /** 프롬프트가 실제로 어떻게 나가는지는 여기서 보지 않는다 — 호출 횟수와 파싱만 본다. */
    private static class RecordingApi extends GeminiApi {
        private final String response;
        private final AtomicInteger calls = new AtomicInteger();

        RecordingApi(String response) {
            super(RestClient.builder(), "https://example.invalid", "key", "model");
            this.response = response;
        }

        @Override
        public String generate(String prompt) {
            calls.incrementAndGet();
            return response;
        }
    }
}
