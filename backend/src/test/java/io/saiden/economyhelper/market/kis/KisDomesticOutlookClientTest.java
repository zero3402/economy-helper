package io.saiden.economyhelper.market.kis;

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
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * KIS {@code invest-opinion}의 실측 응답을 그대로 먹여 파싱과 접기를 고정한다.
 *
 * <p><b>픽스처가 실측이다</b>(2026-08-21, 모의 계정, 삼성전자 7~8월 12행 중 셋). 필드 이름이
 * 이 기능의 유일한 오라클이라, 이름을 하나라도 잘못 적으면
 * {@code @JsonIgnoreProperties} 때문에 <b>오류 없이 빈 값</b>이 되어 「의견 낸 증권사가 없다」와
 * 구분되지 않는다.
 *
 * <p>그 실측에서 확인한 것 둘이 이 파일의 중심이다 — <b>같은 응답에 {@code BUY}와 {@code 매수}가
 * 섞여 온다</b>는 것, 그리고 <b>같은 증권사가 여러 번 낸다</b>는 것.
 */
class KisDomesticOutlookClientTest {

    private static final String PATH = "/uapi/domestic-stock/v1/quotations/invest-opinion";
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    private WireMockServer server;
    private KisDomesticOutlookClient client;
    private KisFixtures.FixedToken tokens;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        tokens = new KisFixtures.FixedToken(clock);
        client = new KisDomesticOutlookClient(RestClient.builder(), server.baseUrl(),
                tokens, new KisHeaders("key", "secret"), KisThrottle.none(), clock);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stub(String body) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    @Test
    @DisplayName("실측 응답을 접어 목표가 평균과 의견을 낸다 — BUY와 매수가 같은 응답에 섞여 온다")
    void foldsTheMeasuredResponse() {
        // 실측 2026-08-21: 키움·삼성은 "BUY", 한국투자는 "매수"로 적었다. 표기가 갈리므로
        // invt_opnn_cls_code가 아니라 이 글자를 정규화해야 셋이 한 등급으로 모인다
        stub("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"BUY",
                   "invt_opnn_cls_code":"2","hts_goal_prc":"350000"},
                  {"stck_bsop_date":"20260731","mbcr_name":"삼성","invt_opnn":"BUY",
                   "invt_opnn_cls_code":"2","hts_goal_prc":"400000"},
                  {"stck_bsop_date":"20260731","mbcr_name":"한국투자","invt_opnn":"매수",
                   "invt_opnn_cls_code":"2","hts_goal_prc":"650000"}]}""");

        StockOutlook outlook = client.outlook("005930").orElseThrow();

        assertThat(outlook.rating())
                .as("BUY 둘과 매수 하나가 같은 등급으로 모여야 한다")
                .isEqualTo(StockOutlook.Rating.BUY);
        assertThat(outlook.targetPrice())
                .as("(350000 + 400000 + 650000) / 3")
                .isEqualByComparingTo(new BigDecimal("466667"));
        assertThat(outlook.analystCount()).isEqualTo(3);
        assertThat(outlook.source()).isEqualTo(StockSource.KIS);
        assertThat(outlook.earningsDate())
                .as("이 엔드포인트는 실적발표일을 주지 않는다 — 0이 아니라 null이어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("같은 증권사의 옛 발표는 걷어낸다 — 자주 내는 곳이 여러 표를 갖지 않는다")
    void keepsOnlyTheLatestPerBroker() {
        // 실측에서 한 종목이 8개월 치 90행이었다. 그대로 세면 자주 내는 증권사가 이긴다
        stub("""
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260301","mbcr_name":"키움","invt_opnn":"매도",
                   "hts_goal_prc":"100000"},
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"BUY",
                   "hts_goal_prc":"350000"}]}""");

        StockOutlook outlook = client.outlook("005930").orElseThrow();

        assertThat(outlook.analystCount()).as("한 증권사는 한 표다").isEqualTo(1);
        assertThat(outlook.rating())
                .as("3월의 매도가 아니라 8월의 BUY가 남아야 한다")
                .isEqualTo(StockOutlook.Rating.BUY);
        assertThat(outlook.targetPrice()).isEqualByComparingTo(new BigDecimal("350000"));
    }

    @Test
    @DisplayName("목표가 0은 평균에 넣지 않는다 — 넣으면 실제보다 낮은 목표가가 화면에 나간다")
    void ignoresZeroTargets() {
        stub("""
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"BUY",
                   "hts_goal_prc":"350000"},
                  {"stck_bsop_date":"20260810","mbcr_name":"미래","invt_opnn":"BUY",
                   "hts_goal_prc":"0"}]}""");

        StockOutlook outlook = client.outlook("005930").orElseThrow();

        assertThat(outlook.targetPrice())
                .as("0을 뺀 하나의 평균이므로 350000 그대로다")
                .isEqualByComparingTo(new BigDecimal("350000"));
    }

    @Test
    @DisplayName("의견 낸 증권사가 없으면 빈 값 — 그건 값이고 실패가 아니다")
    void emptyWhenNobodyPublished() {
        stub("""
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[]}""");

        assertThat(client.outlook("005930")).isEmpty();
    }

    @Test
    @DisplayName("모르는 의견 글자는 세지 않는다 — 넘겨짚어 중립으로 떨어뜨리면 매도가 사라진다")
    void neverGuessesAnUnknownLabel() {
        stub("""
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"아무말",
                   "hts_goal_prc":"350000"}]}""");

        StockOutlook outlook = client.outlook("005930").orElseThrow();

        assertThat(outlook.rating()).as("모르는 글자는 등급이 되지 않는다").isNull();
        assertThat(outlook.targetPrice())
                .as("의견을 못 읽어도 목표가는 살아 있다 — 셋이 따로 논다")
                .isEqualByComparingTo(new BigDecimal("350000"));
    }

    @Test
    @DisplayName("rt_cd가 0이 아니면 던진다 — 에러가 HTTP 200 본문에 실려 온다")
    void throwsOnErrorInsideA200() {
        stub("""
                {"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""");

        assertThatThrownBy(() -> client.outlook("005930"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초당 거래건수");
    }

    @Test
    @DisplayName("실패를 삼키지 않는다 — 삼키면 브레이커가 정상 반환을 보고 성공을 센다")
    void failureReachesTheCaller() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        // DomesticOutlookClient가 「빈 값으로 실패한다」였지만 그대로 하면
        // @CircuitBreaker가 영원히 열리지 않는다(HackerNewsApi가 실제로 그 상태였다).
        // 삼키는 일은 StockService가 한다
        assertThatThrownBy(() -> client.outlook("005930"))
                .isInstanceOf(IllegalStateException.class);

        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("무효 토큰(EGW00121)을 알아보면 버린다 — 안 버리면 최대 24시간 모든 KIS가 죽는다")
    void discardsAnInvalidToken() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}""")));

        assertThatThrownBy(() -> client.outlook("005930")).isInstanceOf(IllegalStateException.class);

        assertThat(tokens.invalidated())
                .as("KIS 클라이언트 셋의 공통 계약이다 — 어느 쪽이 먼저 알아차려도 같은 일을 한다")
                .isTrue();
    }

    @Test
    @DisplayName("등급 코드가 아니라 증권사가 쓴 글자를 읽는다 — 코드 하나에 여러 등급이 섞여 있다")
    void readsTheTextNotTheCode() {
        // 실측: 코드 3 하나에 Strong BUY·Hold·Outperform·Buy가 함께 있었다.
        // 여기서는 같은 코드 "3"에 서로 다른 글자를 넣어, 우리가 코드를 안 본다는 것을 못 박는다
        stub("""
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"Hold",
                   "invt_opnn_cls_code":"3","hts_goal_prc":"300000"},
                  {"stck_bsop_date":"20260810","mbcr_name":"삼성","invt_opnn":"Hold",
                   "invt_opnn_cls_code":"3","hts_goal_prc":"300000"},
                  {"stck_bsop_date":"20260810","mbcr_name":"미래","invt_opnn":"Strong BUY",
                   "invt_opnn_cls_code":"3","hts_goal_prc":"300000"}]}""");

        assertThat(client.outlook("005930").orElseThrow().rating())
                .as("코드로 읽으면 셋이 한 등급이 된다 — 글자로 읽으면 중립 둘이 이긴다")
                .isEqualTo(StockOutlook.Rating.HOLD);
    }
}
