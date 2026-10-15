package io.saiden.economyhelper.infrastructure.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.testsupport.WireMockTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Gemini 전송 골격 — 번역기 둘과 해석기 넷이 전부 이 한 메서드를 거친다.
 *
 * <p>프롬프트·파싱은 각 호출자의 테스트가 본다. 여기서는 <b>부를지 말지</b>와 응답의 글을 그대로 주는지만 본다.
 */
@WireMockTest.WireMockOptions(http2PlainDisabled = true)
class GeminiApiTest extends WireMockTest {

    @Test
    @DisplayName("키가 비어 있으면 부르지 않고 던진다 — 매번 거절당하러 왕복하며 리미터 허용량과 브레이커 실패를 쌓던 것")
    void refusesToCallWithoutAKey() {
        GeminiApi api = api("");

        assertThatThrownBy(() -> api.generate("p"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("키");
        server.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("키가 있으면 응답 본문의 글을 그대로 준다")
    void returnsTheGeneratedText() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]}}]}")));

        assertThat(api("test-key").generate("p")).isEqualTo("{\"ok\":true}");
        server.verify(1, postRequestedFor(anyUrl()));
    }

    private GeminiApi api(String apiKey) {
        return new GeminiApi(RestClient.builder(), TestProperties.builder()
                .gemini(server.baseUrl(), apiKey, "test-model").build());
    }
}
