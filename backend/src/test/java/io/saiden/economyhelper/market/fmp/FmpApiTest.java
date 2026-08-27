package io.saiden.economyhelper.market.fmp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.fmp.FmpApi.FmpQuote;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 FMP의 성질을 고정한다.
 *
 * <p>가장 중요한 건 <b>키가 쿼리에 실린다</b>는 점이다 — 예외 메시지로 새면
 * 로그·모니터링을 타고 그대로 유출된다({@code KeximFxClient}·{@code StockPriceApi}와 같은 규칙).
 */
class FmpApiTest {

    private static final String API_KEY = "secret-key-1234";
    private static final String PATH = "/stable/quote";

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;
    private FmpApi api;
    private CountingGuard guard;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetAndBuild() {
        // 스텁·요청기록·시나리오를 함께 비운다 — 서버는 그대로 두고 상태만 되돌린다
        server.resetAll();
        guard = new CountingGuard(true);
        api = new FmpApi(RestClient.builder(), server.baseUrl(), API_KEY, guard);
    }

    private void stub(String body) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("현재가와 시각을 그대로 읽는다")
    void parsesQuote() {
        stub("""
                [{"symbol":"AAPL","name":"Apple Inc.","price":302.25,"exchange":"NASDAQ",
                  "marketCap":4439253351000,"timestamp":1786564801}]
                """);

        FmpQuote quote = api.quote("AAPL");

        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.name()).isEqualTo("Apple Inc.");
        assertThat(quote.price()).isEqualByComparingTo("302.25");
        assertThat(quote.timestamp()).isEqualTo(1786564801L);
    }

    @Test
    @DisplayName("지수 심볼의 ^를 인코딩해 보낸다 — 날것으로 보내면 URI가 깨진다")
    void encodesCaretInIndexSymbol() {
        stub("""
                [{"symbol":"^IXIC","name":"NASDAQ Composite","price":26588.4881,"timestamp":1786564801}]
                """);

        assertThat(api.quote("^IXIC").name()).isEqualTo("NASDAQ Composite");

        // WireMock은 쿼리를 디코딩해 비교한다 — 우리가 인코딩해 보냈다면 원래 값으로 보인다
        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("symbol", WireMock.equalTo("^IXIC")));
    }

    @Test
    @DisplayName("빈 배열은 없는 심볼이다 — LLM이 지어낸 티커가 여기서 걸러진다")
    void returnsNullForUnknownSymbol() {
        stub("[]");

        assertThat(api.quote("ZZZZ")).isNull();
    }

    @Test
    @DisplayName("예외 메시지에 API 키가 새지 않는다 — 이 API는 키를 쿼리에 싣는다")
    void neverLeaksApiKey() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> api.quote("AAPL"))
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining("apikey");
    }

    @Test
    @DisplayName("402는 요금제 문제로 구분한다 — 재시도해도 소용없다는 것이 메시지에 드러나야 한다")
    void distinguishesPlanRestriction() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(402)
                .withBody("Premium Query Parameter")));

        assertThatThrownBy(() -> api.quote("005930.KS"))
                .hasMessageContaining("요금제")
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    @DisplayName("일일 한도를 넘기면 호출조차 하지 않는다 — 어차피 FMP가 거절한다")
    void skipsCallWhenQuotaExhausted() {
        stub("[{\"symbol\":\"AAPL\",\"price\":302.25}]");
        FmpApi limited = new FmpApi(RestClient.builder(), server.baseUrl(), API_KEY,
                new CountingGuard(false));

        assertThatThrownBy(() -> limited.quote("AAPL")).hasMessageContaining("한도");
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("키가 없으면 부르지 않는다 — 빈 키로 호출하면 한도만 축낸다")
    void skipsCallWithoutApiKey() {
        FmpApi keyless = new FmpApi(RestClient.builder(), server.baseUrl(), "", guard);

        assertThatThrownBy(() -> keyless.quote("AAPL")).hasMessageContaining("키");
        assertThat(guard.calls).as("키가 없으면 쿼터도 소모하지 않는다").isZero();
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    /** Redis 없이 쿼터 판정만 흉내 낸다. */
    private static final class CountingGuard extends FmpQuotaGuard {
        private final boolean allow;
        private int calls;

        private CountingGuard(boolean allow) {
            super(null, null, 240);
            this.allow = allow;
        }

        @Override
        public boolean tryAcquire() {
            calls++;
            return allow;
        }
    }
}
