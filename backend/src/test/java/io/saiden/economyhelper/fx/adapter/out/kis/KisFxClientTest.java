package io.saiden.economyhelper.fx.adapter.out.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.fx.domain.FxSource;
import io.saiden.economyhelper.infrastructure.kis.KisFixtures;
import io.saiden.economyhelper.testsupport.WireMockTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제로 호출해 확인한 KIS 환율의 성질을 고정한다(2026-08-18, 모의 계정).
 *
 * <p>스텁 본문은 그날 실제로 받은 응답을 줄인 것이다 — 필드 이름과 값이 그대로다.
 */
class KisFxClientTest extends WireMockTest {

    private static final String PATH = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
    /** KST 2026-08-18 17:00. */
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    private KisFxClient client;

    @BeforeEach
    void resetAndBuild() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        client = new KisFxClient(
                KisFixtures.call(server.baseUrl(), new KisFixtures.FixedToken(clock)), clock);
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
        assertThat(rate.rate().value()).isEqualByComparingTo("1412.5");
        assertThat(rate.changePercent().percent()).isEqualByComparingTo("-0.22");
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
                .withHeader("authorization", WireMock.equalTo("Bearer " + KisFixtures.TOKEN))
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
                .hasMessageContaining("값이 없습니다");
    }

    @Test
    @DisplayName("현재가가 0이면 던진다 — 같은 스키마에서 KIS는 틀린 심볼에 0.00을 준다")
    void refusesZeroAsAnExchangeRate() {
        // 틀린 심볼은 에러가 아니라 값 0으로 온다(실측 DJI·DJIA). 환율 0은 화면의
        // 모든 원화 환산을 오염시키므로 null과 똑같이 다뤄야 한다
        stub("{\"rt_cd\":\"0\",\"output1\":{\"ovrs_nmix_prpr\":\"0.0000\",\"prdy_ctrt\":\"0.00\"}}");

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("값이 없습니다");
    }

    @Test
    @DisplayName("500 본문에 실려 온 이유를 꺼낸다 — 이유는 벤더 경계 한 곳에서 읽는다")
    void readsTheReasonOutOfAnHttpError() {
        // 무효 토큰이면 환율·국내·미국이 함께 죽는다. 그래서 이유를 꺼내는 자리는 클라이언트가
        // 아니라 KisHeaders다 — 한 곳만 고쳐서 셋이 같이 말하는지 여기서 확인한다
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}
                        """)));

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("유효하지 않은 token")
                .hasMessageNotContaining(KisFixtures.TOKEN);
    }

    private static final String RATE_LIMITED = """
            {"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}
            """;

    @Test
    @DisplayName("초당 거래건수 초과(EGW00201)는 한 번 더 부른다 — 유일하게 잠시 뒤면 낫는 실패다")
    void retriesOnceWhenRateLimited() {
        // 실물 감사(2026-09-29): 간격을 1초 넘게 벌려도 모의 서버가 가끔 이것을 준다.
        // 한 번에 포기하면 /s 유아이패스가 빈손이 되고, 전망은 배당 줄이 빠진 채 12시간 굳는다
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("limit")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json").withBody(RATE_LIMITED))
                .willSetStateTo("second"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("limit").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"0","output1":{"ovrs_nmix_prpr":"1412.5000","prdy_ctrt":"-0.22"}}
                                """)));

        assertThat(client.usdToKrw().rate().value()).isEqualByComparingTo("1412.5");
        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("재시도는 한 번뿐이다 — 계속 막히면 던진다(다음 출처가 있다)")
    void givesUpAfterOneRetry() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody(RATE_LIMITED)));

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초당 거래건수");
        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("다른 500에는 재시도하지 않는다 — 무효 토큰·잘못된 키는 다시 불러도 안 낫는다")
    void neverRetriesOtherFailures() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00304\",\"msg1\":\"appsecret\"}")));

        assertThatThrownBy(() -> client.usdToKrw()).isInstanceOf(IllegalStateException.class);
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("마지막 일봉이 오늘이 아니면 실시간이 아니다 — 쉬는 날 현재가 자리에는 마지막 영업일 종가가 온다")
    void marksTheLastBusinessDayCloseAsNotLive() {
        // 화요일 17시인데 마지막 봉이 금요일이다(월요일이 휴일인 주)
        stub("""
                {"rt_cd":"0","output1":{"ovrs_nmix_prpr":"1412.5000","prdy_ctrt":"-0.22"},
                 "output2":[{"stck_bsop_date":"20260813"},{"stck_bsop_date":"20260814"}]}
                """);

        FxRate rate = client.usdToKrw();

        assertThat(rate.live()).isFalse();
        assertThat(rate.asOf()).isEqualTo(Instant.parse("2026-08-13T15:00:00Z"));
    }

    @Test
    @DisplayName("오늘 봉이 있으면 실시간이고 읽은 시각을 기준으로 든다")
    void keepsTodaysValueLive() {
        stubRealShape();

        FxRate rate = client.usdToKrw();

        assertThat(rate.live()).isTrue();
        assertThat(rate.asOf()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("200 본문에 실려 온 초당 한도 초과도 한 번 더 부른다 — 실측으로 가장 흔한 모양이 이것이다")
    void retriesOnceWhenRateLimitedInsideA200() {
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("limit-200")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(RATE_LIMITED))
                .willSetStateTo("second"));
        server.stubFor(get(urlPathEqualTo(PATH)).inScenario("limit-200").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"0","output1":{"ovrs_nmix_prpr":"1412.5000","prdy_ctrt":"-0.22"}}
                                """)));

        assertThat(client.usdToKrw().rate().value()).isEqualByComparingTo("1412.5");
        server.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("200 본문에 실려 온 무효 토큰도 버린다 — 상태코드가 아니라 msg_cd가 말한다")
    void discardsAnInvalidTokenCarriedInsideA200() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        KisFixtures.FixedToken tokens = new KisFixtures.FixedToken(clock);
        KisFxClient withTokens = new KisFxClient(KisFixtures.call(server.baseUrl(), tokens), clock);
        stub("""
                {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}
                """);

        assertThatThrownBy(withTokens::usdToKrw).isInstanceOf(IllegalStateException.class);
        assertThat(tokens.invalidated()).isTrue();
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("예외 메시지에 접근토큰이 새지 않는다 — 헤더에 실려 있다")
    void neverLeaksTheToken() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.usdToKrw())
                .hasMessageNotContaining(KisFixtures.TOKEN)
                .hasMessageNotContaining("Bearer");
    }

}
