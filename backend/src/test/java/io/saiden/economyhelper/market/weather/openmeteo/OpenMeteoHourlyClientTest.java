package io.saiden.economyhelper.market.weather.openmeteo;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * <b>이 파일이 없었다 — 그리고 그게 버그를 오래 살렸다.</b>
 *
 * <p>이 클라이언트는 <b>AccuWeather가 일별을 맡은 날의 유일한 시각 공급원</b>이다.
 * 1순위가 하루를 낮/밤 두 칸으로만 주므로, 화면의 {@code ☔ 오후 12시~6시} 줄은 언제나 여기서
 * 나온다. 그런데 실패도 빈손도 조용히 삼켜지는 자리라 <b>안 되면 그냥 줄이 사라진다</b> —
 * 「강수확률은 높은데 시각이 없다」는 신고의 절반이 이 경로였고, 그것을 붙잡을 테스트가
 * 하나도 없었다({@code OpenMeteoForecastClient}에는 있는데 여기만 비어 있었다).
 *
 * <p>픽스처는 <b>실측이다</b> — 2026-08-22 미금역({@code 37.35,127.10889}), HTTP 200.
 * 24시간 전부를 그대로 붙였다. 줄이면 봉우리와 문턱의 관계가 바뀌어 토막이 달라진다.
 */
class OpenMeteoHourlyClientTest extends WireMockTest {

    private static final GeoLocation MIGEUM =
            new GeoLocation("미금역", null, 37.35, 127.10889, ZoneId.of("Asia/Seoul"));
    private static final LocalDate DAY = LocalDate.of(2026, 8, 22);

    private OpenMeteoHourlyClient client;

    @BeforeEach
    void resetAndBuild() {
        client = new OpenMeteoHourlyClient(RestClient.builder(), server.baseUrl());
    }

