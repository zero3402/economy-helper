package io.saiden.economyhelper.market.data;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 증권상품시세정보(ETF)를 실제로 호출해 확인한 것들(2026-08-28, 활용신청 승인 뒤)을 고정한다.
 * 스텁 본문은 그날 받은 응답을 줄인 것이다 — 필드 이름과 값이 그대로다({@code fltRt}가 {@code -.14}).
 */
class EtfPriceApiTest {

    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";
    /** 2026-08-28(목) 12:00 KST. 어제(27일)부터 되짚는다. */
    private static final Instant NOW = Instant.parse("2026-08-28T03:00:00Z");
    private static final String PATH = "/1160100/service/GetSecuritiesProductInfoService/getETFPriceInfo";

    private static final String TIME_NASDAQ = """
            {"basDt":"20260826","srtnCd":"426030","isinCd":"KR7426030003","itmsNm":"TIME 미국나스닥100액티브",
             "clpr":"44930","vs":"-65","fltRt":"-.14","nav":"44984.47","mkp":"45045","hipr":"45095","lopr":"44805",
             "trqu":"87543","trPrc":"3934106740","mrktTotAmt":"2428915800000","stLstgCnt":"54060000",
             "bssIdxIdxNm":"NASDAQ 100","bssIdxClpr":"23555.11","nPptTotAmt":"2431462300000"}""";
    private static final String TIGER_NASDAQ = """
            {"basDt":"20260826","srtnCd":"133690","itmsNm":"TIGER 미국나스닥100","clpr":"178630","fltRt":"-.22",
             "mrktTotAmt":"11309065300000"}""";

    /** 활용신청 전에 실제로 온 본문 — HTTP 403이고 봉투 모양이 정상 응답과 다르다. */
    private static final String NOT_REGISTERED = """
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
             "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}""";

    private static WireMockServer server;
    private EtfPriceApi api;

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
        server.resetAll();
        api = new EtfPriceApi(RestClient.builder(), server.baseUrl(), ENCODED_KEY,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")), null);
    }

    @Test
    @DisplayName("이름은 likeItmsNm으로 묻고 주식과 같은 다섯 값으로 옮긴다 — 같은 캐시·같은 선택 규칙을 탄다")
    void searchesByNameAndMapsToStockPrice() {
        stub("20260827", empty());
        stub("20260826", body(TIME_NASDAQ, TIGER_NASDAQ));

        assertThat(api.searchByName("나스닥100")).containsExactly(
                new StockPrice("20260826", "TIME 미국나스닥100액티브", "44930", "-.14", "2428915800000"),
                new StockPrice("20260826", "TIGER 미국나스닥100", "178630", "-.22", "11309065300000"));
        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("likeItmsNm", WireMock.equalTo("나스닥100"))
                .withQueryParam("serviceKey", WireMock.equalTo("abc+def/ghi==")));
    }

    @Test
    @DisplayName("코드는 likeSrtnCd로 묻고 srtnCd로 한 번 더 거른다 — srtnCd 필터는 무시되어 1,164건이 통째로 왔다")
    void searchesByCodeAndFiltersTheAnswerByCode() {
        // 필터가 무시된 모양을 그대로 흉내 낸다: 물어본 코드와 다른 ETF가 함께 온다
        stub("20260827", body(TIGER_NASDAQ, TIME_NASDAQ));

        assertThat(api.searchByCode("426030")).extracting(StockPrice::itmsNm)
                .as("같이 온 다른 ETF는 버린다 — 시총 1위를 고르면 아무 코드에나 TIGER가 답한다")
                .containsExactly("TIME 미국나스닥100액티브");
        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("likeSrtnCd", WireMock.equalTo("426030")));
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("srtnCd", WireMock.matching(".*")));
    }

    @Test
    @DisplayName("활용신청이 안 된 키의 403은 사유를 실어 던진다 — 로그 한 줄로 「신청이 안 됐다」를 알 수 있어야 한다")
    void throwsWithTheRegistrationReasonOnForbidden() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json").withBody(NOT_REGISTERED)));

        assertThatThrownBy(() -> api.searchByName("나스닥100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403 SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
                .hasMessageNotContaining(ENCODED_KEY);
    }

    @Test
    @DisplayName("에러 봉투의 사유만 꺼낸다 — 없으면 빈 문자열, 상태 코드는 호출부가 붙인다")
    void extractsTheErrorMessageFromTheEnvelope() {
        assertThat(DataGoRequest.errorMessageOf(NOT_REGISTERED)).isEqualTo("SERVICE_KEY_IS_NOT_REGISTERED_ERROR");
        assertThat(DataGoRequest.errorMessageOf("<html>")).isEmpty();
        assertThat(DataGoRequest.errorMessageOf(null)).isEmpty();
    }

    private void stub(String basDt, String body) {
        server.stubFor(get(urlPathEqualTo(PATH))
                .withQueryParam("basDt", WireMock.equalTo(basDt))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static String body(String... rows) {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                 "body":{"numOfRows":100,"pageNo":1,"totalCount":%d,"items":{"item":[%s]}}}}
                """.formatted(rows.length, String.join(",", rows));
    }

    private static String empty() {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0}}}";
    }
}
