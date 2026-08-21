package io.saiden.economyhelper.market.fmp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.StockSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * FMP 전망의 실측 응답을 그대로 먹인다(2026-08-21, 무료 티어, {@code AAPL} 둘 다 200).
 *
 * <p>이 파일의 중심은 <b>「둘 중 하나만 와도 답이다」</b>다. 무료 티어가 심볼별 허용목록이라
 * 목표가와 의견 중 한쪽만 402일 수 있고, 그때 살아 있는 쪽을 버리면 이유 없이 화면이 빈다.
 */
class FmpUsOutlookClientTest {

    private static final String GRADES = "/stable/grades-consensus";
    private static final String TARGET = "/stable/price-target-consensus";
    private static final String API_KEY = "test-key-402";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private FmpUsOutlookClient client() {
        return new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(), API_KEY,
                new AlwaysAllow(), Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));
    }

    private void stub(String path, int status, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** 실측 그대로. */
    private void stubBoth() {
        stub(GRADES, 200, """
                [{"symbol":"AAPL","strongBuy":1,"buy":69,"hold":32,"sell":9,
                  "strongSell":0,"consensus":"Buy"}]""");
        stub(TARGET, 200, """
                [{"symbol":"AAPL","targetHigh":400,"targetLow":245,
                  "targetConsensus":340.72,"targetMedian":360}]""");
    }

    @Test
    @DisplayName("실측 응답을 그대로 읽는다 — 곳 수는 다섯 버킷의 합이다")
    void readsTheMeasuredResponse() {
        stubBoth();

        StockOutlook outlook = client().outlook("AAPL").orElseThrow();

        assertThat(outlook.rating())
                .as("consensus는 글자다 — 국내와 같은 정규화를 탄다")
                .isEqualTo(StockOutlook.Rating.BUY);
        assertThat(outlook.analystCount())
                .as("1 + 69 + 32 + 9 + 0")
                .isEqualTo(111);
        assertThat(outlook.targetPrice()).isEqualByComparingTo(new BigDecimal("340.72"));
        assertThat(outlook.source()).isEqualTo(StockSource.FMP);
    }

    @Test
    @DisplayName("고가가 아니라 컨센서스를 쓴다 — 고가는 가장 낙관적인 한 곳이다")
    void usesConsensusNotTheHigh() {
        stubBoth();

        assertThat(client().outlook("AAPL").orElseThrow().targetPrice())
                .as("400(고가)을 쓰면 목표주가가 부풀려진다")
                .isEqualByComparingTo(new BigDecimal("340.72"));
    }

    @Test
    @DisplayName("의견이 402여도 목표가는 살린다 — 무료 티어는 심볼별 허용목록이다")
    void keepsTheTargetWhenGradesAreBlocked() {
        stub(GRADES, 402, "{\"Error Message\":\"Exclusive Endpoint\"}");
        stub(TARGET, 200, """
                [{"symbol":"ORCL","targetConsensus":250.5}]""");

        StockOutlook outlook = client().outlook("ORCL").orElseThrow();

        assertThat(outlook.targetPrice()).isEqualByComparingTo(new BigDecimal("250.5"));
        assertThat(outlook.rating()).as("못 구한 의견은 null이지 중립이 아니다").isNull();
        assertThat(outlook.analystCount()).isNull();
    }

    @Test
    @DisplayName("목표가가 402여도 의견은 살린다 — 반대 방향도 같다")
    void keepsTheRatingWhenTargetIsBlocked() {
        stub(GRADES, 200, """
                [{"symbol":"ORCL","strongBuy":0,"buy":5,"hold":1,"sell":0,
                  "strongSell":0,"consensus":"Buy"}]""");
        stub(TARGET, 402, "{\"Error Message\":\"Exclusive Endpoint\"}");

        StockOutlook outlook = client().outlook("ORCL").orElseThrow();

        assertThat(outlook.rating()).isEqualTo(StockOutlook.Rating.BUY);
        assertThat(outlook.analystCount()).isEqualTo(6);
        assertThat(outlook.targetPrice()).isNull();
    }

    @Test
    @DisplayName("둘 다 실패하면 던진다 — 「의견이 없다」와 구분해야 한다")
    void throwsWhenBothFail() {
        stub(GRADES, 402, "{}");
        stub(TARGET, 402, "{}");

        // 삼키면 그 위의 @CircuitBreaker가 정상 반환을 보고 성공을 센다.
        // 삼키는 일은 StockService가 한다
        assertThatThrownBy(() -> client().outlook("ORCL"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 배열은 값이다 — 의견을 낸 곳이 없는 것이라 던지지 않는다")
    void emptyArrayIsAValue() {
        stub(GRADES, 200, "[]");
        stub(TARGET, 200, "[]");

        assertThat(client().outlook("AAPL"))
                .as("실패가 아니라 「없다」이므로 빈 값이다")
                .isEmpty();
    }

    @Test
    @DisplayName("목표가 0은 값이 아니다 — 「목표가 0달러」가 화면에 나가면 거짓이다")
    void ignoresAZeroTarget() {
        stub(GRADES, 200, "[]");
        stub(TARGET, 200, """
                [{"symbol":"AAPL","targetConsensus":0}]""");

        assertThat(client().outlook("AAPL")).isEmpty();
    }

    @Test
    @DisplayName("곳 수가 0이면 안 적는다 — 「0곳이 매수」는 뜻이 없다")
    void omitsAZeroAnalystCount() {
        stub(GRADES, 200, """
                [{"symbol":"AAPL","strongBuy":0,"buy":0,"hold":0,"sell":0,
                  "strongSell":0,"consensus":"Buy"}]""");
        stub(TARGET, 200, "[]");

        StockOutlook outlook = client().outlook("AAPL").orElseThrow();

        assertThat(outlook.rating()).isEqualTo(StockOutlook.Rating.BUY);
        assertThat(outlook.analystCount()).isNull();
    }

    @Test
    @DisplayName("한도를 소진했으면 부르지 않는다 — 어차피 FMP가 거절한다")
    void skipsTheCallWhenQuotaIsGone() {
        FmpUsOutlookClient limited = new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(),
                API_KEY, new AlwaysDeny(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> limited.outlook("AAPL")).hasMessageContaining("한도");

        server.verify(0, getRequestedFor(urlPathEqualTo(GRADES)));
    }

    @Test
    @DisplayName("키가 없으면 부르지 않는다 — 빈 키로 호출하면 한도만 축낸다")
    void skipsTheCallWithoutAKey() {
        FmpUsOutlookClient keyless = new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(),
                "", new AlwaysAllow(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> keyless.outlook("AAPL")).hasMessageContaining("키");

        server.verify(0, getRequestedFor(urlPathEqualTo(GRADES)));
    }

    @Test
    @DisplayName("실패 메시지에 API 키가 새지 않는다 — 예외 메시지에 URL이 실려 온다")
    void neverLeaksTheApiKey() {
        stub(GRADES, 500, "{}");
        stub(TARGET, 500, "{}");

        assertThatThrownBy(() -> client().outlook("AAPL"))
                .hasMessageNotContaining(API_KEY);
    }

    /** 한도를 세지 않는 가드 — 세는 규칙은 {@code FmpQuotaGuard}가 스스로 시험한다. */
    private static final class AlwaysAllow extends FmpQuotaGuard {
        private AlwaysAllow() {
            super(null, Clock.systemUTC(), 240);
        }

        @Override
        public boolean tryAcquire() {
            return true;
        }
    }

    private static final class AlwaysDeny extends FmpQuotaGuard {
        private AlwaysDeny() {
            super(null, Clock.systemUTC(), 240);
        }

        @Override
        public boolean tryAcquire() {
            return false;
        }
    }
}
