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
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TelegramClientTest {

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        // h2c를 끈다 — JDK HttpClient가 HTTP/2를 먼저 시도하는데 WireMock의 평문 h2 구현과
        // POST 본문에서 충돌한다. 실제 Bot API 서버에서는 나지 않는 문제다.
        server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    /**
     * ⚠️ <b>순서가 값이다.</b> {@code resetAll()}이 기본 스텁보다 <b>먼저</b> 와야 한다 —
     * 거꾸로 두면 방금 깐 스텁을 지워 이 클래스가 통째로 무너진다.
     *
     * <p>기본 스텁을 테스트마다 다시 깔아야 하는 이유가 그것이다. 이 클래스는 요청 기록에
     * 가장 많이 기대는데({@code getAllServeEvents().get(0)} · {@code findAll(...).get(0)}),
     * 그 인덱스 0은 「이 테스트가 낸 첫 요청」을 뜻하므로 비우지 않으면 앞 테스트 것을 읽는다.
     */
    @BeforeEach
    void resetAndStubOk() {
        server.resetAll();
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")));
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
                RestClient.builder(), server.baseUrl(), "test-token", "default-chat", "토픽3", Duration.ZERO))
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
        return client(Duration.ZERO);
    }

    private TelegramClient client(Duration minInterval) {
        return new TelegramClient(
                RestClient.builder(), server.baseUrl(), "test-token", "default-chat", "3", minInterval);
    }

    @Test
    @DisplayName("같은 방의 두 통은 발송 시작이 간격 이상 벌어진다 — 초당 한 통 권고를 클라이언트가 지킨다")
    void pacesConsecutiveMessagesToTheSameChat() {
        TelegramClient paced = client(Duration.ofMillis(400));

        long started = System.nanoTime();
        paced.send("12345", null, null, "첫 통");
        paced.send("12345", null, null, "둘째 통");
        paced.sendPhoto("12345", null, null, new byte[] {1}, "사진");

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("셋째 통의 시작은 첫 통에서 간격 두 배 뒤다")
                .isGreaterThanOrEqualTo(Duration.ofMillis(800));
    }

    @Test
    @DisplayName("간격을 기다리다 인터럽트되면 보내지 않고 던진다 — 종료 중 남은 통이 연달아 나가면 429를 우리가 만든다")
    void refusesToSendWhenInterruptedWhilePacing() {
        TelegramClient paced = client(Duration.ofSeconds(2));
        paced.send("12345", null, null, "첫 통");

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> paced.send("12345", null, null, "둘째 통"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("중단");
        } finally {
            assertThat(Thread.interrupted()).as("인터럽트 플래그는 삼키지 않고 되살린다").isTrue();
        }
        assertThat(server.findAll(postRequestedFor(anyUrl()))).as("둘째 통은 나가지 않았다").hasSize(1);
    }

    @Test
    @DisplayName("다른 방으로 가는 통은 서로 기다리지 않는다 — 권고가 방 단위다")
    void neverPacesAcrossChats() {
        TelegramClient paced = client(Duration.ofSeconds(3));

        long started = System.nanoTime();
        paced.send("12345", null, null, "이 방");
        paced.send("67890", null, null, "저 방");

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("두 방이면 간격을 한 번도 기다리지 않는다")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("빈 사진은 간격도 쓰지 않는다 — 보내지 않는 것에 줄을 세울 이유가 없다")
    void emptyPhotoNeitherSendsNorPaces() {
        TelegramClient paced = client(Duration.ofSeconds(3));

        long started = System.nanoTime();
        paced.sendPhoto("12345", null, null, new byte[0], "없는 사진");
        paced.send("12345", null, null, "글");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(server.findAll(postRequestedFor(anyUrl()))).hasSize(1);
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

    @Test
    @DisplayName("태그가 열린 채 잘려도 4,096자를 넘지 않는다 — 넘기면 텔레그램이 통째로 거절한다")
    void neverExceedsTheLimitEvenAfterClosingTags() {
        // 뉴스 통의 실제 모양이다. 예전에는 4,095자로 자르고 "…"를 붙여 예산을 다 쓴 뒤
        // 닫는 태그를 더 붙여 4,117자가 나갔고, 400으로 통째로 유실됐다 — 잘라 보내기가
        // 존재하는 이유("전부 실패하는 것보다 일부라도")를 그 함수가 스스로 깨뜨렸다
        String newsShaped = "<a href=\"https://example.com/a\"><b>제목</b></a>\n\n<blockquote>"
                + "가".repeat(5000);

        String truncated = TelegramClient.truncate(newsShaped);

        assertThat(truncated.length()).isLessThanOrEqualTo(4096);
        assertThat(truncated).endsWith("</blockquote>");
    }

    @Test
    @DisplayName("서로게이트 쌍을 쪼개지 않는다 — 등락률 이모지가 보조 평면 문자다")
    void neverSplitsASurrogatePair() {
        // 🔴(U+1F534)가 자를 경계에 놓이게 만든다. 쪼개면 깨진 문자가 나간다
        String body = "가".repeat(4094) + "🔴" + "나".repeat(100);

        String truncated = TelegramClient.truncate(body);

        assertThat(truncated.length()).isLessThanOrEqualTo(4096);
        // 고아 서로게이트가 하나도 남지 않았는가 — 쌍을 이루지 못한 하이 서로게이트를 찾는다
        boolean orphan = false;
        for (int i = 0; i < truncated.length(); i++) {
            if (Character.isHighSurrogate(truncated.charAt(i))
                    && (i + 1 >= truncated.length() || !Character.isLowSurrogate(truncated.charAt(i + 1)))) {
                orphan = true;
            }
        }
        assertThat(orphan).as("고아 서로게이트가 남으면 깨진 문자가 나간다").isFalse();
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }


    @Test
    @DisplayName("사진은 multipart로 나가고 파일 이름이 붙는다 — 없으면 파일 업로드로 안 받는다")
    void sendsPhotoAsMultipart() {
        client().sendPhoto("12345", 42, 7, new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "환율 최근 14일");

        com.github.tomakehurst.wiremock.verification.LoggedRequest sent =
                server.findAll(postRequestedFor(urlPathEqualTo("/bottest-token/sendPhoto")))
                        .get(0);
        String body = sent.getBodyAsString();

        assertThat(sent.getHeader("Content-Type")).startsWith("multipart/form-data");
        assertThat(body).as("파일 이름이 없으면 텔레그램이 파일로 받지 않는다")
                .contains("filename=\"chart.png\"");
        assertThat(body).contains("환율 최근 14일");
        assertThat(body).contains("message_thread_id").contains("42");
        assertThat(body).contains("reply_to_message_id").contains("7");
    }

    @Test
    @DisplayName("토픽이 없으면 그 필드 자체가 없다 — null을 실으면 텔레그램이 거절한다")
    void omitsThreadIdFromPhotoWhenAbsent() {
        client().sendPhoto("12345", null, null, new byte[] {1, 2, 3}, "설명");

        String body = server.findAll(postRequestedFor(
                urlPathEqualTo("/bottest-token/sendPhoto"))).get(0).getBodyAsString();

        assertThat(body).doesNotContain("message_thread_id");
        assertThat(body).doesNotContain("reply_to_message_id");
    }

    @Test
    @DisplayName("빈 그림은 보내지 않는다 — 점이 하나뿐인 계열이 그렇다")
    void neverSendsAnEmptyPhoto() {
        client().sendPhoto("12345", null, null, new byte[0], "설명");
        client().sendPhoto("12345", null, null, null, "설명");

        server.verify(0, postRequestedFor(urlPathEqualTo("/bottest-token/sendPhoto")));
    }

    @Test
    @DisplayName("caption은 1024자에서 자른다 — 메시지 상한(4096)으로 자르면 400을 맞는다")
    void truncatesCaptionAtItsOwnLimit() {
        // ⚠️ 이 상한을 메시지와 같은 것으로 착각하면 사진이 통째로 안 나간다.
        //    4096자를 넘지 않으므로 truncate(text) 쪽으로는 안 걸리는 길이를 쓴다
        String tooLong = "가".repeat(2000);

        client().sendPhoto("12345", null, null, new byte[] {1}, tooLong);

        String body = server.findAll(postRequestedFor(
                urlPathEqualTo("/bottest-token/sendPhoto"))).get(0).getBodyAsString();
        int captionLength = captionOf(body).length();

        assertThat(captionLength)
                .as("caption이 1024자를 넘으면 텔레그램이 400으로 사진을 거절한다")
                .isLessThanOrEqualTo(1024);
        assertThat(captionLength).as("2000자가 1024 근처로 줄어야 한다").isGreaterThan(900);
    }

    /** multipart 본문에서 caption 파트의 값만 꺼낸다. */
    private static String captionOf(String multipart) {
        int marker = multipart.indexOf("name=\"caption\"");
        assertThat(marker).as("caption 파트가 없다").isGreaterThan(-1);
        int start = multipart.indexOf("\r\n\r\n", marker) + 4;
        int end = multipart.indexOf("\r\n--", start);
        return multipart.substring(start, end < 0 ? multipart.length() : end);
    }
}
