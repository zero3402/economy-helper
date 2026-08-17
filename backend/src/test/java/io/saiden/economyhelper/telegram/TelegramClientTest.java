package io.saiden.economyhelper.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        client().send("12345", null, null, "안녕하세요");

        server.verify(postRequestedFor(urlPathEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(equalToJson("""
                        {"chat_id":"12345","text":"안녕하세요","parse_mode":"HTML","disable_web_page_preview":true}""")));
    }

    @Test
    @DisplayName("토픽을 주면 message_thread_id로 실어 보낸다 — 포럼에서 그 토픽에 뜬다")
    void carriesForumTopicId() {
        client().send("12345", 7, null, "안녕하세요");

        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"chat_id":"12345","message_thread_id":7,"text":"안녕하세요",\
                        "parse_mode":"HTML","disable_web_page_preview":true}""")));
    }

    @Test
    @DisplayName("토픽이 없으면 필드 자체를 뺀다 — null을 실으면 포럼이 아닌 방에서 거절당한다")
    void omitsThreadIdEntirelyWhenAbsent() {
        client().send("12345", null, null, "안녕하세요");

        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"chat_id":"12345","text":"안녕하세요","parse_mode":"HTML","disable_web_page_preview":true}""")));
        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .as("키가 null로라도 남아 있으면 안 된다").doesNotContain("message_thread_id");
    }

    @Test
    @DisplayName("chat_id를 생략하면 설정된 기본 채팅방의 Notice 토픽으로 간다 — 정기 발송 경로")
    void usesDefaultChatIdAndNoticeTopicForDigest() {
        client().send("정기 발송", false);

        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"chat_id":"default-chat","message_thread_id":3,"text":"정기 발송",\
                        "parse_mode":"HTML","disable_web_page_preview":true}""")));
    }

    @Test
    @DisplayName("답글로 달면 원 명령 번호를 실어 보낸다 — 여럿이 동시에 검색해도 답이 섞이지 않는다")
    void repliesToTheCommandThatAskedForIt() {
        client().send("12345", 7, 4821, "증시 '삼성전자'");

        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"chat_id":"12345","message_thread_id":7,"text":"증시 '삼성전자'",\
                        "parse_mode":"HTML","disable_web_page_preview":true,\
                        "reply_to_message_id":4821,"allow_sending_without_reply":true}""")));
    }

    @Test
    @DisplayName("정기 발송에는 답글을 달지 않는다 — 브리핑은 인용할 명령이 없다")
    void digestCarriesNoReplyFields() {
        client().send("정기 발송", false);

        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .as("키가 null로라도 남아 있으면 안 된다")
                .doesNotContain("reply_to_message_id")
                .doesNotContain("allow_sending_without_reply");
    }

    @Test
    @DisplayName("토픽 ID가 숫자가 아니면 기동에서 실패한다 — 발송 때 터지면 그날 브리핑을 통째로 잃는다")
    void rejectsNonNumericTopicIdAtStartup() {
        assertThatThrownBy(() -> new TelegramClient(
                RestClient.builder(), server.baseUrl(), "test-token", "default-chat", "토픽3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("토픽3");

        assertThat(TelegramClient.topicId("")).as("비면 지정하지 않는다").isNull();
        assertThat(TelegramClient.topicId(" 12 ")).as("붙여 넣은 공백은 다듬는다").isEqualTo(12);
    }

    @Test
    @DisplayName("4096자를 넘으면 잘라 보낸다 — 전부 실패하는 것보다 일부라도 가는 게 낫다")
    void truncatesOverlongMessages() {
        String tooLong = "가".repeat(5000);

        String truncated = TelegramClient.truncate(tooLong);

        assertThat(truncated).hasSize(4096).endsWith("…");
        assertThat(TelegramClient.truncate("짧은 메시지")).isEqualTo("짧은 메시지");
    }

    @Test
    @DisplayName("200 + ok:false는 실패다 — 성공으로 세면 브리핑이 안 왔는데 '발송 완료'가 남는다")
    void treatsOkFalseAsFailure() {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":false,\"error_code\":400,"
                        + "\"description\":\"Bad Request: chat not found\"}")));

        assertThatThrownBy(() -> client().send("12345", null, null, "안녕하세요"))
                .as("무엇을 고쳐야 하는지가 description에 적혀 있다 — 그 문장을 그대로 실어 올린다")
                .hasMessageContaining("chat not found")
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("4xx도 사유를 읽어 올린다 — 기본 예외 메시지만으로는 무엇이 틀렸는지 모른다")
    void carriesDescriptionOnHttpError() {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":false,\"error_code\":400,"
                        + "\"description\":\"Bad Request: message thread not found\"}")));

        assertThatThrownBy(() -> client().send("12345", 3, null, "안녕하세요"))
                .hasMessageContaining("message thread not found");
    }

    @Test
    @DisplayName("getChat으로 방이 포럼인지 확인한다 — 기동 시 자가진단이 이걸 쓴다")
    void readsChatInfo() {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":{\"id\":-1001,\"type\":\"supergroup\","
                        + "\"title\":\"경제 도우미\",\"is_forum\":true}}")));

        assertThat(client().chatInfo()).get()
                .extracting(TelegramClient.ChatInfo::title, TelegramClient.ChatInfo::isForum)
                .containsExactly("경제 도우미", true);
    }

    @Test
    @DisplayName("채팅방을 못 찾아도 예외를 올리지 않는다 — 진단이 앱을 죽이면 본말전도다")
    void chatInfoSwallowsFailure() {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"chat not found\"}")));

        assertThat(client().chatInfo()).isEmpty();
    }

    private TelegramClient client() {
        return new TelegramClient(
                RestClient.builder(), server.baseUrl(), "test-token", "default-chat", "3");
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
