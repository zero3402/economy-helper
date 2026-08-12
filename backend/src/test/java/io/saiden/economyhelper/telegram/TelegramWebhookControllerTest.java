package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.telegram.TelegramWebhookController.Chat;
import io.saiden.economyhelper.telegram.TelegramWebhookController.Message;
import io.saiden.economyhelper.telegram.TelegramWebhookController.Update;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * 웹훅의 응답 규약과 분기를 고정한다.
 *
 * <p>가장 중요한 건 <b>어떤 경우에도 200을 준다</b>는 점이다 — 텔레그램은 비-200에
 * 같은 업데이트를 계속 재전송한다.
 */
class TelegramWebhookControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    @DisplayName("검색 결과를 요청한 채팅방으로 보낸다")
    void repliesToRequestingChat() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(
                facade(Optional.of(item("유가 상승"))), crypto(Optional.empty()), client);

        controller.onUpdate(update(777, "/news 유가"));

        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.get(0).chatId()).isEqualTo("777");
        assertThat(client.sent.get(0).text()).contains("유가 상승");
    }

    @Test
    @DisplayName("결과가 없으면 없다고 알린다")
    void tellsUserWhenNothingFound() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "/news 비트코인"));

        assertThat(client.sent.get(0).text()).contains("찾지 못했습니다");
    }

    @Test
    @DisplayName("검색어가 빠지면 사용법을 안내한다")
    void repliesWithUsageWhenQueryMissing() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "/news"));

        assertThat(client.sent.get(0).text()).contains("/news 금리");
    }

    @Test
    @DisplayName("일반 대화에는 반응하지 않는다 — 그룹 채팅을 오염시키지 않는다")
    void ignoresPlainConversation() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "안녕하세요"));
        controller.onUpdate(update(1, "환율 알려줘"));

        assertThat(client.sent).isEmpty();
    }

    @Test
    @DisplayName("'/'로 시작하는 모르는 명령에만 안내한다 — 오타가 고장으로 보이면 안 된다")
    void guidesOnUnknownCommandOnly() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "/exchange"));

        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.get(0).text()).contains("모르는 명령").contains("/fx");
    }

    @Test
    @DisplayName("/crypto는 코인 시세로 답한다 — 뉴스와 다른 서비스로 간다")
    void routesCryptoCommand() {
        RecordingClient client = new RecordingClient();
        CryptoQuote btc = new CryptoQuote("KRW-BTC", "비트코인", new BigDecimal("89848000"), NOW);
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.of(btc)), client);

        controller.onUpdate(update(1, "/crypto 비트코인"));

        assertThat(client.sent.get(0).text()).contains("비트코인").contains("89,848,000");
    }

    @Test
    @DisplayName("못 찾은 코인은 찾지 못했다고 알린다 — 무응답이면 고장으로 보인다")
    void tellsUserWhenCryptoNotFound() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "/crypto 없는코인zzz"));

        assertThat(client.sent.get(0).text()).contains("찾지 못했습니다");
    }

    @Test
    @DisplayName("/help는 명령 목록을 준다")
    void repliesToHelp() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "/help"));

        assertThat(client.sent.get(0).text()).contains("/news").contains("/stock").contains("/crypto");
    }

    @Test
    @DisplayName("인자가 필요한 명령마다 그 명령의 사용법을 준다")
    void repliesWithPerCommandUsage() {
        RecordingClient client = new RecordingClient();
        var controller = new TelegramWebhookController(facade(Optional.empty()), crypto(Optional.empty()), client);

        controller.onUpdate(update(1, "/stock"));
        controller.onUpdate(update(1, "/crypto"));

        assertThat(client.sent.get(0).text()).contains("/stock 삼성전자");
        assertThat(client.sent.get(1).text()).contains("/crypto 비트코인");
    }

    @Test
    @DisplayName("처리 중 예외가 나도 200 — 텔레그램의 재시도 폭풍을 막는다")
    void alwaysReturnsOkEvenOnFailure() {
        NewsFacade exploding = new NewsFacade(null, null, null, properties()) {
            @Override
            public Optional<NewsItem> search(String query) {
                throw new IllegalStateException("수집 전체 실패");
            }
        };
        var controller =
                new TelegramWebhookController(exploding, crypto(Optional.empty()), new RecordingClient());

        var response = controller.onUpdate(update(1, "/news 금리"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("message가 없는 업데이트(채널 글 등)도 200으로 넘긴다")
    void toleratesUpdatesWithoutMessage() {
        var controller = new TelegramWebhookController(
                facade(Optional.empty()), crypto(Optional.empty()), new RecordingClient());

        assertThat(controller.onUpdate(new Update(null)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.onUpdate(null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Update update(long chatId, String text) {
        return new Update(new Message(new Chat(chatId), text));
    }

    private static NewsItem item(String title) {
        return new NewsItem(NewsSource.BLOOMBERG, "Bloomberg", title, "본문",
                "https://example.com/a", NOW, true, 0.9);
    }

    private static EconomyHelperProperties properties() {
        return new EconomyHelperProperties(Map.of(), null,
                new EconomyHelperProperties.Digest(
                        "Asia/Seoul", "0 0 9,21 * * *", List.of(), Duration.ofDays(3)),
                null, null);
    }

    /** 해석 규칙은 {@code CryptoServiceTest}가 본다. 여기서는 라우팅만 본다. */
    private static CryptoService crypto(Optional<CryptoQuote> result) {
        return new CryptoService(new UpbitApi(RestClient.builder(), "https://example.invalid")) {
            @Override
            public Optional<CryptoQuote> quote(String query) {
                return result;
            }
        };
    }

    private static NewsFacade facade(Optional<NewsItem> result) {
        return new NewsFacade(null, null, null, properties()) {
            @Override
            public Optional<NewsItem> search(String query) {
                return result;
            }
        };
    }

    /** 발송 내용을 기록하는 스텁. HTTP는 {@link TelegramClientTest}에서 따로 본다. */
    private static final class RecordingClient extends TelegramClient {
        private final List<Sent> sent = new ArrayList<>();

        private RecordingClient() {
            super(RestClient.builder(), "https://example.invalid", "token", "default-chat");
        }

        @Override
        public void send(String chatId, String text) {
            sent.add(new Sent(chatId, text));
        }

        @Override
        public void send(String text) {
            sent.add(new Sent("default-chat", text));
        }
    }

    private record Sent(String chatId, String text) {}
}
