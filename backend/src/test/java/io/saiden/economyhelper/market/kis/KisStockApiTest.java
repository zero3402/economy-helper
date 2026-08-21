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
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.KisIndex;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 KIS 시세의 성질을 고정한다(2026-08-18, 모의 계정).
 *
 * <p>스텁 본문은 그날 실제로 받은 응답을 줄인 것이다 — 필드 이름과 값이 그대로다.
 * 그래서 여기가 초록이면 파서가 <b>실물</b>을 읽는다는 뜻이다.
 *
 * <p>잠그려는 것은 네 경로의 성공이 아니라 <b>서로 다르다는 사실</b>이다: 지수만 등락률
 * 필드 이름이 다르고, 지수 이름은 {@code "종합"}으로 오며, 미국 종목에는 달러 등락률이
 * 아예 없다. 하나라도 놓치면 값이 조용히 사라지거나 틀린 값이 화면에 나간다.
 */
class KisStockApiTest {

    private static final String STOCK_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String INDEX_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
    private static final String US_STOCK_PATH = "/uapi/overseas-price/v1/quotations/price-detail";
    private static final String US_INDEX_PATH =
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";

    /** KST 2026-08-18 17:00. */
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    private static final Index KOSPI = new Index("코스피", "0001");
    private static final UsSymbol NASDAQ = new UsSymbol("^IXIC", "나스닥");
    private static final UsSymbol APPLE = new UsSymbol("AAPL", "애플");
    private static final KisIndex NASDAQ_KIS = new KisIndex("^IXIC", "COMP");

