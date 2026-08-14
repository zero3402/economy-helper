package io.saiden.economyhelper.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 텔레그램 웹훅 수신 — {@code /news {검색어}}.
 *
 * <p><b>어떤 경우에도 200을 돌려준다.</b> 텔레그램은 비-200을 받으면 같은 업데이트를
 * 계속 재전송하는데, 우리 쪽 오류로 재시도 폭풍이 나면 복구가 더 어려워진다.
 * 실패는 사용자에게 메시지로 알리고 로그에 남긴다.
 *
 * <p><b>이 주소는 공개된다</b> — 텔레그램이 부르려면 인터넷에서 닿아야 한다. 그래서 두 겹으로 막는다.
 *
 * <ol>
 *   <li><b>{@code secret_token}</b> — {@code setWebhook}에 준 비밀값을 텔레그램이 헤더로
 *       되돌려준다. 없거나 다르면 403이다. 남이 우리 주소에 직접 쏘는 걸 막는다.
 *   <li><b>{@code chat_id} 허용</b> — 봇 이름을 아는 제3자는 <b>정상 경로로</b> 명령을 칠 수 있고
 *       그건 진짜 텔레그램이 보내는 요청이라 1번을 통과한다. 설정된 채팅방이 아니면 무시한다.
 * </ol>
 *
 * FMP 무료 한도가 하루 250회라 남이 몇 분만 두드리면 그날 미국 시세가 죽는다 —
 * 막지 않으면 한도가 곧 가용성이 된다.
 */
