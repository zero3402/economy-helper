package io.saiden.economyhelper.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 텔레그램 웹훅 수신 — {@code /news {검색어}}.
 *
 * <p><b>어떤 경우에도 200을 돌려준다.</b> 텔레그램은 비-200을 받으면 같은 업데이트를
 * 계속 재전송하는데, 우리 쪽 오류로 재시도 폭풍이 나면 복구가 더 어려워진다.
 * 실패는 사용자에게 메시지로 알리고 로그에 남긴다.
 */
@RestController
@RequestMapping("/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final NewsFacade newsFacade;
    private final CryptoService cryptoService;
    private final TelegramClient telegramClient;

    public TelegramWebhookController(NewsFacade newsFacade,
                                     CryptoService cryptoService,
                                     TelegramClient telegramClient) {
        this.newsFacade = newsFacade;
        this.cryptoService = cryptoService;
        this.telegramClient = telegramClient;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> onUpdate(@RequestBody Update update) {
        try {
            handle(update);
        } catch (Exception e) {
            log.error("웹훅 처리 실패: {}", e.toString(), e);
        }
        return ResponseEntity.ok().build();
    }

    private void handle(Update update) {
        if (update == null || update.message() == null || update.message().chat() == null) {
            return;
        }
        Message message = update.message();
        String chatId = String.valueOf(message.chat().id());
        String text = message.text();

        Optional<ParsedCommand> parsed = CommandParser.parse(text);
        if (parsed.isEmpty()) {
            // '/'로 시작하는 오타에만 안내한다. 일반 대화는 조용히 무시해 그룹 채팅을 오염시키지 않는다
            if (CommandParser.isUnknownCommand(text)) {
                telegramClient.send(chatId, MessageFormatter.unknownCommand());
            }
            return;
        }

        ParsedCommand command = parsed.get();
        if (command.missingRequiredArgument()) {
            telegramClient.send(chatId, MessageFormatter.usage(command.command()));
            return;
        }

        telegramClient.send(chatId, reply(command));
    }

    /**
     * 명령 하나에 대한 답을 만든다.
     *
     * <p>{@code default} 없는 switch 식이라 <b>명령을 더하면 컴파일이 깨진다</b> —
     * 새 명령을 여기서 빠뜨려 조용히 무응답이 되는 일을 컴파일러가 막아 준다.
     */
    private String reply(ParsedCommand command) {
        return switch (command.command()) {
            case NEWS -> newsFacade.search(command.argument())
                    .map(MessageFormatter::format)
                    .orElseGet(() -> MessageFormatter.noResults(command.argument()));
            case CRYPTO -> cryptoService.quote(command.argument())
                    .map(MessageFormatter::formatCrypto)
                    .orElseGet(() -> MessageFormatter.cryptoNotFound(command.argument()));
            case HELP -> MessageFormatter.help();
            // TODO 4~5단계에서 채운다
            case FX, STOCK -> "아직 준비 중인 명령입니다.";
        };
    }

    // --- 텔레그램 Update 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Chat chat, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {}
}
