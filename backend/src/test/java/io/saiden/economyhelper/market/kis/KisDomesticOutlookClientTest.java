package io.saiden.economyhelper.market.kis;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.StockSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * KIS {@code invest-opinion}과 예탁원정보(배당일정) 실측 응답을 그대로 먹여 파싱과 접기를 고정한다.
 *
 * <p><b>픽스처가 실측이다</b> — 투자의견은 2026-08-21(모의 계정, 삼성전자 7~8월 12행 중 셋),
 * 배당일정은 2026-09-07(같은 계정, 삼성전자 6행 중 셋과 SK하이닉스의 미정 행). 필드 이름이
 * 이 기능의 유일한 오라클이라, 이름을 하나라도 잘못 적으면
 * {@code @JsonIgnoreProperties} 때문에 <b>오류 없이 빈 값</b>이 되어 「의견 낸 증권사가 없다」와
 * 구분되지 않는다.
 *
 * <p>그 실측에서 확인한 것들이 이 파일의 중심이다 — <b>같은 응답에 {@code BUY}와 {@code 매수}가
 * 섞여 온다</b>는 것, <b>같은 증권사가 여러 번 낸다</b>는 것, 그리고 배당 쪽의 셋:
 * 배열 이름이 {@code output1}이라는 것, <b>한 행 안에서 날짜 모양이 갈린다</b>는 것
 * ({@code 20260630} ↔ {@code 2026/08/28}), 미정이 <b>공백과 {@code "0"}</b>으로 온다는 것.
 *
 * <p>⚠️ <b>하나라도 받았으면 답한다. 둘 다 실패했을 때만 던진다.</b> 반쪽을 안 담으면 조회마다
 * KIS 문 2초를 다시 쓰고, 값이 안 바뀌는 실패는 영원히 되풀이되며 브레이커를 태운다.
 * 그래서 이 파일은 「한쪽만 죽으면 살아남는지」와 「둘 다 죽으면 <b>예외를 감싸지 않고</b>
 * 던지는지」를 함께 본다.
 */
class KisDomesticOutlookClientTest extends WireMockTest {

    private static final String OPINION = "/uapi/domestic-stock/v1/quotations/invest-opinion";
    private static final String DIVIDEND = "/uapi/domestic-stock/v1/ksdinfo/dividend";
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    /** 실측 2026-09-07, 삼성전자. 기준일은 {@code yyyyMMdd}인데 지급일은 {@code yyyy/MM/dd}다. */
    private static final String SAMSUNG_DIVIDENDS = """
            {"output1":[
              {"record_date":"20260630","sht_cd":"005930","isin_name":"삼성전자","divi_kind":"분기",
               "face_val":"100","per_sto_divi_amt":"374","divi_rate":"374.00","stk_divi_rate":"0.00",
               "divi_pay_dt":"2026/08/28","stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통","high_divi_gb":"Y"},
              {"record_date":"20260331","sht_cd":"005930","isin_name":"삼성전자","divi_kind":"분기",
               "face_val":"100","per_sto_divi_amt":"372","divi_rate":"372.00","stk_divi_rate":"0.00",
               "divi_pay_dt":"2026/05/29","stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통","high_divi_gb":"Y"},
              {"record_date":"20251231","sht_cd":"005930","isin_name":"삼성전자","divi_kind":"결산",
               "face_val":"100","per_sto_divi_amt":"566","divi_rate":"566.00","stk_divi_rate":"0.00",
               "divi_pay_dt":"2026/04/17","stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통","high_divi_gb":"Y"}],
             "rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다."}""";

    private KisDomesticOutlookClient client;
    private KisFixtures.FixedToken tokens;