@RestController
@RequestMapping("/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final NewsFacade newsFacade;
    private final CryptoService cryptoService;
    private final FxService fxService;
    private final StockService stockService;
    private final TelegramClient telegramClient;
    private final String webhookSecret;
    private final String allowedChatId;
    private final Integer searchTopicId;

    public TelegramWebhookController(NewsFacade newsFacade,
                                     CryptoService cryptoService,
                                     FxService fxService,
                                     StockService stockService,
                                     TelegramClient telegramClient,
                                     @Value("${economy-helper.telegram.webhook-secret:}") String webhookSecret,
                                     @Value("${economy-helper.telegram.chat-id:}") String allowedChatId,
                                     @Value("${economy-helper.telegram.search-topic-id:}") String searchTopicId) {
        this.newsFacade = newsFacade;
        this.cryptoService = cryptoService;
        this.fxService = fxService;
        this.stockService = stockService;
        this.telegramClient = telegramClient;
        // 다듬어 둔다. 대시보드에 붙여 넣은 값은 끝에 줄바꿈이나 공백이 붙기 쉽고,
        // 그러면 비교가 조용히 어긋나 모든 요청이 403이 된다
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.allowedChatId = allowedChatId == null ? "" : allowedChatId.trim();
        this.searchTopicId = TelegramClient.topicId(searchTopicId);

        // 비어 있으면 열어 둔다 — 로컬 실행과 테스트가 설정 없이 돌아야 하기 때문이다.
        // 대신 열려 있다는 사실을 기동 로그에 남긴다. 조용히 무방비인 것보다 낫다
        if (this.webhookSecret.isBlank()) {
            log.warn("[webhook] webhook-secret이 비어 있습니다 — 엔드포인트가 인증 없이 열립니다");
        }
        if (this.allowedChatId.isBlank()) {
            log.warn("[webhook] chat-id가 비어 있습니다 — 어느 채팅방에서든 명령이 동작합니다");
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> onUpdate(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String presentedSecret,
            @RequestBody Update update) {
        // 200-always 규약보다 먼저다. 그 규약은 *텔레그램이 보낸* 업데이트를 재시도 폭풍 없이
        // 소화하기 위한 것이고, secret이 틀린 요청은 정의상 텔레그램이 보낸 게 아니다.
        // 오히려 403이어야 getWebhookInfo의 last_error_message에 찍혀 설정이 어긋난 걸 눈으로 본다
        if (!secretMatches(presentedSecret)) {
            log.warn("[webhook] secret이 맞지 않는 요청을 거절했습니다");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            handle(update);
        } catch (Exception e) {
            log.error("웹훅 처리 실패: {}", e.toString(), e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 헤더로 돌아온 비밀값이 우리가 등록한 것과 같은가.
     *
     * <p>{@link MessageDigest#isEqual}을 쓴다 — {@code equals}는 첫 불일치에서 즉시 빠져나와
     * 비교 시간이 "몇 글자가 맞았는지"를 흘린다.
     */
    private boolean secretMatches(String presented) {
        if (webhookSecret.isBlank()) {
            return true;
        }
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), webhookSecret.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(Update update) {
        if (update == null || update.message() == null || update.message().chat() == null) {
            return;
        }
        Message message = update.message();
        String chatId = String.valueOf(message.chat().id());
        String text = message.text();
        // 포럼이 아닌 방과 General 토픽에서는 이 필드가 아예 오지 않는다 → null
        Integer topicId = message.messageThreadId();

        // 걸러낸 것은 답하지 않고 조용히 끝낸다. 답하면 봇이 살아 있다는 걸 확인해 주고
        // 발송 한 번을 쓴다. 대신 번호를 로그에 남긴다 — 설정할 값을 여기서 그대로 읽는다
        if (!allowedChatId.isBlank() && !allowedChatId.equals(chatId)) {
            log.info("[webhook] 허용되지 않은 채팅 {} (토픽 {}) — 무시합니다. TELEGRAM_CHAT_ID를 확인하세요",
                    chatId, topicId);
            return;
        }
        if (searchTopicId != null && !searchTopicId.equals(topicId)) {
            log.info("[webhook] 채팅 {}의 토픽 {}은 명령을 받지 않습니다 — TELEGRAM_SEARCH_TOPIC_ID를 확인하세요",
                    chatId, topicId);
            return;
        }

        Optional<ParsedCommand> parsed = CommandParser.parse(text);
        if (parsed.isEmpty()) {
            // '/'로 시작하는 오타에만 안내한다. 일반 대화는 조용히 무시해 그룹 채팅을 오염시키지 않는다
            if (CommandParser.isUnknownCommand(text)) {
                telegramClient.send(chatId, topicId, MessageFormatter.unknownCommand());
            }
            return;
        }

        ParsedCommand command = parsed.get();
        if (command.missingRequiredArgument()) {
            telegramClient.send(chatId, topicId, MessageFormatter.usage(command.command()));
            return;
        }

        // 물어본 토픽으로 답한다 — 다른 토픽에 답이 뜨면 대화가 어긋난다
        telegramClient.send(chatId, topicId, reply(command));
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
                    // 바이낸스가 붙었을 때만 USDT 환율을 묻는다 — 안 쓸 값을 미리 부르지 않는다
                    .map(quote -> MessageFormatter.formatCrypto(quote,
                            quote.binance().hasPrice() ? cryptoService.usdtKrw().orElse(null) : null))
                    .orElseGet(() -> MessageFormatter.cryptoNotFound(command.argument()));
            case FX -> fxService.usdToKrw()
                    .map(MessageFormatter::formatFx)
                    .orElseGet(MessageFormatter::fxUnavailable);
            // 미국 종목이면 원화도 함께 보여준다. 환율 조회가 실패하면 달러만 나간다 —
            // 환산을 못 한다고 시세 자체를 막을 이유가 없다.
            case STOCK -> stockService.quote(command.argument())
                    .map(match -> MessageFormatter.formatStock(match, currentFx()))
                    .orElseGet(() -> MessageFormatter.stockNotFound(command.argument()));
            case HELP -> MessageFormatter.help();
        };
    }

    /** 환율은 원화 환산에만 쓰인다 — 실패해도 시세는 나가야 하므로 {@code null}로 떨어뜨린다. */
    private FxRate currentFx() {
        try {
            return fxService.usdToKrw().orElse(null);
        } catch (RuntimeException e) {
            log.warn("[stock] 환율 조회 실패 — 원화 환산 없이 답합니다: {}", e.toString());
            return null;
        }
    }

    // --- 텔레그램 Update 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(Message message) {}

    /**
     * {@code message_thread_id}는 <b>선택 필드다</b> — 포럼 슈퍼그룹의 토픽 메시지에만 붙고
     * General 토픽과 일반 방에서는 오지 않는다.
     *
     * <p>{@code @JsonProperty}가 필요하다. 이 프로젝트는 전역 snake_case 전략을 쓰지 않아
     * 이름이 다른 필드는 하나씩 짚어 줘야 한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Chat chat, String text,
                          @JsonProperty("message_thread_id") Integer messageThreadId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {}
}
