package io.saiden.economyhelper.market.weather.accu;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.weather.GeoLocation;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 좌표 → 지점 키. <b>예보를 부르기 전에 반드시 한 번 나가는 호출</b>이라, 여기가 조용히
 * 실패하면 날씨가 통째로 폴백한다.
 *
 * <p>스텁은 2026-08-18 실측 응답을 줄인 것이다 — 미금역이 구미1동(키 2331758)으로 잡혔다.
 */
class AccuLocationApiTest {

    private static final String API_KEY = "secret-key-1234";
    private static final String PATH = "/locations/v1/cities/geoposition/search";

    private static final GeoLocation MIGEUM =
            new GeoLocation("미금역", null, 37.35, 127.10889, ZoneId.of("Asia/Seoul"));

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;
    private AccuLocationApi api;

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
        // 스텁·요청기록·시나리오를 함께 비운다 — 서버는 그대로 두고 상태만 되돌린다
        server.resetAll();
        api = new AccuLocationApi(RestClient.builder(), server.baseUrl(), API_KEY);
    }

    private void stub(String body) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("좌표로 지점 키를 받는다 — 실측에서 미금역이 구미1동으로 잡혔다")
    void readsTheLocationKey() {
        stub("""
                {"Key":"2331758","LocalizedName":"구미1동","EnglishName":"Gumi 1(il)-dong",
                 "TimeZone":{"Name":"Asia/Seoul"},"GeoPosition":{"Latitude":37.354,"Longitude":127.088}}
                """);

        assertThat(api.keyOf(MIGEUM)).isEqualTo("2331758");
    }

    @Test
    @DisplayName("좌표를 쉼표로 이어 q에 싣는다 — 이 API는 lat·lon을 따로 받지 않는다")
    void sendsCoordinatesAsOneQueryParam() {
        stub("{\"Key\":\"2331758\"}");

        api.keyOf(MIGEUM);

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("q", WireMock.equalTo("37.35,127.10889")));
    }

    @Test
    @DisplayName("짚어 준 지점이 없으면 던진다 — 빈 키로 예보를 부르면 404가 난다")
    void throwsWhenNoPlaceIsReturned() {
        stub("{}");

        assertThatThrownBy(() -> api.keyOf(MIGEUM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("미금역");
    }

    @Test
    @DisplayName("예외 메시지에 API 키가 새지 않는다 — 이 API는 키를 쿼리에 싣는다")
    void neverLeaksApiKey() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> api.keyOf(MIGEUM))
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining("apikey");
    }

    @Test
    @DisplayName("503은 한도 소진으로 구분한다 — 자정에 풀리는 것과 키가 틀린 것은 다르다")
    void distinguishesQuotaExhaustion() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)
                .withBody("The allowed number of requests has been exceeded.")));

        assertThatThrownBy(() -> api.keyOf(MIGEUM)).hasMessageContaining("한도");
    }

    @Test
    @DisplayName("401·403은 키 문제로 구분한다 — 기다려도 안 풀린다")
    void distinguishesBadKey() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> api.keyOf(MIGEUM))
                .hasMessageContaining("키가 잘못됐거나")
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    @DisplayName("키가 없으면 부르지 않는다 — 빈 키로 호출하면 한도만 축낸다")
    void skipsCallWithoutApiKey() {
        AccuLocationApi keyless = new AccuLocationApi(RestClient.builder(), server.baseUrl(), "");

        assertThatThrownBy(() -> keyless.keyOf(MIGEUM)).hasMessageContaining("키");
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
