package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.market.StockResolver;
import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.fmp.FmpApi;
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
    /**
     * 답을 같은 스레드에서 만든다.
     *
     * <p>운영에서는 가상 스레드로 옮겨 실어 텔레그램에 200을 먼저 준다(늦으면 같은 업데이트를
     * 다시 보낸다). 여기서는 "보냈다"를 곧바로 단언해야 하므로 갈아 끼운다.
     */
    private static final java.util.concurrent.Executor SAME_THREAD = Runnable::run;
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    @Test
    @DisplayName("못 찾은 코인은 찾지 못했다고 알린다 — 무응답이면 고장으로 보인다")
    void tellsUserWhenCryptoNotFound() {
        RecordingClient client = new RecordingClient();
        var controller = defaultController(facade(Optional.empty()), crypto(Optional.empty()), fx(Optional.empty()), stock(Optional.empty()), client);

        controller.onUpdate(null,update(1, "/crypto 없는코인zzz"));

        assertThat(client.sent.get(0).text()).contains("찾지 못했습니다");
    }

    @Test
    @DisplayName("/fx는 출처를 함께 알린다 — 폴백이 일어난 걸 숨기면 거짓말이 된다")
    void routesFxCommandWithSource() {
        RecordingClient client = new RecordingClient();
        FxRate kexim = new FxRate("USD", "KRW", new BigDecimal("1415"), FxSource.KEXIM, NOW);
        var controller = defaultController(
                facade(Optional.empty()), crypto(Optional.empty()), fx(Optional.of(kexim)),
                stock(Optional.empty()), client);

        controller.onUpdate(null,update(1, "/fx"));

        assertThat(client.sent.get(0).text())
                .contains("1,415").contains("수출입은행").contains("고시");
    }

    @Test
    @DisplayName("두 출처가 다 죽으면 못 가져왔다고 알린다")
    void tellsUserWhenFxUnavailable() {
        RecordingClient client = new RecordingClient();
        var controller = defaultController(
                facade(Optional.empty()), crypto(Optional.empty()), fx(Optional.empty()), stock(Optional.empty()), client);

        controller.onUpdate(null,update(1, "/fx"));

        assertThat(client.sent.get(0).text()).contains("환율을 가져오지 못했습니다");
    }

    @Test
    @DisplayName("/stock은 기준일과 함께 답한다 — 전일 종가라 날짜를 숨기면 실시간으로 오해한다")
    void routesStockCommandWithBasisDate() {
        RecordingClient client = new RecordingClient();
        StockQuote match = new StockQuote("005930", "삼성전자", "KOSPI", new BigDecimal("239500"),
                StockQuote.Money.KRW,
                java.time.LocalDate.of(2026, 8, 11)
                        .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                false, false, new BigDecimal("1400183726616000"));
        var controller = defaultController(facade(Optional.empty()), crypto(Optional.empty()),
                fx(Optional.empty()), stock(Optional.of(match)), client);

        controller.onUpdate(null,update(1, "/stock 삼성"));

        assertThat(client.sent.get(0).text())
                .contains("삼성전자").contains("239,500 KRW")
                .as("전일 종가라는 사실은 값의 성격이라 반드시 남긴다")
                .contains("2026년 8월 11일 (종가)")
                .as("이름·값·시각 셋뿐이다 — 종목코드도 거래소도 적지 않는다")
                .doesNotContain("005930").doesNotContain("KOSPI");
    }

    @Test
    @DisplayName("못 찾은 종목은 국내만 조회된다는 것까지 알린다")
    void tellsUserWhenStockNotFound() {
        RecordingClient client = new RecordingClient();
        var controller = defaultController(facade(Optional.empty()), crypto(Optional.empty()),
                fx(Optional.empty()), stock(Optional.empty()), client);

        controller.onUpdate(null,update(1, "/stock AAPL"));

        assertThat(client.sent.get(0).text()).contains("찾지 못했습니다").contains("국내");
    }

    @Test
    @DisplayName("/help는 명령 목록을 준다")
    void repliesToHelp() {
        RecordingClient client = new RecordingClient();
        var controller = defaultController(facade(Optional.empty()), crypto(Optional.empty()), fx(Optional.empty()), stock(Optional.empty()), client);

        controller.onUpdate(null,update(1, "/help"));

        assertThat(client.sent.get(0).text()).contains("/news").contains("/stock").contains("/crypto");
    }

    @Test
    @DisplayName("인자가 필요한 명령마다 그 명령의 사용법을 준다")
    void repliesWithPerCommandUsage() {
        RecordingClient client = new RecordingClient();
        var controller = defaultController(facade(Optional.empty()), crypto(Optional.empty()), fx(Optional.empty()), stock(Optional.empty()), client);

        controller.onUpdate(null,update(1, "/stock"));
        controller.onUpdate(null,update(1, "/crypto"));

        assertThat(client.sent.get(0).text()).contains("/stock 삼성전자");
        assertThat(client.sent.get(1).text()).contains("/crypto 비트코인");
    }

    @Test
    @DisplayName("처리 중 예외가 나도 200 — 텔레그램의 재시도 폭풍을 막는다")
    void alwaysReturnsOkEvenOnFailure() {
        NewsFacade exploding = new NewsFacade(null, null, null) {
            @Override
            public Optional<NewsItem> search(String query) {
                throw new IllegalStateException("수집 전체 실패");
            }
        };
        var controller =
                defaultController(exploding, crypto(Optional.empty()), fx(Optional.empty()), stock(Optional.empty()), new RecordingClient());

        var response = controller.onUpdate(null,update(1, "/news 금리"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("message가 없는 업데이트(채널 글 등)도 200으로 넘긴다")
    void toleratesUpdatesWithoutMessage() {
        var controller = defaultController(
                facade(Optional.empty()), crypto(Optional.empty()), fx(Optional.empty()), stock(Optional.empty()), new RecordingClient());

        assertThat(controller.onUpdate(null,new Update(null)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.onUpdate(null,null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- secret_token: 우리 주소로 직접 쏘는 사칭을 막는다 ---

    @Test
    @DisplayName("secret이 맞으면 지금까지와 똑같이 동작한다 — 진짜 텔레그램 요청을 막으면 안 된다")
    void servesRequestsCarryingTheRegisteredSecret() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("s3cr3t", "", client);

        var response = controller.onUpdate("s3cr3t", update(777, "/news 유가"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.get(0).text()).contains("유가 상승");
    }

    @Test
    @DisplayName("secret이 다르면 403이고 아무것도 하지 않는다 — 200으로 삼키면 사칭이 조용히 성공한다")
    void rejectsRequestsWithWrongSecret() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("s3cr3t", "", client);

        var response = controller.onUpdate("틀린값", update(777, "/news 유가"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.sent).as("사이드이펙트가 하나도 없어야 한다").isEmpty();
    }

    @Test
    @DisplayName("헤더가 아예 없으면 거절한다 — 누락이 통과하면 방어가 없는 것과 같다")
    void rejectsRequestsWithoutSecretHeader() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("s3cr3t", "", client);

        assertThat(controller.onUpdate(null, update(777, "/news 유가")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.sent).isEmpty();
    }

    @Test
    @DisplayName("secret을 설정하지 않으면 검증하지 않는다 — 로컬 실행과 CI가 설정 없이 돌아야 한다")
    void skipsVerificationWhenSecretUnset() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "", client);

        assertThat(controller.onUpdate(null, update(777, "/news 유가")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(client.sent).hasSize(1);
    }

    @Test
    @DisplayName("앞뒤 공백은 다듬는다 — 대시보드에 붙여 넣은 값의 줄바꿈 하나로 전부 403이 되면 안 된다")
    void trimsConfiguredValues() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("  s3cr3t\n", " 777 ", client);

        assertThat(controller.onUpdate("s3cr3t", update(777, "/news 유가")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(client.sent).hasSize(1);
    }

    // --- chat_id 허용: 봇을 찾은 제3자를 막는다 (secret은 통과한다) ---

    @Test
    @DisplayName("허용된 채팅방의 명령은 처리한다")
    void servesTheAllowedChat() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", client);

        controller.onUpdate(null, update(777, "/news 유가"));

        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.get(0).chatId()).isEqualTo("777");
    }

    @Test
    @DisplayName("다른 채팅방은 무시한다 — 답하면 봇의 존재를 확인해 주고 FMP 한도를 남이 쓴다")
    void ignoresOtherChats() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", client);

        var response = controller.onUpdate(null, update(999, "/news 유가"));

        assertThat(response.getStatusCode())
                .as("무응답이지 오류가 아니다 — 비-200이면 텔레그램이 계속 재전송한다")
                .isEqualTo(HttpStatus.OK);
        assertThat(client.sent).isEmpty();
    }

    @Test
    @DisplayName("chat-id를 설정하지 않으면 어디서든 받는다 — 기존 테스트가 그대로 통과해야 한다")
    void acceptsAnyChatWhenAllowListUnset() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "", client);

        controller.onUpdate(null, update(12345, "/news 유가"));

        assertThat(client.sent).hasSize(1);
    }

    // --- 포럼 토픽: 명령은 Search 토픽에서만 받고 그 토픽으로 답한다 ---

    @Test
    @DisplayName("지정한 토픽의 명령은 처리하고 그 토픽으로 답한다 — 다른 토픽에 답이 뜨면 대화가 어긋난다")
    void servesTheSearchTopicAndRepliesIntoIt() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", "42", client);

        controller.onUpdate(null, update(777, 42, "/news 유가"));

        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.get(0).topicId()).as("물어본 토픽으로 되돌아가야 한다").isEqualTo(42);
        assertThat(client.sent.get(0).text()).contains("유가 상승");
    }

    @Test
    @DisplayName("다른 토픽의 명령은 무시한다 — Notice 토픽은 브리핑만 받는 곳이다")
    void ignoresCommandsFromOtherTopics() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", "42", client);

        var response = controller.onUpdate(null, update(777, 9, "/news 유가"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.sent).isEmpty();
    }

    @Test
    @DisplayName("General 토픽은 message_thread_id가 아예 없어 자연히 걸러진다")
    void ignoresCommandsFromTheGeneralTopic() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", "42", client);

        controller.onUpdate(null, update(777, null, "/news 유가"));

        assertThat(client.sent).isEmpty();
    }

    @Test
    @DisplayName("토픽을 지정하지 않으면 검사하지 않는다 — 번호를 잘못 넣어 봇이 막혀도 비우면 되살아난다")
    void acceptsAnyTopicWhenSearchTopicUnset() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", "", client);

        controller.onUpdate(null, update(777, 9, "/news 유가"));
        controller.onUpdate(null, update(777, null, "/news 유가"));

        assertThat(client.sent).hasSize(2);
        assertThat(client.sent.get(0).topicId()).as("들어온 토픽은 그대로 되돌려준다").isEqualTo(9);
        assertThat(client.sent.get(1).topicId()).isNull();
    }

    @Test
    @DisplayName("사용법·모르는 명령 안내도 물어본 토픽으로 간다")
    void sendsGuidanceIntoTheAskingTopic() {
        RecordingClient client = new RecordingClient();
        var controller = guarded("", "777", "42", client);

        controller.onUpdate(null, update(777, 42, "/stock"));
        controller.onUpdate(null, update(777, 42, "/exchange"));

        assertThat(client.sent).hasSize(2);
        assertThat(client.sent).allSatisfy(s -> assertThat(s.topicId()).isEqualTo(42));
    }

    /** secret·허용 채팅을 설정하지 않은 컨트롤러. 나머지 테스트는 라우팅만 보므로 이쪽을 쓴다. */
    private static TelegramWebhookController defaultController(NewsFacade newsFacade,
                                                               CryptoService cryptoService,
                                                               FxService fxService,
                                                               StockService stockService,
                                                               TelegramClient telegramClient) {
        return new TelegramWebhookController(
                newsFacade, cryptoService, fxService, stockService, telegramClient, SAME_THREAD, "", "", "");
    }

    /** 방어를 켠 컨트롤러. {@code /news 유가}가 항상 결과를 내도록 고정해 둔다. */
    private static TelegramWebhookController guarded(String secret, String allowedChatId, TelegramClient client) {
        return guarded(secret, allowedChatId, "", client);
    }

    private static TelegramWebhookController guarded(
            String secret, String allowedChatId, String searchTopicId, TelegramClient client) {
        return new TelegramWebhookController(
                facade(Optional.of(item("유가 상승"))), crypto(Optional.empty()), fx(Optional.empty()),
                stock(Optional.empty()), client, SAME_THREAD, secret, allowedChatId, searchTopicId);
    }

    /** 토픽 없는 메시지 — 포럼이 아닌 방과 General 토픽이 이 모양이다. */
    private static Update update(long chatId, String text) {
        return update(chatId, null, text);
    }

    private static Update update(long chatId, Integer topicId, String text) {
        return new Update(new Message(new Chat(chatId), text, topicId));
    }

    private static NewsItem item(String title) {
        return new NewsItem(NewsSource.CNBC, "Bloomberg", title, "본문",
                "https://example.com/a", NOW, true, 0.9);
    }

    private static EconomyHelperProperties properties() {
        return new EconomyHelperProperties(Map.of(), null,
                new EconomyHelperProperties.Digest(
                        "Asia/Seoul", "0 0 9 * * *", Duration.ofDays(3),
                        List.of(), List.of(), List.of(), List.of()),
                null, null);
    }

    /** 해석 규칙은 {@code StockServiceTest}가 본다. 여기서는 라우팅만 본다. */
    private static StockService stock(Optional<StockQuote> result) {
        return new StockService(
                new StockPriceApi(RestClient.builder(), "https://example.invalid", "k", CLOCK),
                new MarketIndexApi(RestClient.builder(), "https://example.invalid", "k", CLOCK),
                new FmpApi(RestClient.builder(), "https://example.invalid", "", null),
                new StockResolver(null, null)) {
            @Override
            public Optional<StockQuote> quote(String query) {
                return result;
            }
        };
    }

    /** 이중화 규칙은 {@code FxServiceTest}가 본다. 여기서는 라우팅만 본다. */
    private static FxService fx(Optional<FxRate> result) {
        return new FxService(List.of()) {
            @Override
            public Optional<FxRate> usdToKrw() {
                return result;
            }
        };
    }

    /** 해석 규칙은 {@code CryptoServiceTest}가 본다. 여기서는 라우팅만 본다. */
    private static CryptoService crypto(Optional<CryptoQuote> result) {
        return new CryptoService(new UpbitApi(RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.CryptoResolver(null, null)) {
            @Override
            public Optional<CryptoQuote> quote(String query) {
                return result;
            }
        };
    }

    private static NewsFacade facade(Optional<NewsItem> result) {
        return new NewsFacade(null, null, null) {
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
            super(RestClient.builder(), "https://example.invalid", "token", "default-chat", "");
        }

        @Override
        public void send(String chatId, Integer topicId, String text) {
            sent.add(new Sent(chatId, topicId, text, false));
        }

        @Override
        public void send(String text) {
            sent.add(new Sent("default-chat", null, text, false));
        }

        @Override
        public void send(String chatId, Integer topicId, String text, boolean preview) {
            sent.add(new Sent(chatId, topicId, text, preview));
        }
    }

    /** @param preview 링크 미리보기를 켜고 보냈는지 */
    private record Sent(String chatId, Integer topicId, String text, boolean preview) {}
}
