package io.saiden.economyhelper.support;

import io.saiden.economyhelper.telegram.TelegramClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * 발송을 가로채 기록하는 텔레그램 — 브리핑·날씨 알람·웹훅 테스트가 나눠 쓴다.
 *
 * <p><b>네 오버로드를 전부 덮는다.</b> 예전에는 두 테스트가 각자 페이크를 들고 반씩만 덮었다(브리핑은
 * 방 없는 둘, 웹훅은 방 있는 둘) — 브리핑 쪽 주석이 「{@code send(String)}만 막으면 실제 발송 경로가 그 아래
 * 오버로드를 부르므로 테스트가 바깥으로 HTTP를 쏜다」고 경고해 두고 있었는데, 그 경고가 곧 한 페이크가
 * 전부를 덮어야 한다는 뜻이었다.
 *
 * <p>사진은 caption만 담는다 — 그림은 골든이 못 보고 낱말은 전부 caption에 있다({@code ChartRenderer}가
 * 글자를 안 그리기 때문이다). 글과 사진의 <b>보낸 순서</b>도 든다 — 사진이 글 앞에 가면 무엇의 그림인지 알 수 없다.
 */
public class RecordingTelegram extends TelegramClient {

    /** 보낸 글 본문 — 순서대로. */
    public final List<String> sent = new ArrayList<>();
    /** 글마다 미리보기를 켰는지. */
    public final List<Boolean> previews = new ArrayList<>();
    /** 보낸 글 전부, 어느 방·토픽·답글이었는지까지. */
    public final List<Sent> messages = new ArrayList<>();
    /** 사진의 caption만. */
    public final List<String> captions = new ArrayList<>();
    /** 글과 사진이 섞인 보낸 순서 — {@code "글"}·{@code "사진"}. */
    public final List<String> order = new ArrayList<>();
    /** 이 문구가 담긴 통만 거절한다 — 부분 실패를 심는 데 쓴다. {@code null}이면 다 받는다. */
    private final String rejectContaining;

    public RecordingTelegram() {
        this(null);
    }

    public RecordingTelegram(String rejectContaining) {
        super(RestClient.builder(), "https://example.invalid", "token", "default-chat", "", Duration.ZERO);
        this.rejectContaining = rejectContaining;
    }

    @Override
    public void send(String text, boolean preview) {
        record(null, null, null, text, preview);
    }

    @Override
    public void send(String chatId, Integer topicId, Integer replyTo, String text) {
        record(chatId, topicId, replyTo, text, false);
    }

    @Override
    public void send(String chatId, Integer topicId, Integer replyTo, String text, boolean preview) {
        record(chatId, topicId, replyTo, text, preview);
    }

    @Override
    public void sendPhoto(byte[] png, String caption) {
        captions.add(caption);
        order.add("사진");
    }

    @Override
    public void sendPhoto(String chatId, Integer topicId, Integer replyTo, byte[] png, String caption) {
        captions.add(caption);
        order.add("사진");
    }

    private void record(String chatId, Integer topicId, Integer replyTo, String text, boolean preview) {
        if (rejectContaining != null && text.contains(rejectContaining)) {
            throw new TelegramException("거절: " + rejectContaining);
        }
        sent.add(text);
        previews.add(preview);
        messages.add(new Sent(chatId, topicId, replyTo, text, preview));
        order.add("글");
    }

    /**
     * @param chatId  브리핑처럼 설정된 방으로 보낸 것은 {@code null}
     * @param replyTo 어느 명령에 답글로 달았는지. 검색 답은 반드시 채워져 있어야 한다
     * @param preview 링크 미리보기를 켜고 보냈는지
     */
    public record Sent(String chatId, Integer topicId, Integer replyTo, String text, boolean preview) {}
}
