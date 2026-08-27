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
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 AccuWeather의 성질을 고정한다(2026-08-18, 미금역).
 *
 * <p>본문 스텁은 그날 실제로 받은 응답을 줄인 것이다 — 필드 이름과 모양이 그대로다.
 *
 * <p>가장 중요한 건 <b>키가 쿼리에 실린다</b>는 점이다. 예외 메시지로 새면 로그·모니터링을
 * 타고 그대로 유출된다({@code FmpApi}·{@code KeximFxClient}와 같은 규칙).
 */
class AccuWeatherClientTest {

    private static final String API_KEY = "secret-key-1234";
    private static final String LOCATION_PATH = "/locations/v1/cities/geoposition/search";
    private static final String FORECAST_PATH = "/forecasts/v1/daily/5day/2331758";

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 18);
    private static final GeoLocation MIGEUM =
            new GeoLocation("미금역", null, 37.35, 127.10889, ZoneId.of("Asia/Seoul"));

    /** 클래스당 하나다 — 테스트마다 띄우고 내리면 포트 재활용 창이 열린다(ARCHITECTURE.md §6). */
    private static WireMockServer server;
    private AccuWeatherClient client;

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
        client = new AccuWeatherClient(RestClient.builder(), server.baseUrl(), API_KEY,
                new AccuLocationApi(RestClient.builder(), server.baseUrl(), API_KEY));
    }

    private void stubLocation() {
        stub(LOCATION_PATH, """
                {"Key":"2331758","LocalizedName":"구미1동","EnglishName":"Gumi 1(il)-dong"}
                """);
    }

    /** 2026-08-18 실측 응답을 줄인 것. 닷새치가 온다. */
    private void stubForecast() {
        stub(FORECAST_PATH, """
                {"Headline":{},"DailyForecasts":[
                  {"Date":"2026-08-18T07:00:00+09:00",
                   "Temperature":{"Minimum":{"Value":22.0,"Unit":"C"},"Maximum":{"Value":31.0,"Unit":"C"}},
                   "Day":{"Icon":4,"IconPhrase":"Intermittent clouds","PrecipitationProbability":55},
                   "Night":{"Icon":36,"PrecipitationProbability":8}},
                  {"Date":"2026-08-19T07:00:00+09:00",
                   "Temperature":{"Minimum":{"Value":23.8,"Unit":"C"},"Maximum":{"Value":31.0,"Unit":"C"}},
                   "Day":{"Icon":4,"IconPhrase":"Intermittent clouds","PrecipitationProbability":25},
                   "Night":{"Icon":36,"PrecipitationProbability":9}},
                  {"Date":"2026-08-20T07:00:00+09:00",
                   "Temperature":{"Minimum":{"Value":24.3,"Unit":"C"},"Maximum":{"Value":29.0,"Unit":"C"}},
                   "Day":{"Icon":12,"IconPhrase":"Showers","PrecipitationProbability":70},
                   "Night":{"Icon":12,"PrecipitationProbability":60}}
                ]}
                """);
    }

    private void stub(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static WeatherPeriod days(int length) {
        return WeatherPeriod.of(TODAY, null, null, length);
    }

    @Test
    @DisplayName("닷새 응답에서 요청한 하루만 남긴다 — 알람은 오늘치만 묻는다")
    void slicesTheFiveDayResponseToTheRequestedRange() {
        stubLocation();
        stubForecast();

        Weather weather = client.forecast(MIGEUM, days(1));

        assertThat(weather.source()).isEqualTo(WeatherSource.ACCU_WEATHER);
        assertThat(weather.days()).hasSize(1);
        Weather.Daily today = weather.days().get(0);
        assertThat(today.date()).isEqualTo(TODAY);
        assertThat(today.low()).isEqualByComparingTo("22.0");
        assertThat(today.high()).isEqualByComparingTo("31.0");
    }

    @Test
    @DisplayName("확률과 섭씨를 요구해 보낸다 — 둘 다 파라미터가 없으면 안 오거나 화씨다")
    void asksForDetailsAndMetric() {
        stubLocation();
        stubForecast();

        client.forecast(MIGEUM, days(1));

        server.verify(getRequestedFor(urlPathEqualTo(FORECAST_PATH))
                .withQueryParam("details", WireMock.equalTo("true"))
                .withQueryParam("metric", WireMock.equalTo("true")));
    }

    @Test
    @DisplayName("밤이 아니라 낮을 읽는다 — 아침에 하루를 계획하는 값이 밤일 리 없다")
    void readsTheDaytimeHalf() {
        stubLocation();
        stubForecast();

        Weather.Daily today = client.forecast(MIGEUM, days(1)).days().get(0);

        // 같은 날 Night는 아이콘 36·확률 8이다. 그걸 읽었다면 값이 다르게 나온다
        assertThat(today.sky()).isEqualTo(SkyCondition.PARTLY_CLOUDY);
        assertThat(today.precipitationChance()).isEqualTo(55);
        assertThat(today.precipitationAmount())
                .as("확률을 아는 출처는 강수량 칸을 비운다 — 한 줄에 둘을 적지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("여러 날을 물으면 그만큼 담긴다 — 날짜는 지점 현지 오프셋으로 자른다")
    void keepsEveryDayInsideTheRange() {
        stubLocation();
        stubForecast();

        List<Weather.Daily> days = client.forecast(MIGEUM, days(3)).days();

        assertThat(days).hasSize(3);
        assertThat(days.get(2).date()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(days.get(2).sky()).isEqualTo(SkyCondition.SHOWERS);
    }

    @Test
    @DisplayName("닷새를 넘는 기간은 맡지 않는다 — 무료 등급이 거기까지다")
    void declinesRangesBeyondFiveDays() {
        assertThat(client.supports(MIGEUM, days(5), TODAY))
                .as("오늘 포함 닷새까지는 맡는다").isTrue();
        assertThat(client.supports(MIGEUM, days(6), TODAY))
                .as("엿새째부터는 뒷부분이 통째로 비는데 그건 실패로도 안 잡힌다").isFalse();
        assertThat(client.supports(MIGEUM,
                WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1), TODAY))
                .as("지나간 날도 못 준다").isFalse();
        assertThat(client.supports(
                new GeoLocation("파리", "프랑스", 48.8566, 2.3522, ZoneId.of("Europe/Paris")),
                days(3), TODAY))
                .as("지점은 안 가린다 — 전 세계를 맡는다. 국내만 맡는 것은 기상청이다").isTrue();
    }

    @Test
    @DisplayName("요청한 날이 닷새 밖이면 던진다 — 빈 답을 돌려주면 폴백이 안 일어난다")
    void throwsWhenTheRequestedDayIsNotInTheResponse() {
        stubLocation();
        stubForecast();

        assertThatThrownBy(() -> client.forecast(MIGEUM,
                WeatherPeriod.of(TODAY, LocalDate.of(2026, 8, 25), null, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("요청한 날짜");
    }

    @Test
    @DisplayName("예보가 비면 던진다 — 조용히 빈손을 내보내지 않는다")
    void throwsOnEmptyForecast() {
        stubLocation();
        stub(FORECAST_PATH, "{\"DailyForecasts\":[]}");

        assertThatThrownBy(() -> client.forecast(MIGEUM, days(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기온이 없는 날은 값이 아니다 — 담아 두면 '-°C / -°C'가 성공으로 나가 폴백도 안 돈다")
    void throwsWhenTheOnlyDayHasNoTemperature() {
        stubLocation();
        // Temperature 블록이 아예 없는 응답. 예전에는 low/high가 null인 Daily가 그대로 담겨
        // 화면에 '-°C / -°C'가 찍혔고, 성공이니 Open-Meteo로 넘어가지도 않았다
        stub(FORECAST_PATH, """
                {"DailyForecasts":[
                  {"Date":"2026-08-18T07:00:00+09:00",
                   "Day":{"Icon":6,"PrecipitationProbability":49}}
                ]}""");

        assertThatThrownBy(() -> client.forecast(MIGEUM, days(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("예외 메시지에 API 키가 새지 않는다 — 이 API는 키를 쿼리에 싣는다")
    void neverLeaksApiKey() {
        stubLocation();
        server.stubFor(get(urlPathEqualTo(FORECAST_PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.forecast(MIGEUM, days(1)))
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining("apikey");
    }

    @Test
    @DisplayName("503은 한도 소진으로 구분한다 — 키가 틀린 것과 원인이 다르다")
    void distinguishesQuotaExhaustion() {
        stubLocation();
        server.stubFor(get(urlPathEqualTo(FORECAST_PATH)).willReturn(aResponse().withStatus(503)
                .withBody("The allowed number of requests has been exceeded.")));

        assertThatThrownBy(() -> client.forecast(MIGEUM, days(1)))
                .hasMessageContaining("한도")
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    @DisplayName("키가 없으면 부르지 않는다 — 빈 키로 호출하면 한도만 축낸다")
    void skipsCallWithoutApiKey() {
        AccuWeatherClient keyless = new AccuWeatherClient(RestClient.builder(), server.baseUrl(), "",
                new AccuLocationApi(RestClient.builder(), server.baseUrl(), ""));

        assertThatThrownBy(() -> keyless.forecast(MIGEUM, days(1))).hasMessageContaining("키");
        server.verify(0, getRequestedFor(urlPathEqualTo(LOCATION_PATH)));
        server.verify(0, getRequestedFor(urlPathEqualTo(FORECAST_PATH)));
    }
}
