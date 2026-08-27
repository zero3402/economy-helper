package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.llm.GeminiApi;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 호출을 WireMock으로 세워 외부 네트워크와 API 키 없이 검증한다. */
class GeminiTranslatorTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        // h2c를 끈다 — JDK HttpClient가 HTTP/2를 먼저 시도하는데 WireMock의 평문 h2 구현과
        // POST 본문에서 충돌한다. 실제 Gemini 서버에서는 나지 않는 문제다.
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetStubs() {
        // 스텁·요청기록·시나리오를 함께 비운다 — 서버는 그대로 두고 상태만 되돌린다
        server.resetAll();
    }

    @Test
    @DisplayName("정상 응답이면 한국어 제목·본문을 뽑아낸다")
    void extractsKoreanTranslation() {
        stubGemini(200, geminiResponse(
                "{\\\"title\\\": \\\"유가, 4일 상승분 유지\\\", \\\"body\\\": \\\"인플레이션 우려가 되살아났다.\\\"}"));

        Translation translation = translator().translate(article("Oil holds advance", "Oil kept its gains."));

        assertThat(translation.translated()).isTrue();
        assertThat(translation.title()).isEqualTo("유가, 4일 상승분 유지");
        assertThat(translation.body()).isEqualTo("인플레이션 우려가 되살아났다.");
    }

    @Test
    @DisplayName("API 키를 쿼리가 아니라 헤더로 보낸다 — URL은 로그에 남는다")
    void sendsApiKeyAsHeader() {
        stubGemini(200, geminiResponse("{\\\"title\\\": \\\"제목\\\", \\\"body\\\": \\\"\\\"}"));

        translator().translate(article("Title", "Body"));

        server.verify(postRequestedFor(anyUrl())
                .withHeader("x-goog-api-key", equalTo("test-key")));
    }

    @Test
    @DisplayName("429면 예외를 던진다 — 폴백 판단은 TranslationService의 몫이다")
    void throwsOnRateLimit() {
        stubGemini(429, "{\"error\":{\"message\":\"Quota exceeded\"}}");

        assertThatThrownBy(() -> translator().translate(article("Title", "Body")))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("candidates가 비면 예외 — 조용히 빈 번역을 내보내지 않는다")
    void throwsOnEmptyCandidates() {
        stubGemini(200, "{\"candidates\":[]}");

        assertThatThrownBy(() -> translator().translate(article("Title", "Body")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidates");
    }

    @Test
    @DisplayName("모델이 JSON이 아닌 걸 뱉으면 예외 — 깨진 텍스트를 발송하지 않는다")
    void throwsWhenModelReturnsNonJson() {
        stubGemini(200, geminiResponse("죄송합니다, 번역할 수 없습니다"));

        assertThatThrownBy(() -> translator().translate(article("Title", "Body")))
                .isInstanceOf(Exception.class);
    }

    private void stubGemini(int status, String body) {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    /**
     * 배치 응답 하나를 세운다 — <b>본문 JSON을 손으로 이스케이프하지 않는다.</b>
     *
     * <p>Gemini는 결과를 {@code parts[0].text} 안에 <b>문자열로</b> 담아 주므로 따옴표가
     * 두 겹으로 새어 나간다. 케이스마다 손으로 이스케이프하면 그게 곧 오탈자 자리가 된다.
     */
    private void stubBatch(String articlesJson) {
        stubGemini(200, geminiResponse(articlesJson.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ")));
    }

    /** Gemini는 번역 결과를 parts[0].text 안에 문자열로 담아 준다. */
    private static String geminiResponse(String escapedText) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escapedText + "\"}]}}]}";
    }

    @Test
    @DisplayName("배치 응답이 한 건 모자라면 예외 — 개수가 어긋나면 기사에 남의 번역이 붙는다")
    void rejectsAShortBatch() {
        // ⚠️ 이 검사가 이 저장소에서 가장 조용히 위험한 자리를 지킨다.
        //    NewsFacade가 기사와 번역을 **인덱스로** 짝짓고(translations.get(i)),
        //    TranslationService.putAll도 fresh.get(i)로 zip한다 — 그 zip은 try 밖이다.
        //    개수가 모자라면 IndexOutOfBounds가 뉴스 통을 통째로 죽이고, 개수는 맞는데
        //    순서만 어긋나면 **기사 A에 B의 한글**이 붙는다. 화면이 그럴듯해서 아무도 모른다.
        //    LLM이 항목을 빼먹는 것은 흔한 일이라 이 검사를 완화하면 안 된다
        stubBatch("""
                {"articles":[{"title":"기름값 상승","body":"본문"}]}""");

        assertThatThrownBy(() -> translator().translateAll(java.util.List.of(
                article("Oil holds advance", "Oil kept its gains."),
                article("Gold slips", "Gold eased."))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기대 2")
                .hasMessageContaining("실제 1");
    }

    @Test
    @DisplayName("배치 응답이 한 건 남아도 예외 — 많은 쪽도 짝이 어긋난 것이다")
    void rejectsAnOverlongBatch() {
        stubBatch("""
                {"articles":[{"title":"하나","body":"본문"},{"title":"둘","body":"본문"},
                             {"title":"셋","body":"본문"}]}""");

        assertThatThrownBy(() -> translator().translateAll(java.util.List.of(
                article("Oil holds advance", "Oil kept its gains."),
                article("Gold slips", "Gold eased."))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기대 2");
    }

    @Test
    @DisplayName("articles 필드가 없으면 예외 — 빈 번역을 조용히 내보내지 않는다")
    void rejectsABatchWithoutArticles() {
        stubBatch("{\"other\":[]}");

        assertThatThrownBy(() -> translator().translateAll(java.util.List.of(
                article("Oil holds advance", "Oil kept its gains."),
                article("Gold slips", "Gold eased."))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없음");
    }

    @Test
    @DisplayName("개수는 맞는데 title이 비면 예외 — 빈 제목이 기사 자리를 차지하면 안 된다")
    void rejectsABatchWithABlankTitle() {
        stubBatch("""
                {"articles":[{"title":"기름값 상승","body":"본문"},{"title":"  ","body":"본문"}]}""");

        assertThatThrownBy(() -> translator().translateAll(java.util.List.of(
                article("Oil holds advance", "Oil kept its gains."),
                article("Gold slips", "Gold eased."))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("배치가 입력 순서를 그대로 지킨다 — 순서가 뒤바뀌면 기사에 남의 번역이 붙는다")
    void keepsTheInputOrder() {
        stubBatch("""
                {"articles":[{"title":"기름값 상승","body":"첫째"},{"title":"금값 하락","body":"둘째"}]}""");

        assertThat(translator().translateAll(java.util.List.of(
                article("Oil holds advance", "Oil kept its gains."),
                article("Gold slips", "Gold eased."))))
                .extracting(Translation::title)
                .containsExactly("기름값 상승", "금값 하락");
    }

    private GeminiTranslator translator() {
        return new GeminiTranslator(
                new GeminiApi(RestClient.builder(), server.baseUrl(), "test-key",
                        "gemini-flash-lite-latest"),
                JsonMapper.builder().build());
    }

    private static Article article(String title, String description) {
        return new Article(NewsSource.CNBC, title, description,
                "https://example.com/" + title.hashCode(), NOW, 0);
    }
}