    @BeforeEach
    void resetAndBuild() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        tokens = new KisFixtures.FixedToken(clock);
        client = new KisDomesticOutlookClient(RestClient.builder(), server.baseUrl(),
                tokens, new KisHeaders("key", "secret"), KisFixtures.unpaced(), clock);
    }

    private void stub(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    /** 잡힌 배당이 없는 응답 — <b>값</b>이다. 목표가만 보는 테스트가 이것을 깐다. */
    private void noDividends() {
        stub(DIVIDEND, """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.","output1":[]}""");
    }

    /** 의견을 낸 증권사가 없는 응답 — 배당만 보는 테스트가 이것을 깐다. */
    private void noOpinions() {
        stub(OPINION, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[]}""");
    }

    @Test
    @DisplayName("실측 응답을 접어 목표가 평균을 낸다 — 증권사 셋의 평균이다")
    void foldsTheMeasuredResponse() {
        // 실측 2026-08-21. invt_opnn(의견 글자)이 함께 오지만 읽지 않는다 —
        // 투자의견을 화면에서 걷어냈으므로 이 응답에서 쓰는 것은 hts_goal_prc뿐이다
        stub(OPINION, """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"BUY",
                   "invt_opnn_cls_code":"2","hts_goal_prc":"350000"},
                  {"stck_bsop_date":"20260731","mbcr_name":"삼성","invt_opnn":"BUY",
                   "invt_opnn_cls_code":"2","hts_goal_prc":"400000"},
                  {"stck_bsop_date":"20260731","mbcr_name":"한국투자","invt_opnn":"매수",
                   "invt_opnn_cls_code":"2","hts_goal_prc":"650000"}]}""");
        noDividends();

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.targetPrice())
                .as("(350000 + 400000 + 650000) / 3")
                .isEqualByComparingTo(new BigDecimal("466667"));
        assertThat(outlook.source()).isEqualTo(StockSource.KIS);
        assertThat(outlook.earningsDate())
                .as("두 엔드포인트 어느 쪽도 실적발표일을 주지 않는다 — 0이 아니라 null이어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("같은 증권사의 옛 발표는 걷어낸다 — 자주 내는 곳이 여러 표를 갖지 않는다")
    void keepsOnlyTheLatestPerBroker() {
        // 실측에서 한 종목이 8개월 치 90행이었다. 그대로 세면 자주 내는 증권사가 이긴다
        stub(OPINION, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260301","mbcr_name":"키움","invt_opnn":"매도",
                   "hts_goal_prc":"100000"},
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"BUY",
                   "hts_goal_prc":"350000"}]}""");
        noDividends();

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.targetPrice())
                .as("3월의 100000이 아니라 8월의 350000만 남아야 한다 — 둘의 평균(225000)이 아니다")
                .isEqualByComparingTo(new BigDecimal("350000"));
    }

    @Test
    @DisplayName("목표가 0은 평균에 넣지 않는다 — 넣으면 실제보다 낮은 목표가가 화면에 나간다")
    void ignoresZeroTargets() {
        stub(OPINION, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","invt_opnn":"BUY",
                   "hts_goal_prc":"350000"},
                  {"stck_bsop_date":"20260810","mbcr_name":"미래","invt_opnn":"BUY",
                   "hts_goal_prc":"0"}]}""");
        noDividends();

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.targetPrice())
                .as("0을 뺀 하나의 평균이므로 350000 그대로다")
                .isEqualByComparingTo(new BigDecimal("350000"));
    }

    @Test
    @DisplayName("둘 다 비면 빈 값 — 그건 값이고 실패가 아니다")
    void emptyWhenNobodyPublished() {
        noOpinions();
        noDividends();

        assertThat(client.outlook("005930", false).isEmpty()).as("빈 값 객체 — 값이라 캐시된다").isTrue();
    }

    @Test
    @DisplayName("실측 배당일정을 읽는다 — 기준일은 yyyyMMdd, 지급일은 yyyy/MM/dd다")
    void readsTheMeasuredDividend() {
        // 2026-08-21 기준: 06-30 기준일은 지났고 08-28 지급일만 남았다.
        // ⚠️ 한 행 안에서 날짜 모양이 갈린다 — 하나로 읽으면 한쪽이 조용히 죽는다
        noOpinions();
        stub(DIVIDEND, SAMSUNG_DIVIDENDS);

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.dividend())
                .isEqualTo(new StockOutlook.Dividend(null, LocalDate.of(2026, 8, 28), new BigDecimal("374")));
    }

    @Test
    @DisplayName("지급일 공백과 배당금 0은 「아직 안 정해졌다」다 — 기준일 줄만 남는다")
    void blanksAreNotValues() {
        // 실측 SK하이닉스 결산 20251231 행이 그 모양이었다: divi_pay_dt "" · per_sto_divi_amt "0".
        // 여기서는 그 모양을 앞날 기준일에 올려 화면에 무엇이 남는지를 고정한다
        noOpinions();
        stub(DIVIDEND, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output1":[
                  {"record_date":"20260930","sht_cd":"000660","isin_name":"에스케이하이닉스","divi_kind":"결산",
                   "per_sto_divi_amt":"0","divi_pay_dt":"","stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통"}]}""");

        assertThat(client.outlook("005930", false).dividend())
                .isEqualTo(new StockOutlook.Dividend(LocalDate.of(2026, 9, 30), null, null));
    }

    @Test
    @DisplayName("배당 조회가 죽어도 목표가는 살린다 — 안 담으면 조회마다 KIS 문 2초를 다시 쓴다")
    void keepsTheTargetWhenTheDividendFails() {
        // ⚠️ 한때 이 자리에서 던졌다(반쪽이 12시간 굳는 것을 막으려고). 대가가 더 컸다 —
        //    예외는 캐시되지 않아 조회마다 문 2초를 다시 쓰고, 값이 안 바뀌는 실패는 영원히
        //    되풀이되며 브레이커를 태운다. 치르는 값은 배당 줄이 최대 반나절 안 보이는 것이다
        stub(OPINION, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","hts_goal_prc":"350000"}]}""");
        server.stubFor(get(urlPathEqualTo(DIVIDEND)).willReturn(aResponse().withStatus(500)));

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.targetPrice()).isEqualByComparingTo(new BigDecimal("350000"));
        assertThat(outlook.dividend()).as("못 구한 것은 없는 것이다 — 지어내지 않는다").isNull();
    }

    @Test
    @DisplayName("투자의견이 죽어도 배당은 살린다 — 반대 방향도 같다")
    void keepsTheDividendWhenTheOpinionFails() {
        server.stubFor(get(urlPathEqualTo(OPINION)).willReturn(aResponse().withStatus(500)));
        stub(DIVIDEND, SAMSUNG_DIVIDENDS);

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.dividend()).isNotNull();
        assertThat(outlook.targetPrice()).isNull();
    }

    @Test
    @DisplayName("모양이 어긋난 날짜는 그 줄만 빠진다 — 던지면 그 종목의 전망이 영구히 죽는다")
    void aMalformedDateOnlyDropsItsOwnLine() {
        // ⚠️ 한때 던졌다. 같은 입력이면 **영원히 같은 실패**인데 예외는 캐시되지 않아 조회마다
        //    되풀이되고, 캐시가 브레이커 밖이라 매번 세어져 최근 10회 중 5회면 브레이커가 열린다 —
        //    그러면 국내 시세가 전일 종가로 강등되고 미국 시세가 빈손이 된다.
        //    KisStockApi$Unsupported를 ignoreExceptions에 넣은 이유와 같은 부류였다
        noOpinions();
        stub(DIVIDEND, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output1":[
                  {"record_date":"20260930","divi_pay_dt":"20261119","per_sto_divi_amt":"1,200"}]}""");

        StockOutlook outlook = client.outlook("005930", false);

        assertThat(outlook.dividend())
                .as("기준일은 읽혔고 어긋난 지급일·배당금만 빠진다")
                .isEqualTo(new StockOutlook.Dividend(LocalDate.of(2026, 9, 30), null, null));
    }

    @Test
    @DisplayName("지난 행이 깨져도 답은 산다 — 화면이 쓰지 않을 행 하나가 전체를 죽이면 안 된다")
    void aMalformedPastRowDoesNotKillTheAnswer() {
        noOpinions();
        stub(DIVIDEND, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output1":[
                  {"record_date":"엉터리","divi_pay_dt":"2025/05/20","per_sto_divi_amt":"365"},
                  {"record_date":"20260930","divi_pay_dt":"2026/11/19","per_sto_divi_amt":"374"}]}""");

        assertThat(client.outlook("005930", false).dividend())
                .isEqualTo(new StockOutlook.Dividend(LocalDate.of(2026, 9, 30),
                        LocalDate.of(2026, 11, 19), new BigDecimal("374")));
    }

    @Test
    @DisplayName("없는 날은 조용히 옮기지 않는다 — 2026/02/31이 2월 28일로 통과하면 안 된다")
    void neverSlidesAnImpossibleDate() {
        // ⚠️ ofPattern("yyyy/MM/dd")는 기본이 SMART라 2026/02/31을 2026-02-28로 **바꿔 준다** —
        //    없는 날을 있는 날로 적는 셈이고, 같은 행의 record_date(STRICT)와 판정이 갈렸다
        noOpinions();
        stub(DIVIDEND, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output1":[
                  {"record_date":"20260930","divi_pay_dt":"2026/02/31","per_sto_divi_amt":"374"}]}""");

        assertThat(client.outlook("005930", false).dividend().payDate())
                .as("2026-02-28로 미끄러지면 안 된다 — 못 읽은 것이다")
                .isNull();
    }

    @Test
    @DisplayName("우리 문이 거절한 것은 그 타입 그대로 올린다 — 감싸면 브레이커의 ignoreExceptions가 안 맞는다")
    void keepsTheThrottleRejectionType() {
        // ⚠️ application.yml의 kisStock이 KisThrottle$Congested를 ignoreExceptions에 두는 이유가
        //    「우리 문이 우리를 거절한 것을 상대 장애로 세지 않는다」인데, 그것을 맨
        //    IllegalStateException으로 감싸 던지면 그 보호에서 조용히 빠진다.
        //    resilience4j는 **던져진 타입**으로 목록을 맞춘다. (적대적 리뷰가 잡았다)
        //
        //    실물에서 이 거절은 **락 경합**으로만 나므로(pace()는 간격만큼은 그냥 잔다) 여기서는
        //    거절하는 문을 끼워 넣는다 — 재현하려고 스레드를 띄우면 이 파일의 관심사가 흐려진다
        KisDomesticOutlookClient congested = new KisDomesticOutlookClient(RestClient.builder(),
                server.baseUrl(), tokens, new KisHeaders("key", "secret"), new AlwaysCongested(),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

        assertThatThrownBy(() -> congested.outlook("005930", false))
                .isInstanceOf(KisThrottle.Congested.class);
    }

    /** 늘 거절하는 문 — 「예외를 감싸지 않는다」만 보려는 것이라 진짜 경합을 만들지 않는다. */
    private static final class AlwaysCongested extends KisThrottle {

        private AlwaysCongested() {
            super(java.time.Duration.ZERO, java.time.Duration.ZERO);
        }

        @Override
        void pace() {
            throw new Congested("테스트가 세운 거절");
        }
    }

    @Test
    @DisplayName("rt_cd가 0이 아니면 실패다 — 에러가 HTTP 200 본문에 실려 온다")
    void treatsAnErrorInsideA200AsFailure() {
        // 실측 EGW00201(초당 거래건수 초과)은 HTTP 500으로도 오고 200 본문으로도 온다.
        // 둘 다 죽여야 던진다 — 한쪽만 죽으면 살아 있는 쪽이 답이 되기 때문이다
        String congested = """
                {"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""";
        stub(OPINION, congested);
        stub(DIVIDEND, congested);

        assertThatThrownBy(() -> client.outlook("005930", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초당 거래건수");
    }

    @Test
    @DisplayName("실패를 삼키지 않는다 — 삼키면 브레이커가 정상 반환을 보고 성공을 센다")
    void failureReachesTheCaller() {
        server.stubFor(get(urlPathEqualTo(OPINION)).willReturn(aResponse().withStatus(500)));
        server.stubFor(get(urlPathEqualTo(DIVIDEND)).willReturn(aResponse().withStatus(500)));

        // DomesticOutlookClient가 「빈 값으로 실패한다」였지만 그대로 하면
        // @CircuitBreaker가 영원히 열리지 않는다(HackerNewsApi가 실제로 그 상태였다).
        // 삼키는 일은 StockService가 한다
        assertThatThrownBy(() -> client.outlook("005930", false))
                .isInstanceOf(IllegalStateException.class);

        server.verify(1, getRequestedFor(urlPathEqualTo(OPINION)));
        server.verify(1, getRequestedFor(urlPathEqualTo(DIVIDEND)));
    }

    @Test
    @DisplayName("ETF에는 투자의견을 안 묻고 배당만 묻는다 — 분배금이 그 응답에 온다")
    void asksOnlyTheDividendForAFund() {
        // ⚠️ **ETF도 이 응답에 행을 준다** — 코드가 보내는 ±180일 창으로 실측했다(2026-09-08):
        //    KODEX 200(069500)이 두 행이다. 아래가 그 실측 본문이고, **한 행의 연도만** 이 테스트의
        //    고정 시각(2026-08-21) 뒤로 옮겼다(실측 행은 둘 다 지난 것이라 그대로 쓰면 「다음 배당」이
        //    비어 규칙을 못 본다). 투자의견은 ETF에 0행이므로(실측 426030) 그 1초는 안 쓰고,
        //    분배금은 알면서 버리지 않는다.
        //    ⚠️ divi_kind가 ETF 행에서는 빈 문자열이다(기업은 「분기」·「결산」) — 우리가 읽지 않는 필드다
        stub(DIVIDEND, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output1":[
                  {"record_date":"20261031","sht_cd":"069500","isin_name":"삼성KODEX200상장지수투자신탁",
                   "divi_kind":"","per_sto_divi_amt":"183","divi_pay_dt":"2026/11/04",
                   "stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통"},
                  {"record_date":"20260430","sht_cd":"069500","isin_name":"삼성KODEX200상장지수투자신탁",
                   "divi_kind":"","per_sto_divi_amt":"446","divi_pay_dt":"2026/05/06",
                   "stk_div_pay_dt":"","odd_pay_dt":"","stk_kind":"보통"}]}""");

        StockOutlook outlook = client.outlook("069500", true);

        assertThat(outlook.dividend())
                .isEqualTo(new StockOutlook.Dividend(LocalDate.of(2026, 10, 31),
                        LocalDate.of(2026, 11, 4), new BigDecimal("183")));
        assertThat(outlook.targetPrice()).isNull();
        server.verify(0, getRequestedFor(urlPathEqualTo(OPINION)));
    }

    @Test
    @DisplayName("ETF는 배당 조회가 죽으면 던진다 — 물은 다리가 그것뿐이라 살릴 것이 없다")
    void throwsWhenTheOnlyCallForAFundFails() {
        server.stubFor(get(urlPathEqualTo(DIVIDEND)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.outlook("069500", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("무효 토큰(EGW00121)을 알아보면 버린다 — 안 버리면 최대 24시간 모든 KIS가 죽는다")
    void discardsAnInvalidToken() {
        String invalidToken = """
                {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}""";
        server.stubFor(get(urlPathEqualTo(OPINION)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody(invalidToken)));
        server.stubFor(get(urlPathEqualTo(DIVIDEND)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody(invalidToken)));

        assertThatThrownBy(() -> client.outlook("005930", false)).isInstanceOf(IllegalStateException.class);

        assertThat(tokens.invalidated())
                .as("KIS 클라이언트 셋의 공통 계약이다 — 어느 쪽이 먼저 알아차려도 같은 일을 한다")
                .isTrue();
    }

    @Test
    @DisplayName("배당 다리만 무효 토큰을 만나도 버린다 — 살아남는 답이 그것을 덮으면 안 된다")
    void discardsAnInvalidTokenNoticedByTheDividendLeg() {
        // ⚠️ 목표가 다리는 200으로 살아 있다. 그래도 무효 토큰을 알아차린 쪽이 버려야 한다 —
        //    안 버리면 다음 조회도 같은 죽은 토큰으로 나가고, 그 창이 최대 24시간이다
        stub(OPINION, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":[
                  {"stck_bsop_date":"20260810","mbcr_name":"키움","hts_goal_prc":"350000"}]}""");
        server.stubFor(get(urlPathEqualTo(DIVIDEND)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}""")));

        assertThat(client.outlook("005930", false).targetPrice())
                .as("목표가는 살아 있다 — 그래도 무효 토큰은 버려야 한다")
                .isEqualByComparingTo(new BigDecimal("350000"));
        assertThat(tokens.invalidated()).isTrue();
    }
}
