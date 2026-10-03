package io.saiden.economyhelper.telegram.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.saiden.economyhelper.crypto.application.CryptoService;
import io.saiden.economyhelper.crypto.domain.CryptoQuote;
import io.saiden.economyhelper.fx.application.FxService;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.news.application.NewsFacade;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.stock.application.StockService;
import io.saiden.economyhelper.stock.domain.StockOutlook;
import io.saiden.economyhelper.stock.domain.StockQuote;
import io.saiden.economyhelper.telegram.adapter.out.TelegramClient;
import io.saiden.economyhelper.telegram.presentation.ChartImage;
import io.saiden.economyhelper.telegram.presentation.Charts;
import io.saiden.economyhelper.telegram.presentation.CryptoFormatter;
import io.saiden.economyhelper.telegram.presentation.FxFormatter;
import io.saiden.economyhelper.telegram.presentation.HelpFormatter;
import io.saiden.economyhelper.telegram.presentation.MessageLayout;
import io.saiden.economyhelper.telegram.presentation.NewsFormatter;
import io.saiden.economyhelper.telegram.presentation.StockFormatter;
import io.saiden.economyhelper.telegram.presentation.WeatherFormatter;
import io.saiden.economyhelper.weather.application.WeatherFacade;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 텔레그램 웹훅 수신 — 명령({@link Command})을 받아 답한다.
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
 * <p>FMP 무료 한도가 하루 250회라 남이 몇 분만 두드리면 그날 미국 시세가 죽는다 —
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
    private final WeatherFacade weatherFacade;
    private final TelegramClient telegramClient;
    private final String webhookSecret;
    private final String allowedChatId;
    private final Integer searchTopicId;

    /**
     * 명령 처리를 옮겨 실을 곳.
     *
     * <p><b>텔레그램은 웹훅 응답을 기다린다.</b> {@code /news}는 피드 수집과 Gemini 번역을
     * 거쳐 수 초가 걸리는데, 그동안 200을 주지 않으면 텔레그램이 <b>같은 업데이트를 재전송</b>해
     * 답이 두 번 간다. 받자마자 200을 주고 답은 여기서 따로 보낸다.
     *
     * <p>테스트는 같은 스레드로 도는 실행기({@code Runnable::run})를 넣어 순서를 고정한다.
     */
    private final Executor replyExecutor;

    public TelegramWebhookController(NewsFacade newsFacade,
                                     CryptoService cryptoService,
                                     FxService fxService,
                                     StockService stockService,
                                     WeatherFacade weatherFacade,
                                     TelegramClient telegramClient,
                                     @Qualifier("replyExecutor") Executor replyExecutor,
                                     @Value("${economy-helper.telegram.webhook-secret:}") String webhookSecret,
                                     @Value("${economy-helper.telegram.chat-id:}") String allowedChatId,
                                     @Value("${economy-helper.telegram.search-topic-id:}") String searchTopicId) {
        this.replyExecutor = replyExecutor;
        this.newsFacade = newsFacade;
        this.cryptoService = cryptoService;
        this.fxService = fxService;
        this.stockService = stockService;
        this.weatherFacade = weatherFacade;
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
        // 답을 만드는 데 몇 초가 걸릴 수 있다. 여기서 기다리면 텔레그램이 타임아웃 후
        // 같은 업데이트를 다시 보내 답이 두 번 나간다 — 받았다는 사실만 먼저 알린다
        replyExecutor.execute(() -> {
            try {
                handle(update);
            } catch (Exception e) {
                log.error("웹훅 처리 실패: {}", e.toString(), e);
            }
        });
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

    /**
     * 그룹에서 {@code /help@다른봇}처럼 다른 봇을 부른 명령인가 — 그러면 답하지 않는다(모르는 명령 안내도).
     *
     * <p>우리 이름을 모르면({@code getMe} 실패) 가리지 않고 받는다 — 우리를 부른 명령을 놓치는 것보다
     * 남의 명령에 한 번 더 답하는 편이 덜 나쁘다. 이름이 안 붙은 명령은 묻지도 않는다.
     */
    private boolean addressedToAnotherBot(String text) {
        return CommandParser.mentionOf(text)
                .flatMap(mention -> telegramClient.botUsername().map(me -> !me.equalsIgnoreCase(mention)))
                .orElse(false);
    }

    private void handle(Update update) {
        Optional<Inbound> accepted = accepted(update);
        if (accepted.isEmpty()) {
            return;
        }
        Inbound inbound = accepted.get();
        if (addressedToAnotherBot(inbound.text())) {
            return;
        }

        Optional<ParsedCommand> parsed = CommandParser.parse(inbound.text());
        if (parsed.isEmpty()) {
            // '/'로 시작하는 오타에만 안내한다. 일반 대화는 조용히 무시해 그룹 채팅을 오염시키지 않는다
            if (CommandParser.isUnknownCommand(inbound.text())) {
                telegramClient.send(inbound.chatId(), inbound.topicId(), inbound.replyTo(),
                        HelpFormatter.unknownCommand());
            }
            return;
        }
        ParsedCommand command = parsed.get();
        if (command.needsUsage()) {
            telegramClient.send(inbound.chatId(), inbound.topicId(), inbound.replyTo(),
                    HelpFormatter.usage(command.command()));
            return;
        }

        long startedAt = System.nanoTime();
        Reply reply;
        try {
            reply = reply(command);
        } catch (RuntimeException e) {
            // ⚠️ 마지막 그물이다. 텔레그램은 이미 200을 받았으므로 재시도가 없고, 여기서
            //    로그만 남기면 사용자에게는 <b>아무 답도 안 간다</b>(docs/design.md 3.2).
            //    도달 가능한 예외가 실제로 있다: 브레이커 열림(CallNotPermittedException),
            //    Redis 장애로 인한 캐시 계층 예외, 렌더 중의 상태 오류
            log.error("[webhook] 채팅 {} · {} 답 만들기 실패: {}",
                    inbound.chatId(), inbound.text(), e.toString(), e);
            sendQuietly(inbound, MessageLayout.unavailable(command.command()), false);
            return;
        }
        deliver(inbound, reply);

        // 성공 경로에 유일하게 남는 줄이다 — 토픽 번호(SEARCH_TOPIC_ID가 비었을 때 여기서만
        // 보인다)·소요 시간·명령. 답 본문은 남기지 않는다 — 그룹 대화가 로그로 흘러드는 것과 다름없다
        log.info("[webhook] 채팅 {} 토픽 {} · {} → {}초", inbound.chatId(), inbound.topicId(), inbound.text(),
                String.format("%.1f", (System.nanoTime() - startedAt) / 1_000_000_000.0));
    }

    /**
     * 물어본 토픽으로, 그 글에 답글로 보낸다 — 글 전부, 그다음 사진.
     *
     * <p>⚠️ <b>차트 조회를 글 발송과 겹친다</b>(순서는 그대로 글 다음 사진). 글이 나간 뒤에 조회를 시작하면
     * 그 시간이 발송 간격 위에 얹혀 글과 사진 사이가 1.0초에서 2.4초까지 벌어졌다(실측 2026-09-08).
     * 같은 방에 초당 한 통은 {@code TelegramClient}가 지킨다.
     *
     * <p>⚠️ 실행기를 try-with-resources로 닫는다 — 닫지 않으면 요청마다 하나가 남는다.
     */
    private void deliver(Inbound inbound, Reply reply) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<ChartImage>> pending =
                    reply.chart() == null ? null : executor.submit(reply.chart()::get);
            for (String part : reply.texts()) {
                // 통마다 따로 실패한다 — 한 통이 던져도 다음 통은 시도한다
                sendQuietly(inbound, part, reply.preview());
            }
            if (pending != null) {
                chartOf(pending).ifPresent(image -> sendChartQuietly(inbound, image));
            }
        }
    }

    /**
     * 받을 업데이트인가 — 널·채팅방·토픽 검증. <b>걸러낸 것은 답하지 않고 조용히 끝낸다.</b>
     *
     * <p>답하면 봇이 살아 있다는 걸 확인해 주고 발송 한 번을 쓴다. 대신 번호를 로그에 남긴다 —
     * 설정할 값을 거기서 그대로 읽는다.
     */
    private Optional<Inbound> accepted(Update update) {
        if (update == null || update.message() == null || update.message().chat() == null) {
            return Optional.empty();
        }
        Message message = update.message();
        String chatId = String.valueOf(message.chat().id());
        // 포럼이 아닌 방과 General 토픽에서는 이 필드가 아예 오지 않는다 → null
        Integer topicId = message.messageThreadId();
        if (!allowedChatId.isBlank() && !allowedChatId.equals(chatId)) {
            log.info("[webhook] 허용되지 않은 채팅 {} (토픽 {}) — 무시합니다. TELEGRAM_CHAT_ID를 확인하세요",
                    chatId, topicId);
            return Optional.empty();
        }
        if (searchTopicId != null && !searchTopicId.equals(topicId)) {
            log.info("[webhook] 채팅 {}의 토픽 {}은 명령을 받지 않습니다 — TELEGRAM_SEARCH_TOPIC_ID를 확인하세요",
                    chatId, topicId);
            return Optional.empty();
        }
        // 답을 이 명령에 답글로 단다 — 여럿이 동시에 검색해도 어느 물음의 답인지 확정된다
        return Optional.of(new Inbound(chatId, topicId, message.messageId(), message.text()));
    }

    /** 검증을 통과한 명령 하나 — 어느 방·어느 토픽·어느 글에 답할지와 본문. */
    private record Inbound(String chatId, Integer topicId, Integer replyTo, String text) {}

    /**
     * 겹쳐 둔 차트 조회의 결과 — <b>실패는 빈 값이고 글은 이미 갔다.</b>
     *
     * <p>{@code Charts.of}가 이미 {@code RuntimeException}을 삼켜 빈 값으로 주지만, 겹치면서
     * 생기는 실패가 둘 더 있다 — 인터럽트와 {@code Error}다. 인터럽트는 플래그를 되살리고
     * 넘어간다(종료 신호이지 차트의 문제가 아니다). {@code Error}는 그대로 올린다:
     * {@code Concurrently.join}이 {@code OutOfMemoryError}를 「이 출처 실패」로 만들지 않는 것과
     * 같은 판단이다.
     */
    private Optional<ChartImage> chartOf(Future<Optional<ChartImage>> pending) {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            // 종료 신호다 — 플래그를 되살리고 사진만 뺀다. 글은 이미 갔다
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                // OutOfMemoryError를 「차트 실패」로 삼키지 않는다 — Concurrently.join과 같은 판단이다
                throw error;
            }
            log.info("[webhook] 차트 조회 실패 — 값은 이미 나갔습니다: {}",
                    FailureReason.of(cause instanceof RuntimeException runtime ? runtime : e));
            return Optional.empty();
        }
    }

    /**
     * 차트 한 장 — <b>실패해도 답이 이미 나갔다.</b>
     *
     * <p>글이 먼저 나가므로 사진이 실패해도 사용자는 값을 받았다. 그래서 여기서 던지지 않고
     * 로그만 남긴다 — 차트는 보충이지 답이 아니다({@code WeatherService}가 강수 시각을
     * 다루는 방식과 같은 자리다).
     */
    private void sendChartQuietly(Inbound inbound, ChartImage chart) {
        try {
            telegramClient.sendPhoto(inbound.chatId(), inbound.topicId(), inbound.replyTo(),
                    chart.png(), chart.caption());
        } catch (RuntimeException e) {
            log.warn("[webhook] 차트 발송 실패 — 값은 이미 나갔습니다: {}", FailureReason.of(e));
        }
    }

    /**
     * 한 통을 보낸다 — <b>실패해도 다음 통을 막지 않는다.</b>
     *
     * <p>여기서 던지면 남은 통이 통째로 사라지고, 사용자는 왜 답이 중간에 끊겼는지 알 수 없다.
     * {@code DailyDigestJob}이 통마다 실패를 삼키는 것과 같은 규칙이다.
     */
    private void sendQuietly(Inbound inbound, String text, boolean preview) {
        try {
            telegramClient.send(inbound.chatId(), inbound.topicId(), inbound.replyTo(), text, preview);
        } catch (RuntimeException e) {
            log.error("[webhook] 채팅 {} 발송 실패: {}", inbound.chatId(), e.toString());
        }
    }

    /**
     * 답 한 건.
     *
     * @param texts   보낼 본문들. 뉴스만 여럿이고 나머지는 한 통짜리 목록이다
     * @param preview 링크 미리보기를 띄울지. 링크가 있는 통(뉴스)만 참이다
     */
    private record Reply(List<String> texts, boolean preview, Supplier<Optional<ChartImage>> chart) {

        static Reply plain(String text) {
            return new Reply(List.of(text), false, null);
        }

        /** @param chart <b>지연 평가</b>된다 — 글 발송과 겹쳐 불리고 사진은 글 다음에 나간다. 실패는 빈 값이고 글은 이미 갔다 */
        static Reply plain(String text, Supplier<Optional<ChartImage>> chart) {
            return new Reply(List.of(text), false, chart);
        }
    }

    /** 코인 일봉 차트 — 상장 직후 코인은 칸이 모자라 그림이 없을 수 있다. */
    private Supplier<Optional<ChartImage>> cryptoChart(CryptoQuote quote) {
        if (quote.market() == null) {
            // 업비트에 없는 코인이다(바이낸스만 상장) — 일봉을 물을 곳이 없다
            return null;
        }
        return () -> Charts.of("webhook", quote.name(), "KRW", () -> cryptoService.dailyBars(quote.market()));
    }

    /**
     * 일봉 차트 — <b>국내 종목·국내 지수·미국 종목·미국 지수 전부.</b>
     *
     * <p>{@code Answer}가 일봉 열쇠({@code Series})를 들고 오므로 여기서 종류를 가리지 않는다.
     * <b>열쇠가 없는 답만 빠진다</b> — 색인에도 없어 공공데이터포털 이름 검색으로만 찾은 경우다.
     * 그때도 답은 그대로 나간다.
     */
    private Supplier<Optional<ChartImage>> stockChart(StockService.Answer answer) {
        if (answer.series() == null) {
            return null;
        }
        return () -> Charts.of("webhook", answer.quote().name(), answer.quote().currency().unit(),
                () -> stockService.dailyBarsOf(answer.series()));
    }

    /**
     * 이 시세에 환산이 필요한가 — <b>필요할 때만 환율을 묻는다.</b>
     *
     * <p>{@code Money.convertible()}이 곧 「원화 줄이 붙는가」다({@code this == USD}).
     * 국내 종목과 지수는 거짓이라 부르지 않는다 — 안 쓸 값에 KIS 호출과 간격을 쓰지 않는다.
     */
    private FxRate fxFor(StockQuote quote) {
        return quote.currency().convertible() ? fxService.orNull() : null;
    }

    private Supplier<Optional<ChartImage>> fxChart() {
        return () -> Charts.of("webhook", "환율", "KRW", fxService::dailyBars);
    }

    /**
     * 명령 하나에 대한 답을 만든다.
     *
     * <p>{@code default} 없는 switch 식이라 <b>명령을 더하면 컴파일이 깨진다</b> —
     * 새 명령을 여기서 빠뜨려 조용히 무응답이 되는 일을 컴파일러가 막아 준다.
     */
    private Reply reply(ParsedCommand command) {
        return switch (command.command()) {
            case NEWS -> newsReply(command);
            case CRYPTO -> cryptoService.quote(command.argument())
                    .map(this::cryptoReply)
                    .orElseGet(() -> Reply.plain(CryptoFormatter.notFound(command.argument())));
            case FX -> fxService.usdToKrw()
                    // 차트는 보충이다 — 일봉을 못 받아도 환율은 그대로 나간다
                    .map(rate -> Reply.plain(FxFormatter.format(rate), fxChart()))
                    .orElseGet(() -> Reply.plain(FxFormatter.unavailable()));
            case STOCK -> stockService.answer(command.argument())
                    .map(this::stockReply)
                    .orElseGet(() -> Reply.plain(StockFormatter.notFound(command.argument())));
            case WEATHER -> weatherReply(command.argument());
            case HELP -> Reply.plain(HelpFormatter.help());
        };
    }

    /**
     * 기사마다 통을 쪼개므로 미리보기를 켠다.
     *
     * <p>⚠️ 검색어가 없으면 브리핑과 같은 목록을 준다(이유는 {@code Command.NEWS}). 빈손 문구도 갈린다 —
     * 검색어가 없으면 못 찾은 대상이 없으므로 {@code formatAll}이 브리핑 문구를 준다.
     */
    private Reply newsReply(ParsedCommand command) {
        List<NewsItem> found = command.hasArgument()
                ? newsFacade.search(command.argument())
                : newsFacade.digest();
        return command.hasArgument() && found.isEmpty()
                ? Reply.plain(NewsFormatter.noResults(command.argument(), newsFacade.window()))
                : new Reply(NewsFormatter.formatAll(found), true, null);
    }

    /** 브리핑 코인 통과 같은 함수다. 바이낸스가 붙었을 때만 환율을 묻는다 — 안 쓸 값을 미리 부르지 않는다. */
    private Reply cryptoReply(CryptoQuote quote) {
        FxRate fx = quote.binance().hasPrice() ? fxService.orNull() : null;
        return Reply.plain(CryptoFormatter.format(List.of(quote), fx), cryptoChart(quote));
    }

    /**
     * 미국 종목이면 원화도 함께 보여준다. 환율 조회가 실패하면 달러만 나간다 — 환산을 못 한다고
     * 시세 자체를 막을 이유가 없다. ⚠️ <b>미국 종목일 때만 환율을 묻는다</b>({@link #fxFor}).
     */
    private Reply stockReply(StockService.Answer answer) {
        // 전망이 없으면 빈 맵 — 그러면 그 줄이 아예 안 적힌다
        Map<StockQuote, StockOutlook> outlooks = answer.outlook() == null
                ? Map.of()
                : Map.of(answer.quote(), answer.outlook());
        return Reply.plain(StockFormatter.format(List.of(answer.quote()), fxFor(answer.quote()), outlooks),
                stockChart(answer));
    }

    /** 답이 일일 예보라 링크가 없다 — 미리보기를 켤 이유가 없다. */
    private Reply weatherReply(String query) {
        WeatherFacade.Lookup found = weatherFacade.search(query);
        return Reply.plain(switch (found.reason()) {
            case FOUND -> WeatherFormatter.format(found.places());
            // 지역을 안 적은 것과 적었는데 못 찾은 것은 사용자가 할 일이 다르다
            case NO_PLACE -> WeatherFormatter.needsPlace();
            case NOT_FOUND -> WeatherFormatter.notFound(query);
            case UNREADABLE_DATE -> WeatherFormatter.unreadableDate();
            case TOO_FAR_AHEAD -> WeatherFormatter.tooFarAhead();
            case UNAVAILABLE -> WeatherFormatter.unavailable();
        });
    }

    // --- 텔레그램 Update 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(Message message) {}

    /**
     * <b>{@code @JsonProperty}가 필요하다.</b> 이 프로젝트는 전역 snake_case 전략을 쓰지 않아
     * 이름이 다른 필드는 하나씩 짚어 줘야 한다.
     *
     * @param messageId 이 명령 메시지의 번호. <b>답을 여기에 답글로 단다</b> — 그룹에서 여럿이
     *                  동시에 검색하면 답이 누구 것인지 알 수 없고, {@code /news}는 여러 통으로
     *                  쪼개져 특히 섞인다
     * @param messageThreadId <b>선택 필드다</b> — 포럼 슈퍼그룹의 토픽 메시지에만 붙고
     *                  General 토픽과 일반 방에서는 아예 오지 않는다({@code null})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Chat chat, String text,
                          @JsonProperty("message_id") Integer messageId,
                          @JsonProperty("message_thread_id") Integer messageThreadId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {}
}
