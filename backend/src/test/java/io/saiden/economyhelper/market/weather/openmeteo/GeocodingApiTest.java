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
    @DisplayName("모르는 시간대는 UTC로 둔다 — 시간대 하나 때문에 그 도시의 /weather가 통째로 죽지 않는다")
    void unknownTimezoneFallsBackToUtc() {
        // ⚠️ ZoneId.of는 형식이 맞아도 JDK의 tzdb에 없는 이름이면 던진다. 「못 읽으면 UTC로
        //    둔다」를 적어 두고 null만 막고 있었는데, 새 시간대가 생기면 출처가 먼저 알고
        //    JDK가 나중에 따라오므로 실재하는 길이다. 던지면 그 도시의 조회가 통째로 실패하고
        //    멀쩡한 지오코딩 브레이커에 실패가 쌓인다
        stub("""
                {"results":[{"name":"어딘가","latitude":31.7,"longitude":-106.4,
                 "country":"멕시코","timezone":"Mars/Olympus_Mons","population":1500000}]}""");

        var place = api.find("어딘가", null);

        assertThat(place).isPresent();
        assertThat(place.get().zone())
                .as("모르는 시간대에 KST를 채우면 남의 하루를 우리 달력으로 자르게 된다")
                .isEqualTo(java.time.ZoneId.of("UTC"));
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
    @DisplayName("인구가 붙은 후보가 없으면 아무것도 고르지 않는다 — 첫 결과를 집던 것이 국내를 통째로 망가뜨렸다")
    void refusesToGuessWhenNobodyHasPopulation() {
        // 실측(2026-08-19): 한국어 짧은 지명은 이 모양으로만 온다. 첫 결과로 떨어지던 동안
        // '서현'은 김포시(37.65, 126.60), '성남'은 전라북도(35.54, 127.40)를 집고 있었다 —
        // 분당에서 200km다. 인구가 곧 "이게 진짜 도시냐"의 신호다
        stub("""
                {"results":[
                 {"name":"서현","latitude":37.6461,"longitude":126.6049,
                  "country":"대한민국","timezone":"Asia/Seoul"},
                 {"name":"Seohyeon","latitude":36.9533,"longitude":127.1524,
                  "country":"대한민국","timezone":"Asia/Seoul"}]}""");

        assertThat(api.find("서현", "KR"))
                .as("틀린 좌표로 그럴듯한 답을 주는 것이 빈손보다 나쁘다")
                .isEmpty();
    }

    @Test
    @DisplayName("로마자로 와도 상대가 준 날것을 담는다 — 표기를 고르는 것은 읽을 때다")
    void keepsTheRawNameSoTheLabelIsNotFrozenInTheCache() {
        // 실측: '제주시'를 찾으면 이름이 Jejudo로 온다. 그대로 쓰면 전부 한글인 화면에
        // 지명만 로마자로 튄다 — 그래서 바꿔야 하지만, ⚠️ **여기서** 바꾸면 안 된다.
        // 이 결과가 geocode 캐시에 30일 들어가므로 파생된 표기가 캐시에 굳는다.
        // 규칙을 고쳐도 이름이 코드를 따라오지 않아 /weather 미금이 고침 뒤에도
        // 'Seongnam, 대한민국'을 답했다. 담는 것은 날것, 만드는 것은 읽을 때다.
        stub("""
                {"results":[{"name":"Jejudo","latitude":33.4022,"longitude":126.5464,
                 "country":"대한민국","timezone":"Asia/Seoul","population":621550}]}""");

        GeoLocation place = api.find("제주시", "KR").orElseThrow();

        assertThat(place.name()).as("캐시에 담기는 것은 상대가 준 이름이다").isEqualTo("Jejudo");
        assertThat(place.labelledFor("제주시").name())
                .as("한국어 표기는 읽을 때 만든다 — 그래야 규칙을 고치면 옛 캐시까지 낫는다")
                .isEqualTo("제주시");
        assertThat(place.country()).as("나라는 손대지 않는다 — 그쪽은 제대로 한국어로 온다")
                .isEqualTo("대한민국");
        assertThat(place.latitude()).as("좌표는 여전히 지오코딩이 확정한다").isEqualTo(33.4022);
    }

    @Test
    @DisplayName("한글로 온 이름은 물어본 말로 덮지 않는다 — 상대가 확정한 표기가 이긴다")
    void keepsTheGeocodedNameWhenItIsAlreadyKorean() {
        // 실측: name=성남시&countryCode=KR → '성남시' / 인구 914,832 / 경기도 성남시.
        // 물어본 말이 '미금'이어도 화면에는 실제로 조회한 '성남시'가 적혀야 검산이 된다
        stub("""
                {"results":[{"name":"성남시","latitude":37.43861,"longitude":127.13778,
                 "country":"대한민국","timezone":"Asia/Seoul","population":914832}]}""");

        GeoLocation place = api.find("성남시", "KR").orElseThrow();

        assertThat(place.labelledFor("미금").name()).isEqualTo("성남시");
        assertThat(place.labelledFor("미금").displayName()).isEqualTo("성남시, 대한민국");
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
                 "country":"어느나라","population":50000}]}""");

        Optional<GeoLocation> place = api.find("어딘가", null);

        assertThat(place.orElseThrow().zone()).isEqualTo(ZoneId.of("UTC"));
    }
}
