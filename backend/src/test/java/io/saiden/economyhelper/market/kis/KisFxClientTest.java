package io.saiden.economyhelper.market.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 KIS 환율의 성질을 고정한다(2026-08-18, 모의 계정).
 *
 * <p>스텁 본문은 그날 실제로 받은 응답을 줄인 것이다 — 필드 이름과 값이 그대로다.
 */
class KisFxClientTest {

    private static final String PATH = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
    private static final String TOKEN = "secret-token-1234";
    /** KST 2026-08-18 17:00. */
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    private WireMockServer server;
    private KisFxClient client;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        client = new KisFxClient(RestClient.builder(), server.baseUrl(),
                new FixedToken(clock), new KisHeaders("key", "secret"), clock);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stub(String body) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** 2026-08-18 실측 응답을 줄인 것. */
    private void stubRealShape() {
        stub("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                 "output1":{"hts_kor_isnm":"원/달러(KMB)","ovrs_nmix_prpr":"1412.5000",
                            "prdy_ctrt":"-0.22","ovrs_nmix_prdy_clpr":"1415.6000"},
                 "output2":[{"stck_bsop_date":"20260818","ovrs_nmix_prpr":"1412.5000"}]}
                """);
    }

    @Test
    @DisplayName("현재가와 등락률을 읽는다 — output이 아니라 output1이다")
    void readsRateAndChange() {
        stubRealShape();

        FxRate rate = client.usdToKrw();

        assertThat(rate.source()).isEqualTo(FxSource.KIS);
        assertThat(rate.rate()).isEqualByComparingTo("1412.5");
        assertThat(rate.changePercent()).isEqualByComparingTo("-0.22");
    }

    @Test
    @DisplayName("기준 시각은 '읽은 시각'이다 — 계속 움직이는 값이라 저쪽이 시각을 주지 않는다")
    void stampsTheReadTimeBecauseTheSourceGivesNone() {
        stubRealShape();

        assertThat(client.usdToKrw().asOf())
                .as("하루 한 번 고시가 아니므로 날짜만 적으면 오히려 덜 정확하다")
                .isEqualTo(NOW);
        assertThat(FxSource.KIS.intraday())
                .as("그래서 화면에 시각까지 찍힌다")
                .isTrue();
    }

    @Test
    @DisplayName("환율 구분(X)과 FX@KRW를 싣는다 — N은 해외지수, I는 국채다")
    void asksForTheUsdKrwSymbol() {
        stubRealShape();

        client.usdToKrw();

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("X"))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("FX@KRW"))
                .withQueryParam("FID_PERIOD_DIV_CODE", WireMock.equalTo("D")));
    }

    @Test
    @DisplayName("오늘만 묻지 않는다 — 휴일·이른 아침에는 그날 봉이 없어 빈손이 된다")
    void asksForARangeNotJustToday() {
        stubRealShape();

        client.usdToKrw();

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("FID_INPUT_DATE_1", WireMock.equalTo("20260811"))
                .withQueryParam("FID_INPUT_DATE_2", WireMock.equalTo("20260818")));
    }

    @Test
    @DisplayName("토큰과 tr_id, custtype을 헤더로 보낸다 — custtype이 빠지면 이유 없이 실패한다")
    void sendsTheAuthHeaders() {
        stubRealShape();

        client.usdToKrw();

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("authorization", WireMock.equalTo("Bearer " + TOKEN))
                .withHeader("tr_id", WireMock.equalTo("FHKST03030100"))
                .withHeader("custtype", WireMock.equalTo("P")));
    }

    @Test
    @DisplayName("200에 실려 온 에러를 잡는다 — 초당 한도 초과가 실제로 이 모양으로 온다")
    void detectsErrorsCarriedInsideA200() {
        // 실측: 4연속 호출의 네 번째가 이렇게 왔다
        stub("""
                {"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}
                """);

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .as("상태코드가 아니라 rt_cd를 봐야 한다 — 수출입은행의 result와 같은 함정이다")
                .hasMessageContaining("초당 거래건수")
                .hasMessageContaining("rt_cd=1");
    }

    @Test
    @DisplayName("현재가가 없으면 던진다 — 빈 값을 돌려주면 폴백이 안 일어난다")
    void throwsWhenThePriceIsMissing() {
        stub("{\"rt_cd\":\"0\",\"output1\":{}}");

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("현재가");
    }

    @Test
    @DisplayName("예외 메시지에 접근토큰이 새지 않는다 — 헤더에 실려 있다")
    void neverLeaksTheToken() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.usdToKrw())
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("Bearer");
    }

    /** 발급을 흉내 내지 않는다 — 토큰 재사용 규칙은 {@link KisTokenStoreTest}가 따로 본다. */
    private static final class FixedToken extends KisTokenStore {
        private FixedToken(Clock clock) {
            super(RestClient.builder(), "http://localhost:1", "key", "secret", null, clock);
        }

        @Override
        public String token() {
            return TOKEN;
        }
    }
}
