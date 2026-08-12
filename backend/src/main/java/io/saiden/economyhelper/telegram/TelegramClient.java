package io.saiden.economyhelper.telegram;

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

    public TelegramClient(RestClient.Builder builder,
                          @Value("${economy-helper.telegram.base-url}") String baseUrl,
                          @Value("${economy-helper.telegram.bot-token:}") String botToken,
                          @Value("${economy-helper.telegram.chat-id:}") String defaultChatId) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.botToken = botToken;
        this.defaultChatId = defaultChatId;
    }

    /** 정기 발송 대상(설정된 기본 채팅방)으로 보낸다. */
    @CircuitBreaker(name = "telegram")
    public void send(String text) {
        send(defaultChatId, text);
    }

    @CircuitBreaker(name = "telegram")
    public void send(String chatId, String text) {
        restClient.post()
                .uri("/bot{token}/sendMessage", botToken)
                .body(new SendMessage(chatId, truncate(text), true))
                .retrieve()
                .toBodilessEntity();
    }

    /** 상한을 넘기면 잘라 보낸다 — 전부 실패하는 것보다 일부라도 가는 게 낫다. */
    static String truncate(String text) {
        if (text == null || text.length() <= MAX_MESSAGE_LENGTH) {
            return text;
        }
        log.warn("메시지가 {}자로 상한을 넘어 잘라 보냅니다", text.length());
        return text.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    /**
     * 링크 미리보기를 끈다 — 매체별 1건씩 묶어 보내면 미리보기 카드가 줄줄이 붙어
     * 정작 본문이 밀린다.
     */
    record SendMessage(
            @JsonProperty("chat_id") String chatId,
            String text,
            @JsonProperty("disable_web_page_preview") boolean disableWebPagePreview) {}
}
