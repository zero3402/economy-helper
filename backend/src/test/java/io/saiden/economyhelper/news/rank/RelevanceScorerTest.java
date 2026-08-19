package io.saiden.economyhelper.news.rank;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.llm.GeminiApi;
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
 *   <li><b>실패해도 발송을 막지 않는다</b> — 전부 통과시킨다. 예전에는 키워드 사전으로
 *       내려갔지만, 피드를 전부 금융 섹션으로 좁힌 뒤로는 후보 자체가 재테크 기사다
 * </ol>
 */
class RelevanceScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static Article article(int rank, String title) {
        return new Article(NewsSource.YAHOO_FINANCE, title, "본문", "https://example.com/" + rank, NOW, rank);
    }

    private static final List<Article> CANDIDATES = List.of(
            article(0, "Fed signals caution on rate cuts"),
            article(1, "Airport queues double after new EU border checks"),
            article(2, "Dollar strength pressures emerging markets"));

    @Test
    @DisplayName("후보 전체를 한 번의 호출로 채점한다 — 기사마다 물으면 무료 티어를 태운다")
    void scoresWholeBatchInOneCall() {
        RecordingApi api = new RecordingApi("{\"scores\":[0.95,0.1,0.8]}");

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES);

        assertThat(api.calls).hasValue(1);
        assertThat(scores).hasSize(3);
        assertThat(scores.get("https://example.com/0")).isEqualTo(0.95);
        assertThat(scores.get("https://example.com/1")).isEqualTo(0.1);
    }

    @Test
    @DisplayName("입력 순서대로 짝을 맞춘다 — 어긋나면 엉뚱한 기사가 1위가 된다")
    void mapsScoresInInputOrder() {
        RecordingApi api = new RecordingApi("{\"scores\":[0.1,0.2,0.9]}");

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES);

        assertThat(scores.get("https://example.com/2")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("응답 개수가 다르면 폴백한다 — 짝을 잘못 맞추면 엉뚱한 기사가 1위가 된다")
    void fallsBackWhenCountMismatches() {
        RecordingApi api = new RecordingApi("{\"scores\":[0.9,0.1]}");   // 3개인데 2개만 왔다

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES);

        assertThat(scores).hasSize(3).allSatisfy((link, score) -> assertThat(score).isEqualTo(1.0));
    }

    @Test
    @DisplayName("Gemini가 죽으면 전부 통과시킨다 — 피드가 이미 금융 전용이라 걸러낼 필요가 없다")
    void passesAllWhenLlmFails() {
        GeminiApi exploding = new RecordingApi(null) {
            @Override
            public String generate(String prompt) {
                throw new IllegalStateException("서킷브레이커 열림");
            }
        };

        Map<String, Double> scores = new RelevanceScorer(exploding, MAPPER).scoreAll(CANDIDATES);

        // 임계값(0.4)을 넘겨 발송이 멈추지 않아야 한다. 후보 자체가 금융 섹션 피드에서 왔다
        assertThat(scores).hasSize(3).allSatisfy((link, score) -> assertThat(score).isEqualTo(1.0));
    }

    @Test
    @DisplayName("응답이 JSON이 아니어도 폴백한다")
    void fallsBackOnGarbageResponse() {
        RecordingApi api = new RecordingApi("죄송합니다, 채점할 수 없습니다");

        assertThat(new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES))
                .hasSize(3);
    }

    @Test
    @DisplayName("0~1을 벗어난 값은 잘라낸다 — 가중 합이 망가지면 안 된다")
    void clampsOutOfRangeScores() {
        RecordingApi api = new RecordingApi("{\"scores\":[1.7,-0.5,0.5]}");

        Map<String, Double> scores = new RelevanceScorer(api, MAPPER).scoreAll(CANDIDATES);

        assertThat(scores.get("https://example.com/0")).isEqualTo(1.0);
        assertThat(scores.get("https://example.com/1")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("후보가 없으면 호출하지 않는다")
    void skipsCallWhenNoCandidates() {
        RecordingApi api = new RecordingApi("{\"scores\":[]}");

        assertThat(new RelevanceScorer(api, MAPPER).scoreAll(List.of())).isEmpty();
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