    private void stub(String body) {
        server.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private Map<LocalDate, List<HalfDay>> halves() {
        return client.halves(MIGEUM, new WeatherPeriod(DAY, DAY));
    }

    @Test
    @DisplayName("시간별만 묻는다 — 일별은 이미 손에 있고, timezone=auto가 없으면 「오후 1시」가 UTC 오후가 된다")
    void asksOnlyForHourlyInLocalTime() {
        stub(migeum());

        halves();

        server.verify(1, getRequestedFor(urlPathEqualTo("/v1/forecast"))
                .withQueryParam("hourly", WireMock.containing("precipitation_probability"))
                .withQueryParam("hourly", WireMock.containing("weather_code"))
                .withQueryParam("timezone", WireMock.equalTo("auto"))
                // 일별을 안 받는다 — 안 쓸 값을 받아 오면 응답만 무거워진다
                .withQueryParam("daily", WireMock.absent()));
    }

    @Test
    @DisplayName("실측 하루가 오전·오후 두 줄이 된다 — 오전의 두 토막은 센 쪽 하나로 접힌다")
    void foldsTheMeasuredDayIntoTwoHalves() {
        // 봉우리 92% → 문턱 max(50, 92×0.8) = 74%.
        // 03시(73%)·09~11시(69·69·71%)·19시 이후가 그 아래라 거기서 끊긴다.
        // 오전에는 00~02시와 04~08시 둘이 남는데 둘 다 92%이므로 앞선 쪽이 남는다.
        // 오후는 12시가 확률로만 통과하고 코드·양이 둘 다 마르다 — 13시부터다
        stub(migeum());

        Map<LocalDate, List<HalfDay>> byDay = halves();

        assertThat(byDay).containsOnlyKeys(DAY);
        assertThat(byDay.get(DAY)).satisfiesExactly(
                morning -> {
                    assertThat(morning.from()).isEqualTo(LocalTime.of(0, 0));
                    assertThat(morning.to()).as("03시가 73%라 문턱 아래다")
                            .isEqualTo(LocalTime.of(2, 0));
                    assertThat(morning.chance()).isEqualTo(92);
                    assertThat(morning.kind()).as("61·53·53 중 가장 무거운 61")
                            .isEqualTo(SkyCondition.RAIN);
                },
                afternoon -> {
                    // 12시는 확률 75%로 문턱을 넘지만 강수량이 0.00mm이고 코드가 2(구름 조금)다
                    // — 셋 중 둘이 「안 온다」고 말하므로 비로 치지 않는다. 그러지 않으면
                    // 그 시간이 젖은 줄에 들어가 이름 후보에 「구름 조금」이 끼어든다
                    assertThat(afternoon.from()).as("12시는 코드도 양도 마르다")
                            .isEqualTo(LocalTime.of(13, 0));
                    assertThat(afternoon.to()).isEqualTo(LocalTime.of(18, 0));
                    assertThat(afternoon.chance()).isEqualTo(90);
                    assertThat(afternoon.kind()).as("55가 가장 무겁다")
                            .isEqualTo(SkyCondition.DRIZZLE);
                });
    }

    /**
     * ⚠️ <b>이것이 신고의 핵심이다.</b> 이 응답이 손에 들어왔는데도 화면에 시각 줄이 없었다면
     * 원인은 문턱이 아니라 <b>이 호출이 아예 값을 못 가져온 것</b>이다 — 그날 미금역은
     * 봉우리가 92%였고 열두 시간 넘게 문턱을 넘었다.
     */
    @Test
    @DisplayName("확률이 높은 날에는 반드시 토막이 있다 — 「강수확률 92%인데 시각 줄 없음」이 성립하면 안 된다")
    void aHighChanceDayAlwaysYieldsAtLeastOneSpell() {
        stub(migeum());

        assertThat(halves().get(DAY)).isNotEmpty();
    }

    @Test
    @DisplayName("시간별이 없으면 빈 map이다 — 보충은 답을 죽이지 않는다")
    void returnsEmptyWhenTheResponseHasNoHourlyBlock() {
        stub("""
                {"latitude":37.35,"longitude":127.125,"timezone":"Asia/Seoul"}""");

        assertThat(halves()).isEmpty();
    }

    @Test
    @DisplayName("마른 날도 줄은 나온다 — 낮은 확률을 비라고 적지 않되 하늘은 있는 그대로 적는다")
    void stillReportsTheSkyOnADryDay() {
        stub("""
                {"hourly":{"time":["2026-08-22T00:00","2026-08-22T01:00","2026-08-22T02:00"],
                 "precipitation_probability":[10,20,15],
                 "precipitation":[0.00,0.00,0.00],
                 "weather_code":[1,2,2]}}""");

        assertThat(halves().get(DAY)).singleElement().satisfies(morning -> {
            assertThat(morning.wet()).as("10~20%를 비라고 적지 않는다").isFalse();
            assertThat(morning.kind()).as("코드 2가 가장 흔하다")
                    .isEqualTo(SkyCondition.PARTLY_CLOUDY);
        });
    }

    /**
     * 2026-08-22 미금역 실측 — {@code /v1/forecast?latitude=37.35&longitude=127.10889&
     * start_date=2026-08-22&end_date=2026-08-22&hourly=…&timezone=auto}의 {@code hourly} 블록 전부.
     */
    private static String migeum() {
        return """
                {"latitude":37.35,"longitude":127.125,"utc_offset_seconds":32400,
                 "timezone":"Asia/Seoul","elevation":65.0,
                 "hourly":{
                  "time":["2026-08-22T00:00","2026-08-22T01:00","2026-08-22T02:00","2026-08-22T03:00",
                          "2026-08-22T04:00","2026-08-22T05:00","2026-08-22T06:00","2026-08-22T07:00",
                          "2026-08-22T08:00","2026-08-22T09:00","2026-08-22T10:00","2026-08-22T11:00",
                          "2026-08-22T12:00","2026-08-22T13:00","2026-08-22T14:00","2026-08-22T15:00",
                          "2026-08-22T16:00","2026-08-22T17:00","2026-08-22T18:00","2026-08-22T19:00",
                          "2026-08-22T20:00","2026-08-22T21:00","2026-08-22T22:00","2026-08-22T23:00"],
                  "precipitation_probability":[92,85,77,73,78,87,92,86,76,69,69,71,
                                               75,80,87,90,87,81,75,72,69,67,68,69],
                  "precipitation":[1.60,0.80,0.60,5.20,7.10,15.80,11.70,8.50,7.70,3.20,0.30,0.30,
                                   0.00,0.20,0.30,1.10,0.10,0.70,0.10,0.50,0.00,0.00,0.00,0.60],
                  "weather_code":[61,53,53,63,63,65,65,65,65,63,51,51,
                                  2,51,51,55,51,53,51,53,1,1,1,53]}}""";
    }
}
