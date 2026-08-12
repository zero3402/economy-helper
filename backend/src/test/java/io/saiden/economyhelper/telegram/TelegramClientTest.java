package io.saiden.economyhelper.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TelegramClientTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        // h2c를 끈다 — JDK HttpClient가 HTTP/2를 먼저 시도하는데 WireMock의 평문 h2 구현과
        // POST 본문에서 충돌한다. 실제 Bot API 서버에서는 나지 않는 문제다.
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("봇 토큰을 경로에 넣고 chat_id·text를 스네이크케이스로 보낸다")
    void postsSendMessageWithSnakeCaseFields() {
        client().send("12345", "안녕하세요");

        server.verify(postRequestedFor(urlPathEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(equalToJson("""
                        {"chat_id":"12345","text":"안녕하세요","disable_web_page_preview":true}""")));
    }

    @Test
    @DisplayName("chat_id를 생략하면 설정된 기본 채팅방으로 간다 — 정기 발송 경로")
    void usesDefaultChatIdForDigest() {
        client().send("정기 발송");

        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"chat_id":"default-chat","text":"정기 발송","disable_web_page_preview":true}""")));
    }

    @Test
    @DisplayName("4096자를 넘으면 잘라 보낸다 — 전부 실패하는 것보다 일부라도 가는 게 낫다")
    void truncatesOverlongMessages() {
        String tooLong = "가".repeat(5000);

        String truncated = TelegramClient.truncate(tooLong);

        assertThat(truncated).hasSize(4096).endsWith("…");
        assertThat(TelegramClient.truncate("짧은 메시지")).isEqualTo("짧은 메시지");
    }

    private TelegramClient client() {
        return new TelegramClient(
                RestClient.builder(), server.baseUrl(), "test-token", "default-chat");
    }
}
