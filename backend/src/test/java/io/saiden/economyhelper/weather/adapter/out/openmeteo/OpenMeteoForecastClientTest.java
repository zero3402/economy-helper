package io.saiden.economyhelper.weather.adapter.out.openmeteo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.testsupport.WireMockTest;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.SkyCondition;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** {@code daily=}·{@code hourly=}에 무엇을 보내는지, 응답을 어떻게 접는지 본다. */
class OpenMeteoForecastClientTest extends WireMockTest {

    private static final GeoLocation SEONGNAM =
            new GeoLocation("성남시", "대한민국", 37.43861, 127.13778, ZoneId.of("Asia/Seoul"));
    private static final LocalDate DAY = LocalDate.of(2026, 8, 20);

    private OpenMeteoForecastClient client;

    @BeforeEach
    void resetAndBuild() {
        client = new OpenMeteoForecastClient(RestClient.builder(),
                TestProperties.builder().openMeteo(server.baseUrl()).build());
    }

    private void stub(String body) {
        server.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("일별과 시간별을 한 번에 묻는다 — timezone=auto가 둘 다에 걸려야 시각이 현지시다")
    void asksForDailyAndHourlyInOneCall() {
        stub(oneDay());

        client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY));

        server.verify(1, getRequestedFor(urlPathEqualTo("/v1/forecast"))
                .withQueryParam("daily", WireMock.containing("precipitation_probability_max"))
                .withQueryParam("hourly", WireMock.containing("precipitation_probability"))
                .withQueryParam("hourly", WireMock.containing("weather_code"))
                // 없으면 「오후 1시」가 UTC 오후가 된다
                .withQueryParam("timezone", WireMock.equalTo("auto")));
    }

    @Test
    @DisplayName("몰려 있는 강수 시간을 토막으로 접어 그날에 붙인다 — 「비옴」이 언제인지 말해 준다")
    void attachesTheHalfDayToItsDay() {
        // 2026-08-20 성남시 실측을 줄인 것이다. 일 단위는 최대 80%인데 시간별로는 몰려 있다
        stub(oneDay());

        Weather weather = client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY));

        assertThat(weather.days()).singleElement().satisfies(day -> {
            assertThat(day.precipitationChance()).as("일 단위 값은 그대로다").isEqualTo(80);
            assertThat(day.halves()).singleElement().satisfies(spell -> {
                assertThat(spell.from()).isEqualTo(LocalTime.of(14, 0));
                assertThat(spell.to()).as("47%·60%·20%는 봉우리(80%)의 가장자리다")
                        .isEqualTo(LocalTime.of(15, 0));
                assertThat(spell.kind()).isEqualTo(SkyCondition.DRIZZLE);
                assertThat(spell.chance()).isEqualTo(80);
            });
        });
    }

    @Test
    @DisplayName("시간별이 아예 없어도 하루는 그대로 나간다 — 강수 시각은 보충이라 답을 죽이지 않는다")
    void survivesAResponseWithoutHourly() {
        stub("""
                {"daily":{"time":["2026-08-20"],"weather_code":[55],
                 "temperature_2m_max":[25.9],"temperature_2m_min":[22.5],
                 "precipitation_probability_max":[80]}}""");

        Weather weather = client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY));

        assertThat(weather.days()).singleElement().satisfies(day -> {
            assertThat(day.precipitationChance()).isEqualTo(80);
            assertThat(day.halves()).as("빈 목록이라 화면에 그 줄이 없다").isEmpty();
        });
    }

    @Test
    @DisplayName("기온이 빈 날은 뺀다 — 담으면 「-°C」가 성공으로 찍힌다")
    void dropsADayWithoutTemperature() {
        stub("""
                {"daily":{"time":["2026-08-20","2026-08-21"],"weather_code":[3,3],
                 "temperature_2m_max":[25.9,null],"temperature_2m_min":[22.5],
                 "precipitation_probability_max":[10,10]}}""");

        Weather weather = client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY.plusDays(1)));

        assertThat(weather.days()).extracting(Weather.Daily::date).containsExactly(DAY);
    }

    @Test
    @DisplayName("기온이 있는 날이 하나도 없으면 던진다 — 다음 출처로 넘어가야 한다")
    void throwsWhenNoDayHasTemperature() {
        stub("""
                {"daily":{"time":["2026-08-20"],"weather_code":[3],
                 "precipitation_probability_max":[10]}}""");

        assertThatThrownBy(() -> client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기온이 있는 날이 없습니다");
    }

    @Test
    @DisplayName("일일 값이 없으면 던진다 — 빈 값을 돌려주면 폴백이 안 일어난다")
    void throwsWhenTheDailyBlockIsMissing() {
        stub("{}");

        assertThatThrownBy(() -> client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("일일 값이 없습니다");
    }

    @Test
    @DisplayName("마른 날도 반나절 줄은 붙는다 — 낮은 확률을 비라고 적지 않되 하늘은 남긴다")
    void stillReportsTheSkyOnADryDay() {
        stub("""
                {"daily":{"time":["2026-08-20"],"weather_code":[1],
                 "temperature_2m_max":[28.1],"temperature_2m_min":[23.0],
                 "precipitation_probability_max":[10]},
                 "hourly":{"time":["2026-08-20T12:00","2026-08-20T13:00","2026-08-20T14:00"],
                 "precipitation_probability":[8,10,6],"precipitation":[0.0,0.0,0.0],
                 "weather_code":[1,2,1]}}""");

        assertThat(client.forecast(SEONGNAM, new WeatherPeriod(DAY, DAY)).days())
                .singleElement()
                .satisfies(day -> assertThat(day.halves()).singleElement()
                        .satisfies(afternoon -> {
                            assertThat(afternoon.wet()).isFalse();
                            assertThat(afternoon.kind()).as("코드 1이 가장 흔하다")
                                    .isEqualTo(SkyCondition.MOSTLY_CLEAR);
                        }));
    }

    /** 실측 응답을 세 시간으로 줄인 것 — 13시 60%·14시 73%·15시 80%가 이어진다. */
    private static String oneDay() {
        return """
                {"daily":{"time":["2026-08-20"],"weather_code":[55],
                 "temperature_2m_max":[25.9],"temperature_2m_min":[22.5],
                 "precipitation_probability_max":[80]},
                 "hourly":{"time":["2026-08-20T12:00","2026-08-20T13:00","2026-08-20T14:00",
                 "2026-08-20T15:00","2026-08-20T16:00"],
                 "precipitation_probability":[47,60,73,80,20],
                 "precipitation":[0.0,0.4,1.1,0.4,0.0],
                 "weather_code":[3,51,55,51,2]}}""";
    }
}
