package io.saiden.economyhelper.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 텔레그램 Bot API 발송. */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    /** Bot API 메시지 길이 상한. 넘기면 400이 떨어져 발송 자체가 실패한다. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final RestClient restClient;
    private final String botToken;
    private final String defaultChatId;
    private final Integer noticeTopicId;

    public TelegramClient(RestClient.Builder builder,
                          @Value("${economy-helper.telegram.base-url}") String baseUrl,
                          @Value("${economy-helper.telegram.bot-token:}") String botToken,
                          @Value("${economy-helper.telegram.chat-id:}") String defaultChatId,
                          @Value("${economy-helper.telegram.notice-topic-id:}") String noticeTopicId) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.botToken = botToken;
        this.defaultChatId = defaultChatId;
        this.noticeTopicId = topicId(noticeTopicId);
    }

    /**
     * 설정값을 토픽 번호로 읽는다. 비어 있으면 {@code null} — 토픽을 지정하지 않는다는 뜻이고,
     * 포럼이 아닌 방이거나 General 토픽으로 보내는 경우다.
     *
     * <p>숫자가 아니면 <b>기동을 실패시킨다.</b> 발송 시점까지 미루면 다음 날 아침 브리핑을
     * 통째로 잃고, 그때는 아무도 안 보고 있다.
     */
    static Integer topicId(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "텔레그램 토픽 ID가 숫자가 아닙니다: '" + configured + "'", e);
        }
    }

    /** 정기 발송 — 설정된 방의 Notice 토픽으로. */
    @CircuitBreaker(name = "telegram")
    public void send(String text) {
        send(defaultChatId, noticeTopicId, text);
    }

    /**
     * 토픽을 지정해 보낸다. {@code topicId}가 {@code null}이면 토픽 없이 — 포럼이라면
     * General 토픽으로 간다.
     *
     * <p>토픽을 뺀 2-인자 오버로드는 두지 않는다. 있으면 토픽을 깜빡한 호출이 조용히 General로
     * 떨어지고, 그건 아무 오류도 내지 않아 발견이 늦다.
     */
    @CircuitBreaker(name = "telegram")
    public void send(String chatId, Integer topicId, String text) {
        restClient.post()
                .uri("/bot{token}/sendMessage", botToken)
                .body(new SendMessage(chatId, topicId, truncate(text), "HTML", true))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 상한을 넘기면 잘라 보낸다 — 전부 실패하는 것보다 일부라도 가는 게 낫다.
     *
     * <p><b>HTML 모드에서는 자르는 위치가 위험하다.</b> 태그 한가운데나 열린 태그 상태로
     * 끊기면 텔레그램이 "can't parse entities"로 <b>메시지 전체를 거절한다</b> —
     * 일부라도 보내려던 것이 도리어 전부를 잃는다.
     */
    static String truncate(String text) {
        if (text == null || text.length() <= MAX_MESSAGE_LENGTH) {
            return text;
        }
        log.warn("메시지가 {}자로 상한을 넘어 잘라 보냅니다", text.length());
        String cut = text.substring(0, MAX_MESSAGE_LENGTH - 1);

        // 태그 한가운데서 끊겼으면 그 조각을 버린다
        int lastOpen = cut.lastIndexOf('<');
        if (lastOpen > cut.lastIndexOf('>')) {
            cut = cut.substring(0, lastOpen);
        }
        // 생략 표시는 닫는 태그 <b>앞</b>에 넣는다 — 뒤에 붙이면 서식 밖으로 튀어나온다
        return closeOpenTags(cut + "…");
    }

    /** 열린 채 남은 태그를 닫는다. 여는 순서의 역순으로 닫아야 중첩이 맞는다. */
    private static String closeOpenTags(String html) {
        java.util.Deque<String> open = new java.util.ArrayDeque<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(/?)(b|i|code|pre|a|blockquote)\\b[^>]*>").matcher(html);
        while (m.find()) {
            if (m.group(1).isEmpty()) {
                open.push(m.group(2));
            } else if (!open.isEmpty() && open.peek().equals(m.group(2))) {
                open.pop();
            }
        }
        StringBuilder closed = new StringBuilder(html);
        while (!open.isEmpty()) {
            closed.append("</").append(open.pop()).append(">");
        }
        return closed.toString();
    }

    /**
     * 링크 미리보기를 끈다 — 매체별 1건씩 묶어 보내면 미리보기 카드가 줄줄이 붙어
     * 정작 본문이 밀린다.
     *
     * <p><b>{@code NON_NULL}이 필요하다.</b> {@code message_thread_id}는 "for forum supergroups
     * only"라 토픽이 없을 때는 필드 자체가 없어야 한다 — {@code null}을 실어 보내면 포럼이
     * 아닌 방에서 거절당할 수 있다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendMessage(
            @JsonProperty("chat_id") String chatId,
            @JsonProperty("message_thread_id") Integer messageThreadId,
            String text,
            @JsonProperty("parse_mode") String parseMode,
            @JsonProperty("disable_web_page_preview") boolean disableWebPagePreview) {}
}
