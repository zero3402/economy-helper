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
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 지수 조회의 HTTP 계층.
 *
 * <p><b>이 파일이 없었다.</b> {@code DataGoStockClientTest}의 javadoc이 "{@code MarketIndexApiTest}가
 * 따로 본다"고 적어 두고 있었는데 실제로는 존재하지 않아, 같은 게이트웨이를 쓰는 형제
 * ({@code StockPriceApi})만 스텁 테스트를 갖고 있었다.
 *
 * <p>{@code StockPriceApi}와 같은 함정을 공유한다 — 서비스키를 다시 인코딩하면 403이고,
 * 오늘 데이터가 없으면 영업일을 되짚는다. 다른 것은 조회 키뿐이다({@code likeIdxNm}).
 */
class MarketIndexApiTest {

    /** 이미 인코딩된 형태의 키. 다시 인코딩하면 403 "등록되지 않은 서비스키"가 난다. */
    private static final String ENCODED_KEY = "abc%2Bdef%2Fghi%3D%3D";

    /** 2026-08-12(수) 12:00 KST. 오늘은 데이터가 없어 어제부터 되짚는다. */
    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");
    private static final String PATH = "/1160100/service/GetMarketIndexInfoService/getStockMarketIndex";

    private WireMockServer server;
    private MarketIndexApi api;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        api = new MarketIndexApi(RestClient.builder(), server.baseUrl(), ENCODED_KEY,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")), null);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("이름이 없는 후보가 섞여도 조회가 죽지 않는다 — 폴백 비교자가 널을 역참조했다")
    void survivesACandidateWithoutAName() {
        // 완전일치 쪽은 널을 받아 주는데 "가장 짧은 이름" 폴백만 idxNm().length()를
        // 무방비로 불렀다. 이름 하나가 비어 오면 NPE로 조회 전체가 죽고, 그건 화면에서
        // "그런 지수가 없다"와 구분되지 않는다
        stub("20260811", body(
                row("20260811", null, "3182.44", "0.42"),
                row("20260811", "코스피 200", "421.10", "0.30")));

        MarketIndex found = api.searchByName("코스피");

        assertThat(found).isNotNull();
        assertThat(found.idxNm()).isEqualTo("코스피 200");
    }

    @Test
    @DisplayName("완전일치가 부분일치를 이긴다 — 파생 지수보다 본 지수가 답이다")
    void exactNameBeatsPartialMatch() {
        stub("20260811", body(
                row("20260811", "코스피 200 ESG", "1234.00", "0.10"),
                row("20260811", "코스피", "3182.44", "0.42")));

        assertThat(api.searchByName("코스피").clpr()).isEqualTo("3182.44");
    }

    @Test
    @DisplayName("오늘 데이터가 없으면 영업일을 되짚는다 — 주말·휴장에는 빈 목록이 온다")
    void walksBackToTheLatestBusinessDay() {
        stub("20260811", body());
        stub("20260810", body(row("20260810", "코스피", "3150.00", "-0.20")));

        assertThat(api.searchByName("코스피").basDt()).isEqualTo("20260810");
    }

    @Test
    @DisplayName("서비스키를 다시 인코딩하지 않는다 — 발급 키가 이미 인코딩돼 있다")
    void neverReEncodesTheServiceKey() {
        stub("20260811", body(row("20260811", "코스피", "3182.44", "0.42")));

        api.searchByName("코스피");

        // WireMock은 쿼리 파라미터를 디코딩해 비교한다. 그대로 보냈다면 디코딩 결과가
        // 원래 값(abc+def/ghi==)이고, 한 번 더 인코딩했다면 %2B가 남는다 — 그게 403이다
        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("serviceKey", WireMock.equalTo("abc+def/ghi==")));
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("serviceKey", WireMock.equalTo(ENCODED_KEY)));
    }

    @Test
    @DisplayName("예외 메시지에 서비스키가 새지 않는다 — 이 API는 키를 쿼리에 싣는다")
    void neverLeaksTheServiceKey() {
        server.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> api.searchByName("코스피"))
                .hasMessageNotContaining(ENCODED_KEY)
                .hasMessageNotContaining("serviceKey");
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

    private static String row(String basDt, String name, String close, String change) {
        String idxNm = name == null ? "null" : "\"" + name + "\"";
        return """
                {"basDt":"%s","idxNm":%s,"clpr":"%s","fltRt":"%s"}
                """.formatted(basDt, idxNm, close, change);
    }
}
