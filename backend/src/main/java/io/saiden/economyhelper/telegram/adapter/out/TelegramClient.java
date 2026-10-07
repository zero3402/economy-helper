package io.saiden.economyhelper.telegram.adapter.out;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.saiden.economyhelper.config.EconomyHelperProperties.Telegram;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 텔레그램 Bot API 발송.
 *
 * <p><b>응답 본문을 반드시 읽는다.</b> 텔레그램은 실패를 4xx로도 주고 <b>200 + {@code ok:false}</b>로도
 * 준다. 후자를 안 읽으면 실패가 성공으로 집계돼, 아침 브리핑이 오지 않았는데 로그에는
 * "발송 완료"가 남는다. 무엇을 고쳐야 하는지는 응답의 {@code description}에 적혀 있다.
 *
 * <p><b>닿지 못한 것만 다시 시도한다.</b> 웹훅은 텔레그램에 200을 이미 줬으므로 저쪽이 다시
 * 보내지 않는다 — 우리가 재시도하지 않으면 그 답은 영영 없다. 다만 {@code ok:false} 거절
 * ({@code chat not found})은 설정이 틀린 것이라 세 번 불러도 같은 답이고, 그동안 브리핑이
 * 통마다 늦어진다. 그래서 {@link TelegramUnavailable}만 재시도한다.
 *
 * <p>⚠️ <b>{@code send} 셋에 {@code @Retry}·{@code @CircuitBreaker}가 전부 붙어 있는데 겹쳐
 * 걸리지 않는다 — 자기 호출은 프록시를 타지 않기 때문이다.</b> 짧은 오버로드 둘은
 * {@code this.send(...)}로 넘기므로 안쪽 애너테이션이 발동하지 않고, 바깥에서 불린 하나만
 * 걸린다. 그래서 어느 오버로드로 들어와도 시도는 최대 세 번이다.
 * <b>이걸 "중복이니 정리하자"며 자기 주입·{@code @Lazy}로 프록시를 타게 만들면 재시도가
 * 겹쳐 한 통이 최대 아홉 번 나간다.</b>
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    /** Bot API 메시지 길이 상한. 넘기면 400이 떨어져 발송 자체가 실패한다. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    /**
     * ⚠️ 사진 설명의 상한 — <b>메시지의 4096이 아니라 1024다.</b> {@link #MAX_MESSAGE_LENGTH}로
     * 자르면 400이 떨어져 <b>사진이 통째로 실패한다.</b>
     */
    private static final int MAX_CAPTION_LENGTH = 1024;

    /**
     * 429의 {@code retry_after}를 이만큼까지만 기다린다. 더 길면 기다리지 않고 올린다 — 웹훅 답 하나나
     * 브리핑 한 통에 몇 분을 붙들면 그 뒤가 줄줄이 늦는다.
     */
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    /** 닫아 줄 태그 — {@link #closeOpenTags}. 자르기가 되풀이되므로 한 번만 컴파일한다. */
    private static final Pattern TAG = Pattern.compile("<(/?)(b|i|code|pre|a|blockquote)\\b[^>]*>");

    private final RestClient restClient;
    private final String botToken;
    private final String defaultChatId;
    private final Integer noticeTopicId;

    /**
     * 같은 방에 연달아 보낼 때 <b>발송 시작 사이</b>의 최소 간격.
     *
     * <p>텔레그램은 같은 채팅방에 <b>초당 한 통</b>을 권고한다. 붙여 쏘면 429와
     * {@code retry_after}를 맞을 수 있는데, 간격을 지키는 편이 재시도에 기대는 것보다 단순하고 확실하다.
     *
     * <p>⚠️ <b>「통 사이에 1초를 잔다」가 아니다.</b> 그러면 앞 통의 HTTP(실측 764ms)와 <b>합산</b>돼
     * 브리핑 한 번이 18초를 그냥 잔다(통 수는 ADR-0015). 방마다 「마지막 발송 시작 + 간격」을 기억해
     * <b>남은 만큼만</b> 기다리고, 부르는 쪽은 쉴 필요가 없다.
     *
     * <p>{@code KisThrottle}과 같은 모양(공평한 락 + {@code nextAllowed})이되 <b>방마다 하나</b>다 —
     * 권고가 방 단위라서다. 다른 방으로 가는 통은 서로 기다리지 않는다.
     */
    private final long intervalNanos;
    private final ConcurrentHashMap<String, ChatGate> gates = new ConcurrentHashMap<>();

    /** {@link #botUsername()}가 한 번 받은 이름. */
    private volatile String botUsername;

    /** 같은 방 발송 간격은 {@link Telegram#minInterval()}이 정한다 — 테스트는 0으로 끈다. */
    public TelegramClient(RestClient.Builder builder, EconomyHelperProperties properties) {
        Telegram telegram = properties.telegram();
        this.restClient = builder.baseUrl(telegram.baseUrl()).build();
        // ⚠️ **끝의 개행·공백을 뗀다.** .env나 BotFather에서 복사한 값에 붙기 쉬운데, 토큰은
        //    URI 경로에 그대로 실려 `%0A`가 되므로 **모든 발송이 404**가 된다. KIS가 같은 자리에서
        //    403 EGW00105를 맞았고(KisTokenStore), 웹훅 secret도 같은 이유로 다듬는다
        this.botToken = trimmed(telegram.botToken());
        this.defaultChatId = trimmed(telegram.chatId());
        this.noticeTopicId = topicId(telegram.noticeTopicId());
        this.intervalNanos = Math.max(0, telegram.minInterval().toNanos());
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** 그 방의 앞 통과 간격이 벌어질 때까지 기다린다 — 실제 HTTP 호출 직전에 부른다. */
    private void pace(String chatId) {
        if (intervalNanos == 0) {
            return;
        }
        gates.computeIfAbsent(chatId == null ? "" : chatId, key -> new ChatGate()).pace(intervalNanos);
    }

    /**
     * 방 하나의 문. {@code synchronized}가 아닌 이유는 가상 스레드다 — 그 안에서 자면 캐리어가 핀 된다.
     * <b>HTTP 호출 동안 잡고 있지 않는다</b> — 간격만 지키면 되고, 같은 방의 다른 답을 줄 세울 이유는 없다.
     */
    private static final class ChatGate {
        private final ReentrantLock lock = new ReentrantLock(true);
        private long nextAllowed = System.nanoTime();

        void pace(long intervalNanos) {
            lock.lock();
            try {
                long waitNanos = nextAllowed - System.nanoTime();
                if (waitNanos > 0) {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                }
                nextAllowed = System.nanoTime() + intervalNanos;
            } catch (InterruptedException e) {
                // 인터럽트는 삼키지 않고 플래그를 되살린다 — 종료 신호가 무시되면 컨테이너가 강제 종료된다.
                // ⚠️ 그리고 **던진다.** 정상 반환하면 종료 중 남은 통이 간격 없이 연달아 나가
                //    429를 우리가 만든다 — KisThrottle이 Congested를 던지는 것과 같은 자리다
                Thread.currentThread().interrupt();
                throw new IllegalStateException("텔레그램 발송 간격을 기다리다 중단됐습니다");
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 설정값을 토픽 번호로 읽는다. 비어 있으면 {@code null} — 토픽을 지정하지 않는다는 뜻이고,
     * 포럼이 아닌 방이거나 General 토픽으로 보내는 경우다.
     *
     * <p>숫자가 아니면 <b>기동을 실패시킨다.</b> 발송 시점까지 미루면 다음 날 아침 브리핑을
     * 통째로 잃고, 그때는 아무도 안 보고 있다.
     */
    public static Integer topicId(String configured) {
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

    /**
     * 정기 발송 — 설정된 방의 Notice 토픽으로. 미리보기는 뉴스 통만 켠다.
     *
     * <p><b>답글로 달지 않는다.</b> 브리핑은 아무도 묻지 않은 것에 대한 답이라 인용할 명령이 없다.
     */
    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void send(String text, boolean preview) {
        send(defaultChatId, noticeTopicId, null, text, preview);
    }

    /**
     * 토픽을 지정해 보낸다. {@code topicId}가 {@code null}이면 토픽 없이 — 포럼이라면
     * General 토픽으로 간다.
     *
     * <p><b>{@code send(chatId, text)} 꼴은 두지 않는다.</b> 있으면 토픽을 깜빡한 호출이 조용히
     * General로 떨어지고, 그건 아무 오류도 내지 않아 발견이 늦다. {@code replyTo}도 같은 이유로
     * 뺄 수 없게 두었다 — 답글을 깜빡하면 여럿이 함께 쓸 때 답이 섞인다.
     * (정기 발송용 {@code send(text, preview)}는 예외다. 대상이 설정에 박힌 방·토픽 하나뿐이라
     * 깜빡할 인자가 없다.)
     */
    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void send(String chatId, Integer topicId, Integer replyTo, String text) {
        send(chatId, topicId, replyTo, text, false);
    }

    /**
     * @param preview 링크 미리보기를 띄울지. <b>기본은 끈다</b> — 시세 통에는 링크 자체가
     *                없어 켜 봐야 달라지는 것이 없다. 기사를 담은 통만 켠다.
     *                <p><b>텔레그램은 한 메시지에 미리보기를 하나만, 그것도 맨 아래에 붙인다</b> —
     *                그래서 {@code NewsFormatter}가 기사마다 통을 쪼갠다
     */
    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void send(String chatId, Integer topicId, Integer replyTo, String text, boolean preview) {
        pace(chatId);
        call("sendMessage", new SendMessage(chatId, topicId, truncate(text), "HTML", !preview,
                replyTo, replyTo == null ? null : true), SendAck.class);
    }

    /**
     * 브리핑이 보내는 사진 — <b>설정된 채팅방과 공지 토픽으로.</b>
     *
     * <p>{@link #send(String, boolean)}과 같은 자리다 — 답글로 달지 않고, 방을 받지 않으므로
     * 토픽을 깜빡할 여지가 없다.
     */
    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void sendPhoto(byte[] png, String caption) {
        sendPhoto(defaultChatId, noticeTopicId, null, png, caption);
    }

    /**
     * 사진 한 장 — 차트를 보낸다.
     *
     * <p><b>caption이 설명을 든다</b> — 그림에는 글자가 없다({@code ChartRenderer}).
     * caption 상한은 {@link #MAX_CAPTION_LENGTH}다.
     *
     * <p>⚠️ <b>{@code sendPhoto(chatId, png)} 꼴의 짧은 오버로드를 두지 않는다</b> — 토픽을
     * 깜빡한 호출이 조용히 General로 떨어진다({@link #send} 셋과 같은 규칙). 자기 호출 경고는
     * 클래스 javadoc.
     *
     * @param png 그림 바이트. <b>비어 있으면 아무것도 보내지 않는다</b> — 점이 하나뿐인
     *            계열에서 {@code ChartRenderer}가 빈 배열을 준다
     */
    @Retry(name = "telegram")
    @CircuitBreaker(name = "telegram")
    public void sendPhoto(String chatId, Integer topicId, Integer replyTo,
                          byte[] png, String caption) {
        if (png == null || png.length == 0) {
            // 그릴 것이 없었다는 뜻이다. 빈 사진을 보내는 것보다 안 보내는 것이 맞다
            return;
        }
        pace(chatId);
        MultiValueMap<String, Object> parts =
                new LinkedMultiValueMap<>();
        parts.add("chat_id", chatId);
        // ⚠️ 토픽이 없을 때는 필드 자체가 없어야 한다 — "for forum supergroups only"라서
        //    null을 실으면 거절된다(SendMessage가 @JsonInclude(NON_NULL)인 것과 같은 이유)
        if (topicId != null) {
            parts.add("message_thread_id", topicId);
        }
        parts.add("caption", truncate(caption, MAX_CAPTION_LENGTH));
        parts.add("parse_mode", "HTML");
        if (replyTo != null) {
            parts.add("reply_to_message_id", replyTo);
            parts.add("allow_sending_without_reply", true);
        }
        // ⚠️ 파일 이름이 있어야 텔레그램이 파일 업로드로 받는다. 없으면 그냥 문자열 파트가 된다
        parts.add("photo", new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "chart.png";
            }
        });

        exchange("sendPhoto", SendAck.class, request -> request
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts));
    }

    /**
     * 설정된 채팅방의 정보 — 기동 시 자가진단에만 쓴다.
     *
     * <p>여기서 예외를 던지지 않는다. 진단이 앱을 죽이면 진단하려던 문제보다 큰 문제가 된다.
     *
     * @return 채팅방 정보. 못 가져오면 {@link Optional#empty()}이고 사유는 로그에 남는다
     */
    public Optional<ChatInfo> chatInfo() {
        if (defaultChatId == null || defaultChatId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(call("getChat", new GetChat(defaultChatId), ChatAck.class))
                    .map(ChatAck::result);
        } catch (RuntimeException e) {
            log.error("[telegram] 채팅방 조회 실패 — 브리핑이 안 나갈 수 있습니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 이 봇의 사용자 이름({@code getMe}) — 그룹의 {@code /명령@이름}이 우리를 부른 것인지 가린다.
     *
     * <p><b>받은 것만 기억한다.</b> 실패는 기억하지 않고 다음에 다시 묻는다 — 이름을 모르면 호출부는 지금까지처럼
     * 다 받으므로, 실패를 굳히면 다른 봇에게 한 명령에 계속 답한다. 예외를 던지지 않는다({@link #chatInfo}와 같다).
     */
    public Optional<String> botUsername() {
        String known = botUsername;
        if (known != null) {
            return Optional.of(known);
        }
        try {
            String name = Optional.ofNullable(call("getMe", Map.of(), UserAck.class))
                    .map(UserAck::result)
                    .map(BotUser::username)
                    .filter(username -> !username.isBlank())
                    .orElse(null);
            botUsername = name;
            return Optional.ofNullable(name);
        } catch (RuntimeException e) {
            log.warn("[telegram] 봇 이름 조회 실패 — 이번에는 @이름을 가리지 않습니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 4xx의 기본 예외를 끄고 본문을 직접 읽는다 — 사유({@code description})가 본문에만 있고,
     * 그 한 문장이 곧 무엇을 고쳐야 하는지다({@code chat not found}인지
     * {@code message thread not found}인지).
     */
    private <T extends Ack> T call(String method, Object body, Class<T> responseType) {
        return exchange(method, responseType, request -> request.body(body));
    }

    /**
     * 호출 하나 — <b>본문 만드는 법만 다르고 응답 처리는 하나다.</b>
     *
     * <p>{@code sendPhoto}는 multipart이고 나머지는 JSON이지만, {@code ok:false} 판정과
     * 429 가르기와 예외 감싸기는 <b>같아야 한다.</b>
     */
    private <T extends Ack> T exchange(String method, Class<T> responseType,
                                       UnaryOperator<RestClient.RequestBodySpec> body) {
        try {
            return exchangeOnce(method, responseType, body);
        } catch (TelegramRateLimited e) {
            // 한 번만 다시 보낸다 — 두 번째 429는 그대로 올린다. 브리핑 한 번이 그룹 한도(분당 20)를
            // 넘으면 뒤쪽 통이 여기 걸린다(통 수는 ADR-0015). 기다리는 동안 이 방의 다음 통도
            // ChatGate에서 함께 밀린다
            if (e.retryAfter() == null || e.retryAfter().compareTo(MAX_RETRY_AFTER) > 0) {
                throw e;
            }
            log.info("[telegram] {} 429 — {}초 뒤 한 번 더 보냅니다", method, e.retryAfter().toSeconds());
            sleep(e.retryAfter());
            return exchangeOnce(method, responseType, body);
        }
    }

    /**
     * 텔레그램이 「N초 뒤에」라고 한 것을 지킨다 — 인터럽트는 {@link ChatGate#pace}처럼 되살려 던진다.
     */
    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("텔레그램 429 대기 중 중단됐습니다");
        }
    }

    private <T extends Ack> T exchangeOnce(String method, Class<T> responseType,
                                           UnaryOperator<RestClient.RequestBodySpec> body) {
        T response;
        try {
            response = body.apply(restClient.post().uri("/bot{token}/" + method, botToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> { })
                    .body(responseType);
        } catch (RestClientException e) {
            // 닿지 못한 것이다 — 거절이 아니라서 다시 시도할 값이 있다(TelegramUnavailable javadoc)
            throw new TelegramUnavailable("텔레그램 " + method + " 호출 실패: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new TelegramUnavailable("텔레그램 " + method + " 응답이 비어 있습니다");
        }
        if (!response.ok()) {
            String reason = "텔레그램 " + method + " 거절: "
                    + response.errorCode() + " " + response.description();
            // ⚠️ 429는 따로 던진다 — 브레이커가 걸러낼 수 있어야 한다(TelegramRateLimited)
            if (Integer.valueOf(429).equals(response.errorCode())) {
                ResponseParameters parameters = response.parameters();
                throw new TelegramRateLimited(reason, parameters == null || parameters.retryAfter() == null
                        ? null : Duration.ofSeconds(parameters.retryAfter()));
            }
            throw new TelegramException(reason);
        }
        return response;
    }

    /**
     * <b>우리가 너무 빨리 물었다</b>(429) — 상대 장애가 아니다.
     *
     * <p>따로 두는 이유는 서킷브레이커가 이걸 실패로 세면 안 되기 때문이다
     * ({@code application.yml}의 {@code telegram} 인스턴스가 {@code ignoreExceptions}에 적는다) —
     * 하나로 뭉쳐 던지면 10회 창에 5번으로 브레이커가 열려 멀쩡한 발송까지 60초 막힌다.
     */
    public static class TelegramRateLimited extends TelegramException {

        /** 텔레그램이 알려 준 기다릴 시간({@code parameters.retry_after}). 안 줬으면 {@code null}. */
        private final Duration retryAfter;

        public TelegramRateLimited(String message) {
            this(message, null);
        }

        TelegramRateLimited(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /**
     * <b>텔레그램에 닿지 못했다</b> — 거절당한 것이 아니다.
     *
     * <p>따로 두는 이유는 <b>재시도가 이 둘을 구분해야</b> 하기 때문이다. {@code ok:false}로
     * 오는 거절({@code chat not found}·{@code message thread not found})은 설정이 틀린 것이라
     * 세 번 불러도 같은 답이다. 게이트웨이 502나 읽기 타임아웃만 다시 시도한다.
     *
     * <p>{@link TelegramException}의 하위이므로 <b>브레이커 설정은 손대지 않는다</b> —
     * 닿지 못한 것은 여전히 상대 장애로 세어야 한다.
     */
    public static class TelegramUnavailable extends TelegramException {

        public TelegramUnavailable(String message) {
            super(message);
        }

        TelegramUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 텔레그램이 거절했거나 닿지 못했다. 사유를 메시지에 그대로 싣는다. */
    public static class TelegramException extends RuntimeException {
        public TelegramException(String message) {
            super(message);
        }

        TelegramException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 상한을 넘기면 잘라 보낸다 — 전부 실패하는 것보다 일부라도 가는 게 낫다.
     *
     * <p><b>HTML 모드에서는 자르는 위치가 위험하다.</b> 태그 한가운데나 열린 태그 상태로
     * 끊기면 텔레그램이 "can't parse entities"로 <b>메시지 전체를 거절한다</b> —
     * 일부라도 보내려던 것이 도리어 전부를 잃는다.
     */
    static String truncate(String text) {
        return truncate(text, MAX_MESSAGE_LENGTH);
    }

    /** 상한을 받아 자른다 — <b>메시지와 caption의 상한이 다르다</b>({@link #MAX_CAPTION_LENGTH}). */
    static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        log.warn("메시지가 {}자로 상한({})을 넘어 잘라 보냅니다", text.length(), limit);

        // ⚠️ 닫는 태그까지 예산에 넣는다 — 닫는 태그가 상한을 넘기면 400으로 통째로 거절된다.
        //    자를 위치에 따라 닫을 태그가 달라지므로, 넘치면 그만큼 더 줄여 다시 맞춘다.
        int budget = limit - 1;
        while (budget > 0) {
            // 생략 표시는 닫는 태그 앞에 넣는다 — 뒤에 붙이면 서식 밖으로 튀어나온다
            String candidate = closeOpenTags(cutAt(text, budget) + "…");
            if (candidate.length() <= limit) {
                return candidate;
            }
            budget -= Math.max(1, candidate.length() - limit);
        }
        return "…";
    }

    /**
     * 안전한 자리에서 자른다 — <b>태그·엔티티·서로게이트 쌍을 쪼개지 않는다.</b>
     *
     * <p>셋 다 쪼개지면 텔레그램이 {@code can't parse entities}로 메시지를 통째로 거절하거나
     * 깨진 문자가 나간다. 서로게이트가 실제로 걸리는 이유는 등락률 이모지(🔴/🔵)가
     * 보조 평면 문자이고 그게 긴 통 안에 들어 있기 때문이다.
     */
    private static String cutAt(String text, int budget) {
        int end = Math.min(budget, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        String cut = text.substring(0, end);

        // 태그 한가운데서 끊겼으면 그 조각을 버린다
        int lastOpen = cut.lastIndexOf('<');
        if (lastOpen > cut.lastIndexOf('>')) {
            cut = cut.substring(0, lastOpen);
        }
        // 엔티티 한가운데서 끊겼으면(&am) 그 조각도 버린다 — '&'만 남는 것도 거절 사유다
        int lastAmp = cut.lastIndexOf('&');
        if (lastAmp > cut.lastIndexOf(';')) {
            cut = cut.substring(0, lastAmp);
        }
        return cut;
    }

    /** 열린 채 남은 태그를 닫는다. 여는 순서의 역순으로 닫아야 중첩이 맞는다. */
    private static String closeOpenTags(String html) {
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(html);
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
     * <b>{@code NON_NULL}이 필요하다.</b> {@code message_thread_id}는 "for forum supergroups
     * only"라 토픽이 없을 때는 필드 자체가 없어야 한다 — {@code null}을 실어 보내면 포럼이
     * 아닌 방에서 거절당할 수 있다. 답글 두 필드도 같은 규칙에 기댄다.
     *
     * @param disableWebPagePreview 링크 미리보기를 끌지. <b>호출자가 정한다</b> — 기사를 담은
     *                         통만 켠다
     * @param replyToMessageId 이 답이 어느 명령에 대한 것인지. 텔레그램이 원 명령을 인용해 그려 주므로
     *                         <b>여럿이 같은 방에서 동시에 검색해도 답이 섞이지 않는다.</b>
     *                         정기 발송은 인용할 명령이 없어 {@code null}이다
     * @param allowSendingWithoutReply 원 명령이 지워졌을 때 <b>답 자체가 실패하지 않게</b> 한다.
     *                         이게 없으면 텔레그램이 {@code message to be replied not found}로
     *                         거절해, 인용을 붙인 대가로 답을 통째로 잃는다.
     *                         답글이 아닐 때는 보내지 않는다({@code null})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendMessage(
            @JsonProperty("chat_id") String chatId,
            @JsonProperty("message_thread_id") Integer messageThreadId,
            String text,
            @JsonProperty("parse_mode") String parseMode,
            @JsonProperty("disable_web_page_preview") boolean disableWebPagePreview,
            @JsonProperty("reply_to_message_id") Integer replyToMessageId,
            @JsonProperty("allow_sending_without_reply") Boolean allowSendingWithoutReply) {}

    record GetChat(@JsonProperty("chat_id") String chatId) {}

    /** 모든 Bot API 응답의 공통 머리. {@code ok=false}면 {@code description}이 사유다. */
    public interface Ack {
        boolean ok();

        String description();

        Integer errorCode();

        /** 거절의 덧붙임 — 429면 {@code retry_after}가 든다. */
        ResponseParameters parameters();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseParameters(@JsonProperty("retry_after") Integer retryAfter) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendAck(boolean ok, String description,
                          @JsonProperty("error_code") Integer errorCode,
                          ResponseParameters parameters) implements Ack {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatAck(boolean ok, String description,
                          @JsonProperty("error_code") Integer errorCode,
                          ResponseParameters parameters,
                          ChatInfo result) implements Ack {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserAck(boolean ok, String description,
                          @JsonProperty("error_code") Integer errorCode,
                          ResponseParameters parameters,
                          BotUser result) implements Ack {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BotUser(String username) {}

    /**
     * @param isForum 포럼(토픽) 그룹이면 참. <b>참인데 토픽 ID가 없으면</b> 브리핑이 General로
     *                떨어지고, 거짓인데 토픽 ID가 있으면 발송이 거절된다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatInfo(String type, String title,
                           @JsonProperty("is_forum") Boolean isForum) {}
}
