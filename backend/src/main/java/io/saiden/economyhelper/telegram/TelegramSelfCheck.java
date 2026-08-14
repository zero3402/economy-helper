package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.telegram.TelegramClient.ChatInfo;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 텔레그램 설정을 한 번 검사한다.
 *
 * <p><b>왜 필요한가.</b> 브리핑과 명령 응답이 <b>서로 다른 값</b>으로 보낸다 — 명령은 들어온
 * 메시지의 {@code chat_id}로 답하고, 브리핑만 설정값({@code TELEGRAM_CHAT_ID} +
 * {@code TELEGRAM_NOTICE_TOPIC_ID})을 쓴다. 그래서 설정이 틀려도 <b>명령은 멀쩡히 동작하고
 * 아침 브리핑만 조용히 사라진다.</b> 그 사실을 다음 날 아침에 알게 되는 것이 문제였다.
 *
 * <p>기동을 막지는 않는다. 잘못된 설정으로도 명령 응답은 계속 되므로, 서비스를 세우는 것보다
 * 크게 남기는 편이 낫다 — 다만 {@code ERROR}로 남겨 배포 로그 첫 화면에 걸리게 한다.
 */
@Component
public class TelegramSelfCheck {

    private static final Logger log = LoggerFactory.getLogger(TelegramSelfCheck.class);

    private final TelegramClient client;
    private final String chatId;
    private final Integer noticeTopicId;
    private final Integer searchTopicId;

    public TelegramSelfCheck(TelegramClient client,
                             @Value("${economy-helper.telegram.chat-id:}") String chatId,
                             @Value("${economy-helper.telegram.notice-topic-id:}") String noticeTopicId,
                             @Value("${economy-helper.telegram.search-topic-id:}") String searchTopicId) {
        this.client = client;
        this.chatId = chatId;
        this.noticeTopicId = TelegramClient.topicId(noticeTopicId);
        this.searchTopicId = TelegramClient.topicId(searchTopicId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (chatId == null || chatId.isBlank()) {
            log.error("[telegram] TELEGRAM_CHAT_ID가 비어 있습니다 — 아침 브리핑은 나가지 않습니다");
            return;
        }

        Optional<ChatInfo> chat = client.chatInfo();
        if (chat.isEmpty()) {
            log.error("[telegram] chat-id '{}'로 채팅방을 찾지 못했습니다 — 아침 브리핑이 나가지 않습니다. "
                    + "슈퍼그룹으로 승격되면 번호가 -100...으로 바뀝니다", chatId);
            return;
        }

        ChatInfo info = chat.get();
        boolean forum = Boolean.TRUE.equals(info.isForum());
        log.info("[telegram] 발송 대상 확인 — '{}' ({}, 포럼={}), Notice 토픽={}, Search 토픽={}",
                info.title(), info.type(), forum, noticeTopicId, searchTopicId);

        // 포럼인데 토픽이 없으면 브리핑이 General로 떨어진다. 발송은 성공하므로 로그 말고는
        // 알 방법이 없다 — 사용자에게는 "Notice에 안 온다"로 보인다
        if (forum && noticeTopicId == null) {
            log.error("[telegram] 포럼 그룹인데 TELEGRAM_NOTICE_TOPIC_ID가 비어 있습니다 — "
                    + "브리핑이 General 토픽으로 갑니다");
        }
        // 반대는 발송 자체가 거절된다
        if (!forum && noticeTopicId != null) {
            log.error("[telegram] 포럼이 아닌 방에 TELEGRAM_NOTICE_TOPIC_ID={}가 설정돼 있습니다 — "
                    + "브리핑이 거절됩니다", noticeTopicId);
        }
    }
}