    private WireMockServer server;
    private KisStockApi api;
    private RememberingCache exchanges;
    private KisFixtures.FixedToken tokens;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        exchanges = new RememberingCache();
        api = apiWith(List.of(KOSPI), List.of(NASDAQ_KIS));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    /**
     * 표는 둘이다 — 국내 지수(업종코드)와 미국 지수(KIS 심볼). <b>미국 종목은 표에 없다</b>:
     * 거래소를 스스로 찾기 때문이다.
     */
    private KisStockApi apiWith(List<Index> indices, List<KisIndex> usIndices) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        tokens = new KisFixtures.FixedToken(clock);
        return new KisStockApi(RestClient.builder(), server.baseUrl(),
                tokens, new KisHeaders("key", "secret"), clock,
                TestProperties.builder()
                        .digest(new Digest(null, null, indices, null, null, null))
                        .market(new EconomyHelperProperties.Market(
                                new EconomyHelperProperties.Kis(usIndices)))
                        .build(),
                // 간격을 지키는 문은 여기서 열어 둔다 — 규칙은 KisThrottleTest가 따로 본다
                exchanges, KisThrottle.none());
    }

    /** Redis 대신 메모리에 기억한다 — 규칙만 본다. */
    private static final class RememberingCache extends KisExchangeCache {
        private final java.util.Map<String, String> remembered = new java.util.HashMap<>();

        private RememberingCache() {
            super(null);
        }

        @Override
        String of(String symbol) {
            return remembered.get(symbol);
        }

        @Override
        void remember(String symbol, String exchange) {
            remembered.put(symbol, exchange);
        }
    }

    private void stub(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** 삼성전자 실측 응답을 줄인 것. */
    private void stubStock() {
        stub(STOCK_PATH, """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                 "output1":{"prdy_ctrt":"-2.19","stck_prdy_clpr":"274500","hts_kor_isnm":"삼성전자",
                            "stck_prpr":"268500","stck_shrn_iscd":"005930","per":"40.90"},
                 "output2":[{"stck_bsop_date":"20260818","stck_clpr":"268500"}]}
                """);
    }

    /**
     * 코스피 실측 응답을 줄인 것. <b>{@code prdy_ctrt}가 아예 없다</b> — 그게 핵심이다.
     * 2026-08-19 코스닥 실호출에서도 {@code prdy_ctrt}는 {@code null}이고
     * {@code bstp_nmix_prdy_ctrt}만 {@code 0.54}로 왔다.
     */
    private void stubIndex() {
        stub(INDEX_PATH, """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                 "output1":{"bstp_nmix_prdy_vrss":"-108.11","bstp_nmix_prdy_ctrt":"-1.55",
                            "hts_kor_isnm":"종합","bstp_nmix_prpr":"6869.83","bstp_cls_code":"0001"},
                 "output2":[{"stck_bsop_date":"20260818","bstp_nmix_prpr":"6869.83"}]}
                """);
    }

    /** 나스닥 종합 실측 응답을 줄인 것. 환율과 같은 스키마다. */
    private void stubUsIndex() {
        stub(US_INDEX_PATH, """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                 "output1":{"prdy_ctrt":"-0.32","hts_kor_isnm":"나스닥 종합",
                            "ovrs_nmix_prpr":"26644.91","stck_shrn_iscd":"COMP"},
                 "output2":[{"stck_bsop_date":"20260817","ovrs_nmix_prpr":"26644.91"}]}
                """);
    }

    @Test
    @DisplayName("국내 종목은 현재가와 한글명을 응답에서 읽는다 — 전일 종가가 아니다")
    void readsDomesticStock() {
        stubStock();

        StockQuote quote = api.stock("005930");

        assertThat(quote.name()).isEqualTo("삼성전자");
        assertThat(quote.price()).isEqualByComparingTo("268500");
        assertThat(quote.changePercent()).isEqualByComparingTo("-2.19");
        assertThat(quote.source()).isEqualTo(StockSource.KIS);
        assertThat(quote.currency()).isEqualTo(StockQuote.Money.KRW);
        assertThat(quote.market()).isEqualTo(StockQuote.Market.DOMESTIC);
        assertThat(quote.realtime())
                .as("이게 이 출처를 1순위에 세운 이유다 — 2순위는 전일 종가뿐이다")
                .isTrue();
    }

    @Test
    @DisplayName("국내 지수 등락률은 필드 이름이 종목과 다르다 — prdy_ctrt로 읽으면 조용히 사라진다")
    void readsTheIndexSpecificChangeField() {
        stubIndex();

        StockQuote quote = api.index(KOSPI);

        assertThat(quote.price()).isEqualByComparingTo("6869.83");
        assertThat(quote.changePercent())
                .as("응답에 prdy_ctrt는 아예 없다. 종목 이름으로 읽었다면 여기가 null이다")
                .isEqualByComparingTo("-1.55");
        assertThat(quote.currency())
                .as("지수는 통화 단위가 없다 — 원화 환산 대상이 아니다")
                .isEqualTo(StockQuote.Money.NONE);
    }

    @Test
    @DisplayName("지수 이름은 응답이 아니라 설정 것을 쓴다 — 코스피는 '종합', 코스닥은 'KOSDAQ'이다")
    void discardsTheGenericIndexName() {
        stubIndex();

        assertThat(api.index(KOSPI).name())
                .as("응답의 hts_kor_isnm을 그대로 쓰면 화면에 「종합」·「KOSDAQ」이 찍힌다")
                .isEqualTo("코스피");
    }

    @Test
    @DisplayName("국내 종목·지수는 시장 구분이 다르다 — J는 주식, U는 업종이다")
    void sendsTheRightMarketDivision() {
        stubStock();
        stubIndex();

        api.stock("005930");
        api.index(KOSPI);

        server.verify(getRequestedFor(urlPathEqualTo(STOCK_PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("J"))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("005930"))
                // 수정주가로 받는다 — 분할 전 가격이 섞이면 '지금 얼마냐'가 아니게 된다
                .withQueryParam("FID_ORG_ADJ_PRC", WireMock.equalTo("0"))
                // 오늘만 물으면 휴일·이른 아침에 빈손이 된다
                .withQueryParam("FID_INPUT_DATE_1", WireMock.equalTo("20260811"))
                .withQueryParam("FID_INPUT_DATE_2", WireMock.equalTo("20260818")));
        server.verify(getRequestedFor(urlPathEqualTo(INDEX_PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("U"))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("0001")));
    }

    @Test
    @DisplayName("미국 지수는 KIS 전용 심볼로 부른다 — ^IXIC를 그대로 주면 모른다")
    void translatesTheUsIndexSymbol() {
        stubUsIndex();

        StockQuote quote = api.quote(NASDAQ);

        assertThat(quote.name()).isEqualTo("나스닥");
        assertThat(quote.price()).isEqualByComparingTo("26644.91");
        assertThat(quote.changePercent()).isEqualByComparingTo("-0.32");
        assertThat(quote.market()).isEqualTo(StockQuote.Market.US);
        server.verify(getRequestedFor(urlPathEqualTo(US_INDEX_PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("N"))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("COMP")));
    }

    @Test
    @DisplayName("미국 종목 등락률은 last와 base로 낸다 — t_xrat은 원화 환산가 기준이라 틀리다")
    void computesTheDollarChangeItself() {
        // 실측값은 t_xrat과 우연히 같은 자리에 떨어져(둘 다 +0.20) 잘못 읽어도 통과한다.
        // 그래서 base만 벌려 둘이 확실히 갈리게 한다 — 실물 필드 이름은 그대로다
        stub(US_STOCK_PATH, """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                 "output":{"rsym":"DNASAAPL","curr":"USD","last":"306.1920","base":"300.0000",
                           "t_xprc":"432282","t_xrat":"+0.20","t_rate":"1411.80"}}
                """);

        StockQuote quote = api.quote(APPLE);

        assertThat(quote.price()).isEqualByComparingTo("306.1920");
        assertThat(quote.changePercent())
                .as("t_xrat(+0.20)을 읽었다면 여기서 갈린다")
                .isEqualByComparingTo("2.06");
        assertThat(quote.currency()).isEqualTo(StockQuote.Money.USD);
        assertThat(quote.name())
                .as("응답에 이름이 아예 없다 — rsym뿐이라 표시 이름은 설정에서 온다")
                .isEqualTo("애플");
        server.verify(getRequestedFor(urlPathEqualTo(US_STOCK_PATH))
                .withQueryParam("EXCD", WireMock.equalTo("NAS"))
                .withQueryParam("SYMB", WireMock.equalTo("AAPL")));
    }

    @Test
    @DisplayName("검색이 비워 보낸 조회 키를 설정 표에서 채운다 — 브리핑과 같은 값이 나와야 한다")
    void fillsLookupKeysFromConfiguration() {
        stubIndex();
        stubUsIndex();

        // /stock 코스피 · /stock 나스닥이 오는 모양이다 — LLM은 업종코드도 KIS 심볼도 모른다
        assertThat(api.index(new Index("코스피", null)).name()).isEqualTo("코스피");
        assertThat(api.quote(new UsSymbol("^IXIC", "나스닥 종합")).name())
                .as("이름은 부르는 쪽 것을 쓴다 — 표는 KIS 심볼만 준다")
                .isEqualTo("나스닥 종합");

        server.verify(getRequestedFor(urlPathEqualTo(INDEX_PATH))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("0001")));
        server.verify(getRequestedFor(urlPathEqualTo(US_INDEX_PATH))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("COMP")));
    }

    @Test
    @DisplayName("설정에 없는 미국 종목도 조회한다 — 나스닥에 없으면 뉴욕으로 다시 묻는다")
    void findsTheExchangeItself() {
        // 실측(2026-08-19): 거래소가 빗나가면 에러가 아니라 rt_cd=0에 41개 필드 값이
        // 전부 빈 문자열로 온다. 없는 티커도 똑같다 — 그래서 "비었으면 다음 거래소"가 성립한다.
        // 이 모양을 못 읽던 동안 /stock 유아이패스·오라클이 통째로 빈손이었다
        server.stubFor(get(urlPathEqualTo(US_STOCK_PATH))
                .withQueryParam("EXCD", WireMock.equalTo("NAS"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                                 "output":{"rsym":"","zdiv":"","curr":"","last":"","base":"",
                                           "t_xrat":"","t_rate":"","tvol":"","tamt":""}}""")));
        server.stubFor(get(urlPathEqualTo(US_STOCK_PATH))
                .withQueryParam("EXCD", WireMock.equalTo("NYS"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                                 "output":{"rsym":"DNYSPATH","curr":"USD",
                                           "last":"15.5800","base":"15.9900","t_xrat":"-2.56"}}""")));

        StockQuote quote = api.quote(new UsSymbol("PATH", "유아이패스"));

        assertThat(quote.name()).isEqualTo("유아이패스");
        assertThat(quote.price()).isEqualByComparingTo("15.5800");
        assertThat(quote.source()).isEqualTo(StockSource.KIS);
        assertThat(exchanges.remembered)
                .as("한 번 찾았으면 기억한다 — 거래소는 바뀌지 않는다")
                .containsEntry("PATH", "NYS");
    }

    @Test
    @DisplayName("나스닥이 초당 한도에 걸려도 뉴욕은 시도한다 — 초당 1건이라 그게 흔한 경로다")
    void triesTheNextExchangeEvenWhenTheFirstIsThrottled() {
        // rt_cd=1은 request()가 던진다. 예전에는 그 예외가 루프를 통째로 빠져나가
        // NYS를 시도조차 못 했다 — 빈 응답만 continue했기 때문이다.
        // 이 앱키는 초당 1건이라 탐색 두 번째 호출이 실제로 여기 걸린다
        server.stubFor(get(urlPathEqualTo(US_STOCK_PATH))
                .withQueryParam("EXCD", WireMock.equalTo("NAS"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"1","msg_cd":"EGW00201",
                                 "msg1":"초당 거래건수를 초과하였습니다."}""")));
        server.stubFor(get(urlPathEqualTo(US_STOCK_PATH))
                .withQueryParam("EXCD", WireMock.equalTo("NYS"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"0","output":{"rsym":"DNYSPATH","curr":"USD",
                                 "last":"15.5800","base":"15.9900"}}""")));

        StockQuote quote = api.quote(new UsSymbol("PATH", "유아이패스"));

        assertThat(quote.price()).isEqualByComparingTo("15.5800");
        assertThat(exchanges.remembered).containsEntry("PATH", "NYS");
    }

    @Test
    @DisplayName("거래소를 다 물어도 못 찾으면 던진다 — 빈 값을 돌려주면 2순위가 안 뜬다")
    void throwsWhenEveryExchangeFails() {
        server.stubFor(get(urlPathEqualTo(US_STOCK_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"rt_cd":"1","msg_cd":"EGW00201",
                                 "msg1":"초당 거래건수를 초과하였습니다."}""")));

        assertThatThrownBy(() -> api.quote(new UsSymbol("PATH", "유아이패스")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기억해 둔 거래소가 있으면 한 번만 부른다 — 탐색은 초당 한도를 두 배로 쓴다")
    void asksOnceWhenTheExchangeIsAlreadyKnown() {
        exchanges.remember("PATH", "NYS");
        stub(US_STOCK_PATH, """
                {"rt_cd":"0","output":{"rsym":"DNYSPATH","curr":"USD",
                 "last":"15.5800","base":"15.9900"}}""");

        api.quote(new UsSymbol("PATH", "유아이패스"));

        assertThat(server.getAllServeEvents()).hasSize(1);
        server.verify(getRequestedFor(urlPathEqualTo(US_STOCK_PATH))
                .withQueryParam("EXCD", WireMock.equalTo("NYS")));
    }

    @Test
    @DisplayName("현재가가 0이면 던진다 — 지수 심볼이 틀리면 에러가 아니라 0.00이 온다")
    void rejectsZeroAsAPrice() {
        // 실측: FID_INPUT_ISCD=DJI·DJIA가 rt_cd=0에 ovrs_nmix_prpr=0.00으로 왔다.
        // null만 보던 동안에는 화면에 지수 0이 찍히고 폴백도 일어나지 않았다
        stub(US_INDEX_PATH, """
                {"rt_cd":"0","output1":{"ovrs_nmix_prpr":"0.00","prdy_ctrt":"0.00"}}""");

        assertThatThrownBy(() -> api.quote(NASDAQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("값이 없습니다");
    }

    @Test
    @DisplayName("지수는 표에 없으면 부르지도 않고 던진다 — ^IXIC→COMP 같은 규칙이 없어 만들 요청이 없다")
    void neverGuessesAnIndexSymbol() {
        KisStockApi bare = apiWith(List.of(), List.of());

        // 국내 지수: KIS에는 지수명 검색이 아예 없다
        assertThatThrownBy(() -> bare.index(new Index("코스피 200", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("업종코드");
        // 미국 지수: ^DJI를 그대로 물으면 KIS는 모른다
        assertThatThrownBy(() -> bare.quote(new UsSymbol("^DJI", "다우")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("미국 지수");

        assertThat(server.getAllServeEvents())
                .as("던지는 것이 곧 2순위로 넘기는 것이다 — 그 전에 호출까지 태울 이유가 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("200에 실려 온 에러를 잡는다 — 초당 한도 초과가 실제로 이 모양으로 온다")
    void detectsErrorsCarriedInsideA200() {
        stub(STOCK_PATH, """
                {"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}
                """);

        assertThatThrownBy(() -> api.stock("005930"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초당 거래건수")
                .hasMessageContaining("rt_cd=1");
    }

    @Test
    @DisplayName("500 본문에 실려 온 이유를 꺼낸다 — 무효 토큰이 상대 서버 장애로 읽혔다")
    void readsTheReasonOutOfAnHttpError() {
        // 실측 본문이다(2026-08-19). KIS는 무효 토큰에 401이 아니라 500을 주고 이유는 본문에만
        // 있는데, 그 본문을 버리고 예외 이름만 남기던 동안 이 응답이 "InternalServerError"로
        // 읽혔다. 그래서 상대 서버 장애로 오진돼 "NYSE 종목은 어느 출처로도 못 온다"가
        // 문서 셋에 측정 사실로 적혔다 — 실제로는 유효한 토큰이면 다 온다
        server.stubFor(get(urlPathEqualTo(STOCK_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}
                        """)));

        assertThatThrownBy(() -> api.stock("005930"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("유효하지 않은 token")
                .hasMessageContaining("EGW00121")
                // 앞당겨 발급해도 낫지 않는다는 사실이 함께 있어야 한다. 없으면 다음 사람이
                // 앱을 다시 띄우며 발급을 연타하고, 그것이 이 상태를 만든 원인이다
                .hasMessageContaining("6시간");
    }

    @Test
    @DisplayName("무효 토큰을 알아차리면 버린다 — 안 버리면 기록된 만료까지 모든 KIS 호출이 죽는다")
    void throwsAwayATokenThatKisRejected() {
        // 이것이 /stock 유아이패스가 하루 종일 빈손이던 정체다. 500을 이유만 적고 넘어가면
        // 죽은 토큰이 최대 24시간 남아 그 창 동안 모든 KIS 호출이 같은 이유로 실패한다.
        // 미국 종목은 2순위(FMP)가 PATH·ORCL·SNOW를 402로 막아 KIS가 유일한 길이다
        server.stubFor(get(urlPathEqualTo(STOCK_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}
                        """)));

        assertThatThrownBy(() -> api.stock("005930")).isInstanceOf(IllegalStateException.class);

        assertThat(tokens.invalidated()).as("버려야 다음 호출이 새 토큰을 받아 스스로 낫는다").isTrue();
    }

    @Test
    @DisplayName("잘못된 앱시크릿(EGW00304)에는 토큰을 버리지 않는다 — 그건 사람이 키를 고쳐야 한다")
    void keepsTheTokenWhenTheSecretIsWrong() {
        // 실측(2026-08-20): 앱시크릿이 틀리면 KIS도 500을 준다. 즉 500은 영구 실패의 기본
        // 표현이다. 이걸 토큰 문제로 읽어 버리면 멀쩡한 토큰을 잃고 알림톡만 한 통 더 가고
        // 결과는 같다 — 우리가 스스로 고칠 수 있는 하나(EGW00121)만 갈라내야 한다
        server.stubFor(get(urlPathEqualTo(STOCK_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"고객식별키가 유효하지 않습니다.","msg_cd":"EGW00304"}
                        """)));

        assertThatThrownBy(() -> api.stock("005930")).isInstanceOf(IllegalStateException.class);

        assertThat(tokens.invalidated()).isFalse();
    }

    @Test
    @DisplayName("거래소가 전부 예외로 실패하면 '현재가가 없다'고 하지 않는다 — 확인한 적 없는 결론이다")
    void neverClaimsAbsenceItDidNotEstablish() {
        // 빈손의 원인이 둘인데 문장이 하나였다: 값이 정말 없는 것과, 물어보지도 못한 것.
        // 후자에 "응답에 현재가가 없습니다"가 나가면 종목이 상장 폐지된 것처럼 읽힌다
        server.stubFor(get(urlPathEqualTo(US_STOCK_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}
                        """)));

        assertThatThrownBy(() -> api.quote(new UsSymbol("ORCL", "오라클")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("유효하지 않은 token")
                .hasMessageNotContaining("현재가가 없습니다");
    }

    @Test
    @DisplayName("없는 종목코드는 rt_cd가 0인 채 값만 비어 온다 — 그것도 던져야 폴백한다")
    void throwsWhenThePriceIsMissing() {
        stub(STOCK_PATH, "{\"rt_cd\":\"0\",\"output1\":{}}");

        assertThatThrownBy(() -> api.stock("999999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("값이 없습니다");
    }

    @Test
    @DisplayName("예외 메시지에 접근토큰이 새지 않는다 — 헤더에 실려 있다")
    void neverLeaksTheToken() {
        server.stubFor(get(urlPathEqualTo(STOCK_PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> api.stock("005930"))
                .hasMessageNotContaining(KisFixtures.TOKEN)
                .hasMessageNotContaining("Bearer");
    }

    // --- 일봉(차트) ---
    //
    // ⚠️ 이 자리가 통째로 비어 있었다. 시세 셋은 촘촘히 덮여 있는데 일봉은 하나도 없었고,
    //    그래서 지수 둘을 배선하지 않은 것도 아무도 알려 주지 않았다.

    @Test
    @DisplayName("국내 종목 일봉은 output2에서 읽는다 — 시세는 output1이다")
    void readsDomesticStockSeries() {
        stub(STOCK_PATH, """
                {"rt_cd":"0","output1":{"stck_prpr":"268500"},
                 "output2":[{"stck_bsop_date":"20260818","stck_clpr":"268500"},
                            {"stck_bsop_date":"20260817","stck_clpr":"274500"},
                            {"stck_bsop_date":"20260814","stck_clpr":"271000"}]}""");

        assertThat(api.dailyBars("005930"))
                .as("응답은 최신순인데 그림은 왼쪽이 과거다 — DailySeries가 뒤집는다")
                .extracting(bar -> bar.date().toString())
                .containsExactly("2026-08-14", "2026-08-17", "2026-08-18");
    }

    @Test
    @DisplayName("국내 지수 일봉은 bstp_nmix_prpr다 — 종목 필드로 읽으면 통째로 빈다")
    void readsDomesticIndexSeries() {
        stubIndex();

        assertThat(api.dailyBarsOfIndex("코스피"))
                .singleElement()
                .satisfies(bar -> assertThat(bar.close()).isEqualByComparingTo("6869.83"));
        server.verify(getRequestedFor(urlPathEqualTo(INDEX_PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("U"))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("0001"))
                .withQueryParam("FID_PERIOD_DIV_CODE", WireMock.equalTo("D")));
    }

    @Test
    @DisplayName("미국 지수 일봉은 ^IXIC가 아니라 COMP로 묻는다 — 시세와 같은 표를 쓴다")
    void readsUsIndexSeries() {
        stubUsIndex();

        assertThat(api.dailyBarsOfUs("^IXIC"))
                .singleElement()
                .satisfies(bar -> assertThat(bar.close()).isEqualByComparingTo("26644.91"));
        server.verify(getRequestedFor(urlPathEqualTo(US_INDEX_PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("N"))
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("COMP")));
    }

    @Test
    @DisplayName("미국 종목도 같은 경로로 일봉을 받는다 — 표를 안 타고 심볼 그대로 묻는다")
    void readsUsStockSeriesThroughTheSamePath() {
        // ⚠️ 이것이 이번 실측의 핵심이다(2026-08-21, AAPL·NVDA·ORCL 모두 rt_cd=0 · 20행).
        //    지수용으로 만든 경로가 종목 심볼도 받아서, 거래소 코드도 새 레코드도 필요 없었다.
        //    후보였던 HHDFS76240000은 EXCD를 요구하고 xymd·clos라 필드를 새로 선언해야 했다
        stub(US_INDEX_PATH, """
                {"rt_cd":"0","msg1":"정상처리 되었습니다.",
                 "output2":[{"stck_bsop_date":"20260820","ovrs_nmix_prpr":"311.30"},
                            {"stck_bsop_date":"20260819","ovrs_nmix_prpr":"316.83"}]}""");

        assertThat(api.dailyBarsOfUs("AAPL"))
                .extracting(bar -> bar.close().toPlainString())
                .containsExactly("316.83", "311.30");
        server.verify(getRequestedFor(urlPathEqualTo(US_INDEX_PATH))
                .withQueryParam("FID_COND_MRKT_DIV_CODE", WireMock.equalTo("N"))
                // 종목은 표를 타지 않는다 — 심볼이 그대로 간다
                .withQueryParam("FID_INPUT_ISCD", WireMock.equalTo("AAPL")));
    }

    @Test
    @DisplayName("차트는 창이 더 넓다 — 거래일 열나흘을 담으려면 주말 넷을 넘겨야 한다")
    void widensTheWindowForSeries() {
        stubStock();

        api.dailyBars("005930");

        server.verify(getRequestedFor(urlPathEqualTo(STOCK_PATH))
                // 시세 경로는 이레(20260811)인데 차트는 스무닷새다
                .withQueryParam("FID_INPUT_DATE_1", WireMock.equalTo("20260724"))
                .withQueryParam("FID_INPUT_DATE_2", WireMock.equalTo("20260818")));
    }

    @Test
    @DisplayName("0.00은 값이 아니라 절벽이다 — 없는 코드에 에러가 아니라 0이 온다")
    void dropsZeroBars() {
        stub(STOCK_PATH, """
                {"rt_cd":"0","output1":{"stck_prpr":"268500"},
                 "output2":[{"stck_bsop_date":"20260818","stck_clpr":"268500"},
                            {"stck_bsop_date":"20260817","stck_clpr":"0.00"}]}""");

        assertThat(api.dailyBars("005930"))
                .as("그대로 그리면 차트가 0으로 떨어지는 절벽을 그린다")
                .singleElement()
                .satisfies(bar -> assertThat(bar.close()).isEqualByComparingTo("268500"));
    }

    @Test
    @DisplayName("표에 없는 미국 지수는 부르지도 않는다 — 만들 수 있는 요청이 아니다")
    void refusesAnUnknownUsIndexSeries() {
        assertThatThrownBy(() -> api.dailyBarsOfUs("^DJI"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("^DJI");

        server.verify(0, getRequestedFor(urlPathEqualTo(US_INDEX_PATH)));
    }

    @Test
    @DisplayName("업종코드 없는 지수는 부르지도 않는다 — KIS에는 지수명 검색이 없다")
    void refusesAnIndexSeriesWithoutACode() {
        assertThatThrownBy(() -> api.dailyBarsOfIndex("없는지수"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("업종코드");

        server.verify(0, getRequestedFor(urlPathEqualTo(INDEX_PATH)));
    }

    @Test
    @DisplayName("일봉이 아예 없으면 빈 목록 — 던지지 않는다. 값은 이미 시세가 냈다")
    void returnsEmptyWhenThereAreNoBars() {
        stub(STOCK_PATH, "{\"rt_cd\":\"0\",\"output1\":{\"stck_prpr\":\"268500\"}}");

        assertThat(api.dailyBars("005930"))
                .as("차트는 보충이지 폴백이 아니다 — 그 그림만 빠진다")
                .isEmpty();
    }
}
