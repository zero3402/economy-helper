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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 함정들을 고정한다.
 *
 * <p>가장 중요한 건 <b>서비스키를 다시 인코딩하면 안 된다</b>는 점이다.
 * 발급된 키는 이미 URL 인코딩된 형태(`%` 포함)라, 한 번 더 인코딩하면
 * 403 "등록되지 않은 서비스키"가 난다.
 */
class StockPriceApiTest {

    /** `%2B`가 들어간, 이미 인코딩된 형태의 키. 실제 키와 같은 모양이다. */
    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";

    /** 2026-08-12(수) 12:00 KST. 오늘은 데이터가 없으므로 어제부터 되짚는다. */
    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");
    private static final String PATH = "/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo";

    private WireMockServer server;
    private StockPriceApi api;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        api = new StockPriceApi(RestClient.builder(), server.baseUrl(), ENCODED_KEY,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")), null);
    }

    @AfterEach
    void stopServer() {
        server.stop();
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
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":%d,"items":{"item":[%s]}}}}
                """.formatted(rows.length, String.join(",", rows));
    }

    private static String row(String basDt, String code, String name, String close, String cap) {
        return """
                {"basDt":"%s","srtnCd":"%s","isinCd":"KR7%s003","itmsNm":"%s",
                 "mrktCtg":"KOSPI","clpr":"%s","mrktTotAmt":"%s"}
                """.formatted(basDt, code, code, name, close, cap);
    }

    private static String empty() {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0}}}";
    }

    @Test
    @DisplayName("서비스키를 다시 인코딩하지 않는다 — 하면 403 '등록되지 않은 서비스키'가 난다")
    void neverReEncodesServiceKey() {
        stub("20260811", body(row("20260811", "005930", "삼성전자", "239500", "1400183726616000")));

        api.searchByName("삼성전자");

        // WireMock은 쿼리 파라미터를 디코딩해 비교한다. 우리가 키를 그대로 보냈다면
        // 디코딩 결과가 원래 값(abc+def/ghi==)이고, 한 번 더 인코딩했다면
        // 디코딩해도 %2B가 남아 ENCODED_KEY 그대로 보인다 — 그게 403을 부르는 상태다.
        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("serviceKey", WireMock.equalTo("abc+def/ghi==")));
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("serviceKey", WireMock.equalTo(ENCODED_KEY)));
    }

    @Test
    @DisplayName("종목명은 인코딩해서 보낸다 — 한글이 그대로 나가면 안 된다")
    void encodesQueryValues() {
        stub("20260811", body(row("20260811", "005930", "삼성전자", "239500", "1400183726616000")));

        api.searchByName("삼성전자");

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("likeItmsNm", WireMock.equalTo("삼성전자")));
    }

    @Test
    @DisplayName("오늘이 아니라 어제부터 찾는다 — 오늘 데이터는 항상 0건이다")
    void startsFromYesterday() {
        stub("20260811", body(row("20260811", "005930", "삼성전자", "239500", "1400183726616000")));

        assertThat(api.searchByName("삼성전자")).hasSize(1);

        server.verify(0, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("basDt", WireMock.equalTo("20260812")));
    }

    @Test
    @DisplayName("비어 있으면 하루씩 물려 되짚는다 — 주말·연휴가 그렇다")
    void fallsBackToPreviousBusinessDay() {
        stub("20260811", empty());
        stub("20260810", empty());
        stub("20260809", body(row("20260809", "005930", "삼성전자", "230000", "1344644079840000")));

        List<StockPrice> found = api.searchByName("삼성전자");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).basDt()).isEqualTo("20260809");
    }

    @Test
    @DisplayName("10일을 되짚어도 없으면 빈 목록 — 무한히 과거로 가면 한도를 태운다")
    void givesUpAfterLookbackLimit() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(empty())));

        assertThat(api.searchByName("없는종목")).isEmpty();
        server.verify(10, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("종목코드는 likeSrtnCd로 보낸다 — srtnCd는 무시되어 엉뚱한 종목이 나온다")
    void usesLikeSrtnCdNotSrtnCd() {
        stub("20260811", body(row("20260811", "005930", "삼성전자", "239500", "1400183726616000")));

        api.searchByCode("005930");

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("likeSrtnCd", WireMock.equalTo("005930")));
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("srtnCd", WireMock.matching(".*")));
    }

    @Test
    @DisplayName("예외 메시지에 서비스키가 새지 않는다 — 이 API는 키를 URL에 싣는다")
    void neverLeaksServiceKeyInExceptions() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> api.searchByName("삼성전자"))
                .hasMessageNotContaining(ENCODED_KEY)
                .hasMessageNotContaining("serviceKey");
    }

    @Test
    @DisplayName("items가 없는 응답도 견딘다 — totalCount 0이면 items 자체가 빠진다")
    void toleratesMissingItems() {
        stub("20260811", empty());
        stub("20260810", body(row("20260810", "005930", "삼성전자", "230000", "1344644079840000")));

        assertThat(api.searchByName("삼성전자")).hasSize(1);
    }
}
