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
                        {"chat_id":"12345","text":"안녕하세요","parse_mode":"HTML","disable_web_page_preview":true}""")));
    }

    @Test
    @DisplayName("chat_id를 생략하면 설정된 기본 채팅방으로 간다 — 정기 발송 경로")
    void usesDefaultChatIdForDigest() {
        client().send("정기 발송");

        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"chat_id":"default-chat","text":"정기 발송","parse_mode":"HTML","disable_web_page_preview":true}""")));
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

    @Test
    @DisplayName("자를 때 태그를 깨뜨리지 않는다 — HTML 모드에서는 메시지 전체가 거절된다")
    void truncationNeverBreaksTags() {
        // 상한 근처에서 <b>가 열린 채 끊기고, 그 뒤에 태그 조각이 남는 모양
        String longBody = "가".repeat(4090);
        String truncated = TelegramClient.truncate("<b>" + longBody + "</b><a href=\"x\">링크</a>");

        assertThat(truncated).endsWith("…</b>");
        assertThat(count(truncated, "<b>")).as("여는 태그와 닫는 태그 수가 맞아야 한다")
                .isEqualTo(count(truncated, "</b>"));
        assertThat(truncated.lastIndexOf('<')).as("태그 조각이 남으면 파싱이 깨진다")
                .isLessThan(truncated.lastIndexOf('>'));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

}
