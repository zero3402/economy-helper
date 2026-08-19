package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.llm.GeminiApi;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 검색어 번역을 WireMock으로 세워 외부 네트워크와 API 키 없이 검증한다. */
class QueryTranslatorTest {

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
    @DisplayName("영어 표현 목록을 뽑아낸다")
    void extractsEnglishTerms() {
        stub(200, geminiResponse("{\\\"terms\\\": [\\\"bitcoin\\\", \\\"btc\\\"]}"));

        assertThat(translator().toEnglishTerms("비트코인")).containsExactly("bitcoin", "btc");
    }

    @Test
    @DisplayName("공백·중복을 정리하고 5개로 자른다 — 묶음이 커지면 엉뚱한 기사가 걸린다")
    void trimsDeduplicatesAndCaps() {
        stub(200, geminiResponse(
                "{\\\"terms\\\": [\\\" chip \\\", \\\"chip\\\", \\\"chips\\\", \\\"a\\\", \\\"b\\\", \\\"c\\\", \\\"d\\\"]}"));

        assertThat(translator().toEnglishTerms("반도체"))
                .containsExactly("chip", "chips", "a", "b", "c");
    }

    @Test
    @DisplayName("빈 terms면 예외 — 폴백 판단은 QueryExpander의 몫이다")
    void throwsOnEmptyTerms() {
        stub(200, geminiResponse("{\\\"terms\\\": []}"));

        assertThatThrownBy(() -> translator().toEnglishTerms("금리"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비어 있습니다");
    }

    @Test
    @DisplayName("terms 필드가 없으면 예외")
    void throwsWhenTermsFieldMissing() {
        stub(200, geminiResponse("{\\\"other\\\": 1}"));

        assertThatThrownBy(() -> translator().toEnglishTerms("금리"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms");
    }

    @Test
    @DisplayName("모델이 JSON이 아닌 걸 뱉으면 예외")
    void throwsWhenModelReturnsNonJson() {
        stub(200, geminiResponse("죄송합니다, 번역할 수 없습니다"));

        assertThatThrownBy(() -> translator().toEnglishTerms("금리"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("429면 예외를 던진다")
    void throwsOnRateLimit() {
        stub(429, "{\"error\":{\"message\":\"Quota exceeded\"}}");

        assertThatThrownBy(() -> translator().toEnglishTerms("금리"))
                .isInstanceOf(Exception.class);
    }

    private void stub(int status, String body) {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String geminiResponse(String escapedText) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escapedText + "\"}]}}]}";
    }

    private QueryTranslator translator() {
        return new QueryTranslator(
                new GeminiApi(RestClient.builder(), server.baseUrl(), "test-key", "test-model"),
                JsonMapper.builder().build());
    }
}
