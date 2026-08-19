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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 호출을 WireMock으로 세워 외부 네트워크와 API 키 없이 검증한다. */
class GeminiTranslatorTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        // h2c를 끈다 — JDK HttpClient가 HTTP/2를 먼저 시도하는데 WireMock의 평문 h2 구현과
        // POST 본문에서 충돌한다. 실제 Gemini 서버에서는 나지 않는 문제다.
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
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

    /** Gemini는 번역 결과를 parts[0].text 안에 문자열로 담아 준다. */
    private static String geminiResponse(String escapedText) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escapedText + "\"}]}}]}";
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
