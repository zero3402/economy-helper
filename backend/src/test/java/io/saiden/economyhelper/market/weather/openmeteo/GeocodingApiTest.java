package io.saiden.economyhelper.market.weather.openmeteo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.saiden.economyhelper.market.weather.GeoLocation;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 지오코딩이 <b>사람이 물었을 법한 곳</b>을 고르는지 고정한다.
 *
 * <p>여기가 깨지면 증상이 조용하다 — 엉뚱한 지점의 날씨가 그럴듯한 숫자로 나오고,
 * 이름이 영문으로 찍히는 것 말고는 티가 안 난다. 실제로 그 상태로 배포됐다.
 */
class GeocodingApiTest {

    private WireMockServer server;
    private GeocodingApi api;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        api = new GeocodingApi(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stub(String body) {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("인구가 가장 많은 후보를 고른다 — 이 API는 인구순으로 주지 않는다")
    void picksTheMostPopulatedCandidate() {
        // 2026-08-17 실측 응답을 줄여 옮긴 것이다. 경기도 성남시가 마지막에 있고,
        // 인구가 붙은 유일한 항목이다 — count=1이던 시절에는 첫 번째(남원시 마을)를 집었다
        stub("""
                {"results":[
                 {"name":"Seongnam","latitude":36.4052,"longitude":127.7548,
                  "country":"대한민국","timezone":"Asia/Seoul"},
                 {"name":"Seongnam","latitude":35.54127,"longitude":127.39683,
                  "country":"대한민국","timezone":"Asia/Seoul"},
                 {"name":"성남","latitude":35.48474,"longitude":129.30505,
                  "country":"대한민국","timezone":"Asia/Seoul"},
                 {"name":"성남시","latitude":37.43861,"longitude":127.13778,
                  "country":"대한민국","timezone":"Asia/Seoul","population":914832}]}""");

        GeoLocation place = api.find("성남", "KR").orElseThrow();

        assertThat(place.latitude()).isEqualTo(37.43861);
        assertThat(place.longitude()).isEqualTo(127.13778);
        assertThat(place.name())
                .as("옳은 지점을 집으면 이름도 저절로 한글이다 — 큰 도시에만 한글 별칭이 있다")
                .isEqualTo("성남시");
    }

    @Test
    @DisplayName("한 건만 받으면 안 된다 — 후보를 넉넉히 물어야 큰 도시가 딸려 온다")
    void asksForEnoughCandidates() {
        stub("""
                {"results":[{"name":"성남시","latitude":37.43861,"longitude":127.13778,
                 "country":"대한민국","timezone":"Asia/Seoul","population":914832}]}""");

        api.find("성남", "KR");

        server.verify(getRequestedFor(urlPathEqualTo("/v1/search"))
                .withQueryParam("count", equalTo("10"))
                .withQueryParam("language", equalTo("ko"))
                .withQueryParam("countryCode", equalTo("KR")));
    }

    @Test
    @DisplayName("인구가 붙은 후보가 없으면 첫 번째를 쓴다 — 그때는 API 순서가 유일한 단서다")
    void fallsBackToTheFirstWhenNobodyHasPopulation() {
        // 실측에서 '잠실'이 이 경우였다 — 10건 전부 인구가 없다
        stub("""
                {"results":[
                 {"name":"잠실","latitude":37.6087,"longitude":127.377,
                  "country":"대한민국","timezone":"Asia/Seoul"},
                 {"name":"Jamsil","latitude":36.9533,"longitude":127.1524,
                  "country":"대한민국","timezone":"Asia/Seoul"}]}""");

        GeoLocation place = api.find("잠실", "KR").orElseThrow();

        assertThat(place.latitude()).isEqualTo(37.6087);
    }

    @Test
    @DisplayName("못 찾은 것은 실패가 아니다 — 예외를 던지면 브레이커에 애먼 실패가 쌓인다")
    void treatsAnEmptyResultAsAbsenceNotFailure() {
        stub("{}");

        assertThat(api.find("ㅁㄴㅇㄹ", null)).isEmpty();
    }

    @Test
    @DisplayName("나라 코드를 모르면 그 조건을 아예 빼고 묻는다")
    void omitsCountryCodeWhenUnknown() {
        stub("""
                {"results":[{"name":"파리","latitude":48.85341,"longitude":2.3488,
                 "country":"프랑스","timezone":"Europe/Paris","population":2138551}]}""");

        GeoLocation place = api.find("파리", null).orElseThrow();

        assertThat(place.name()).isEqualTo("파리");
        assertThat(place.country()).isEqualTo("프랑스");
        assertThat(place.zone()).isEqualTo(ZoneId.of("Europe/Paris"));
        server.verify(getRequestedFor(urlPathEqualTo("/v1/search"))
                .withQueryParam("countryCode", absent()));
    }

    @Test
    @DisplayName("시간대가 없으면 UTC로 둔다 — KST로 채우면 남의 하루를 우리 달력으로 자른다")
    void neverAssumesSeoulWhenTheZoneIsMissing() {
        stub("""
                {"results":[{"name":"어딘가","latitude":1.0,"longitude":1.0,
                 "country":"어느나라"}]}""");

        Optional<GeoLocation> place = api.find("어딘가", null);

        assertThat(place.orElseThrow().zone()).isEqualTo(ZoneId.of("UTC"));
    }
}
