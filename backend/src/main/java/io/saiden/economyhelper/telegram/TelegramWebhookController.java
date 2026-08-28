package io.saiden.economyhelper.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.market.chart.ChartImage;
import io.saiden.economyhelper.market.weather.WeatherFacade;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.support.FailureReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
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

    private void handle(Update update) {
        Optional<Inbound> accepted = accepted(update);
        if (accepted.isEmpty()) {
            return;
        }
        String chatId = accepted.get().chatId();
        String text = accepted.get().text();
        Integer topicId = accepted.get().topicId();
        Integer replyTo = accepted.get().replyTo();

        Optional<ParsedCommand> parsed = CommandParser.parse(text);
        if (parsed.isEmpty()) {
            // '/'로 시작하는 오타에만 안내한다. 일반 대화는 조용히 무시해 그룹 채팅을 오염시키지 않는다
            if (CommandParser.isUnknownCommand(text)) {
                telegramClient.send(chatId, topicId, replyTo, HelpFormatter.unknownCommand());
            }
            return;
        }

        ParsedCommand command = parsed.get();
        if (command.missingRequiredArgument()) {
            telegramClient.send(chatId, topicId, replyTo, HelpFormatter.usage(command.command()));
            return;
        }

        long startedAt = System.nanoTime();
        // 물어본 토픽으로 답한다 — 다른 토픽에 답이 뜨면 대화가 어긋난다
        Reply reply;
        try {
            reply = reply(command);
        } catch (RuntimeException e) {
            // ⚠️ 마지막 그물이다. 텔레그램은 이미 200을 받았으므로 재시도가 없고, 여기서
            //    로그만 남기면 사용자에게는 <b>아무 답도 안 간다</b> — 서비스마다 안내 문구를
            //    따로 둔 이유(ARCHITECTURE.md 3-2)가 이 한 구멍으로 무력해졌다.
            //    도달 가능한 예외가 실제로 있다: 브레이커 열림(CallNotPermittedException),
            //    Redis 장애로 인한 캐시 계층 예외, 렌더 중의 상태 오류
            log.error("[webhook] 채팅 {} · {} 답 만들기 실패: {}", chatId, text, e.toString(), e);
            sendQuietly(chatId, topicId, replyTo, MessageLayout.unavailable(command.command()), false);
            return;
        }
        // 뉴스 검색은 기사마다 한 통이다(브리핑과 같은 규칙). 같은 방에 초당 한 통은
        // TelegramClient가 방마다 지키므로 여기서 쉴 일은 없다 — 예전에는 통마다 1초를 잤고
        // 그것이 앞 통의 HTTP 시간과 합산돼 /news 다섯 통에 4초를 더 태웠다
        for (String part : reply.texts()) {
            // 통마다 따로 실패한다 — 예전에는 2번 통이 던지면 3번은 시도조차 못 해
            // /news 3건 중 1건만 받고 왜 그런지 알 길이 없었다
            sendQuietly(chatId, topicId, replyTo, part, reply.preview());
        }
        // 글 다음에 사진 — 순서만 여기서 정하고 간격은 클라이언트가 지킨다.
        // ⚠️ 차트는 **글을 보낸 뒤에** 만든다. 답을 만들 때 함께 받았던 동안은 KIS 일봉(간격 1초 + HTTP)이
        //    끝나야 첫 글이 나갔다 — /stock마다 1.3초가 그냥 늦어졌다. 이제 그 조회가 발송 간격 안에 숨는다
        if (reply.chart() != null) {
            reply.chart().get().ifPresent(chart -> sendChartQuietly(chatId, topicId, replyTo, chart));
        }

        // 성공 경로에 유일하게 남는 줄이다. 세 가지를 여기서만 알 수 있다.
        //   · 토픽 번호 — 거절할 때만 찍으면 SEARCH_TOPIC_ID가 비었을 때(=아무것도 거절하지
        //     않을 때) 로그로는 알 길이 없다. /help 답과 함께 두 경로가 된다
        //   · 소요 시간 — 수집을 겹친 것이 실제로 듣는지, 스핀다운 직후 첫 요청이 얼마나
        //     늦는지가 여기서만 보인다
        //   · 어떤 명령이었는지
        // INFO여야 한다. debug로 두면 기본 레벨(INFO)에서 안 찍혀 없는 것과 같다 —
        // 실제로 그 상태로 커밋된 적이 있다.
        //
        // 답 본문은 남기지 않는다. 길고, 그룹 대화가 로그로 흘러드는 것과 다름없다
        log.info("[webhook] 채팅 {} 토픽 {} · {} → {}초", chatId, topicId, text,
                String.format("%.1f", (System.nanoTime() - startedAt) / 1_000_000_000.0));
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
     * 차트 한 장 — <b>실패해도 답이 이미 나갔다.</b>
     *
     * <p>글이 먼저 나가므로 사진이 실패해도 사용자는 값을 받았다. 그래서 여기서 던지지 않고
     * 로그만 남긴다 — 차트는 보충이지 답이 아니다({@code WeatherService}가 강수 시각을
     * 다루는 방식과 같은 자리다).
     */
    private void sendChartQuietly(String chatId, Integer topicId, Integer replyTo, ChartImage chart) {
        try {
            telegramClient.sendPhoto(chatId, topicId, replyTo, chart.png(), chart.caption());
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
    private void sendQuietly(String chatId, Integer topicId, Integer replyTo,
                             String text, boolean preview) {
        try {
            telegramClient.send(chatId, topicId, replyTo, text, preview);
        } catch (RuntimeException e) {
            log.error("[webhook] 채팅 {} 발송 실패: {}", chatId, e.toString());
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

        /** @param chart <b>지연 평가</b>된다 — 글이 나간 뒤 불린다. 실패는 빈 값이고 글은 이미 갔다 */
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
     * 예전에는 국내 종목만 붙었다 — 지수는 코드가 손에 없다고 봤고(실은 설정 표가 이름으로
     * 안다) 미국 종목은 경로를 실측하지 않았다.
     *
     * <p><b>열쇠가 없는 답만 빠진다</b> — 색인에도 없어 공공데이터포털 이름 검색으로만 찾은 경우다.
     * 그때도 답은 그대로 나간다: 전망과 같은 「보충이지 폴백이 아니다」 규칙이다.
     *
     * <p>단위는 시세가 든 통화를 그대로 쓴다. 지수는 {@code Money.NONE}이라 {@code null}이 되고
     * caption이 숫자만 적는다 — 「6,869.83 KRW」라고 적을 근거가 없다.
     */
    private Supplier<Optional<ChartImage>> stockChart(StockService.Answer answer) {
        if (answer.series() == null) {
            return null;
        }
        return () -> Charts.of("webhook", answer.quote().name(), Charts.unitOf(answer.quote()),
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
            // 기사마다 통을 쪼개므로 통마다 카드가 그 기사 것으로 확정된다 — 브리핑도 같은
            // 규칙이라 거기서도 미리보기를 켠다.
            //
            // ⚠️ 검색어가 없으면 브리핑과 같은 목록을 준다(코인 5 + 경제 5). 여섯 명령 중
            //    뉴스만 인자가 선택인 이유는 Command.NEWS 주석에 있다. 빈손 문구도 갈린다 —
            //    검색은 "'금리'에 해당하는 최근 24시간 뉴스를 찾지 못했습니다"이지만,
            //    검색어가 없으면 못 찾은 대상이 없으므로 브리핑이 쓰는 그 문구를 쓴다
            //    (formatAll이 빈 목록에 그것을 준다).
            case NEWS -> {
                List<NewsItem> found = command.hasArgument()
                        ? newsFacade.search(command.argument())
                        : newsFacade.digest();
                yield command.hasArgument() && found.isEmpty()
                        ? Reply.plain(NewsFormatter.noResults(command.argument(), newsFacade.window()))
                        : new Reply(NewsFormatter.formatAll(found), true, null);
            }
            // 브리핑 코인 통과 같은 함수다 — 항목이 하나뿐일 뿐이다.
            // 바이낸스가 붙었을 때만 환율을 묻는다 — 안 쓸 값을 미리 부르지 않는다
            case CRYPTO -> cryptoService.quote(command.argument())
                    .map(quote -> Reply.plain(CryptoFormatter.format(List.of(quote),
                                    quote.binance().hasPrice() ? fxService.orNull() : null),
                            cryptoChart(quote)))
                    .orElseGet(() -> Reply.plain(CryptoFormatter.notFound(command.argument())));
            case FX -> fxService.usdToKrw()
                    // 차트는 보충이다 — 일봉을 못 받아도 환율은 그대로 나간다
                    .map(rate -> Reply.plain(FxFormatter.format(rate), fxChart()))
                    .orElseGet(() -> Reply.plain(FxFormatter.unavailable()));
            // 미국 종목이면 원화도 함께 보여준다. 환율 조회가 실패하면 달러만 나간다 —
            // 환산을 못 한다고 시세 자체를 막을 이유가 없다.
            //
            // ⚠️ <b>미국 종목일 때만 환율을 묻는다.</b> 국내는 이미 원화라
            //    StockFormatter.convertible()이 거짓이고 시세도 목표가도 그 값을 안 쓴다.
            //    그런데 무조건 부르던 동안 값이 실재했다 — fxService의 1순위가 KisFxClient이고
            //    KisThrottle.pace()를 타므로, 찬 캐시(fx-kis 1분)에서 /stock 삼성전자가 KIS
            //    호출을 하나 더 태우고 간격이 전역이라 **그 1초가 주가 조회까지 늦췄다.**
            //    바로 위 case CRYPTO가 같은 규칙을 이미 지킨다
            case STOCK -> stockService.answer(command.argument())
                    .map(answer -> Reply.plain(StockFormatter.format(
                                    List.of(answer.quote()), fxFor(answer.quote()),
                                    // 전망이 없으면 빈 맵 — 그러면 그 줄이 아예 안 적힌다
                                    answer.outlook() == null
                                            ? Map.of()
                                            : Map.of(answer.quote(), answer.outlook())),
                            stockChart(answer)))
                    .orElseGet(() -> Reply.plain(StockFormatter.notFound(command.argument())));
            // 답이 일일 예보라 링크가 없다 — 미리보기를 켤 이유가 없다
            case WEATHER -> {
                WeatherFacade.Lookup found = weatherFacade.search(command.argument());
                yield Reply.plain(switch (found.reason()) {
                    case FOUND -> WeatherFormatter.format(found.places());
                    // 지역을 안 적은 것과 적었는데 못 찾은 것은 사용자가 할 일이 다르다
                    case NO_PLACE -> WeatherFormatter.needsPlace();
                    case NOT_FOUND -> WeatherFormatter.notFound(command.argument());
                    case UNREADABLE_DATE -> WeatherFormatter.unreadableDate();
                    case TOO_FAR_AHEAD -> WeatherFormatter.tooFarAhead();
                    case UNAVAILABLE -> WeatherFormatter.unavailable();
                });
            }
            case HELP -> Reply.plain(HelpFormatter.help());
        };
    }

    // --- 텔레그램 Update 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(Message message) {}

    /**
     * <b>{@code @JsonProperty}가 필요하다.</b> 이 프로젝트는 전역 snake_case 전략을 쓰지 않아
     * 이름이 다른 필드는 하나씩 짚어 줘야 한다.
     *
     *
     * @param messageId 이 명령 메시지의 번호. <b>답을 여기에 답글로 단다</b> — 그룹에서 여럿이
     *                  동시에 검색하면 답이 누구 것인지 알 수 없고, {@code /news}는 통이 셋으로
     *                  쪼개져 특히 섞인다. 텔레그램이 원 명령을 인용해 그려 주면 그게 사라진다
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
