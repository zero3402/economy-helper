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
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.KisIndex;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
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

    private static final String TOKEN = "secret-token-1234";
    /** KST 2026-08-18 17:00. */
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    private static final Index KOSPI = new Index("코스피", "0001");
    private static final UsSymbol NASDAQ = new UsSymbol("^IXIC", "나스닥");
    private static final UsSymbol APPLE = new UsSymbol("AAPL", "애플");
    private static final KisIndex NASDAQ_KIS = new KisIndex("^IXIC", "COMP");

    private WireMockServer server;
    private KisStockApi api;
    private RememberingCache exchanges;

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
        return apiWith(indices, usIndices, null);
    }

    private KisStockApi apiWith(List<Index> indices, List<KisIndex> usIndices,
                                RateLimiterRegistry limiters) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new KisStockApi(RestClient.builder(), server.baseUrl(),
                new FixedToken(clock), new KisHeaders("key", "secret"), clock,
                new EconomyHelperProperties(null, null,
                        new Digest(null, null, indices, null, null, null), null, null,
                        new EconomyHelperProperties.Market(
                                new EconomyHelperProperties.Kis(usIndices))),
                exchanges, limiters);
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
