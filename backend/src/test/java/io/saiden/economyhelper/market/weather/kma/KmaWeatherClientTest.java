package io.saiden.economyhelper.market.weather.kma;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import io.saiden.economyhelper.support.TestFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 기상청의 성질을 고정한다(2026-08-26, {@code nx=62 ny=123}).
 *
 * <p>단기예보 본문은 <b>그날 받은 907행 그대로</b>다
 * ({@code fixtures/kma-vilage-20260826-0500.json}) — 줄이지 않은 이유는 우리가 안 읽는 항목
 * 일곱 개({@code UUU}·{@code VVV}·{@code VEC}·{@code WSD}·{@code WAV}·{@code REH}·{@code SNO})가
 * 섞여 있어도 멀쩡한지가 이 테스트가 볼 것 중 하나이기 때문이다. 중기예보는 짧아 인라인이다.
 *
 * <p>가장 중요한 건 <b>서비스키가 쿼리에 실린다</b>는 점이다. 예외 메시지로 새면 로그를 타고
 * 그대로 유출된다({@code AccuWeatherClient}·{@code KeximFxClient}와 같은 규칙).
 */
class KmaWeatherClientTest extends WireMockTest {

    /** 발급 키는 이미 URL 인코딩된 형태다 — {@code %}가 들어 있는 것이 요점이다. */
    private static final String API_KEY = "raw%2Bkey%2Fwith%3Dpercent";
    /** 그것을 한 번만 실었을 때 상대가 디코딩해서 보는 값. */
    private static final String DECODED_KEY = "raw+key/with=percent";
    private static final String VILLAGE_PATH =
            "/1360000/VilageFcstInfoService_2.0/getVilageFcst";

    private static final ZoneId SEOUL = KmaFixtures.SEOUL;
    /**
      * 2026-08-26 <b>06:30 KST</b> — 픽스처가 05시 발표라 그것이 가장 최근인 시각으로 맞췄다.
      * 오전 6시 알람이 도는 그 시각이기도 하다.
      */
    private static final Instant NOW = Instant.parse("2026-08-25T21:30:00Z");
    /** 2026-08-26 <b>14:30 KST</b> — 최신 발표가 14시이고 오늘 오전 슬롯이 0개인 시각. */
    private static final Instant AFTERNOON = Instant.parse("2026-08-26T05:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    private static final GeoLocation SEOHYEON = KmaFixtures.seohyeon();

    private KmaWeatherClient client;

    @BeforeEach
    void resetAndBuild() {
        client = clientWith(API_KEY);
    }

    // --- 맡는 범위 ---------------------------------------------------------

    @Test
    @DisplayName("국내 좌표의 나흘까지 맡는다 — 그 밖은 맡지 않는다")
    void takesDomesticRangesWithinFourDays() {
        assertThat(client.supports(SEOHYEON, days(4), TODAY))
                .as("단기예보가 온전히 덮는 범위다").isTrue();
        assertThat(client.supports(SEOHYEON, days(5), TODAY))
                .as("닷새째는 단기예보에 오후가 없다 — 중기예보는 쓰지 않는다").isFalse();
        assertThat(client.supports(SEOHYEON,
                WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1), TODAY))
                .as("지나간 날은 못 준다").isFalse();
    }

    @Test
    @DisplayName("⚠️ 과거를 걸친 범위는 안 맡는다 — period.past()는 끝만 본다")
    void refusesRangesThatReachIntoThePast() {
        // 단기예보에 지난 날짜가 없다. 시작일을 안 보면 「어제~오늘」이 통과해 오늘 하나만
        // 돌려주고, 물어본 어제가 조용히 사라진다 — 그것을 줄 수 있는 아카이브로도 안 넘어간다
        assertThat(client.supports(SEOHYEON,
                WeatherPeriod.of(TODAY, TODAY.minusDays(1), null, 2), TODAY))
                .as("어제~오늘").isFalse();
        assertThat(client.supports(SEOHYEON,
                WeatherPeriod.of(TODAY, TODAY.minusDays(1), null, 3), TODAY))
                .as("어제~내일").isFalse();
    }

    @Test
    @DisplayName("⚠️ 물어본 날이 하나라도 비면 던진다 — 반쪽을 성공으로 주면 폴백이 안 일어난다")
    void failsOverWhenARequestedDayIsMissing() {
        // VillageBlock이 일부러 버리는 날이 있다(반나절 하나뿐·기온 없음). 그 판단은 맞지만
        // **남은 것으로 답을 확정할지**는 클라이언트가 정한다 — 이 좁은 나흘은 폴백 둘이
        // 온전히 덮으므로 반쪽을 돌려주는 것은 언제나 손해다
        stub(VILLAGE_PATH, """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
                 "body":{"dataType":"JSON","items":{"item":[
                  {"category":"TMP","fcstDate":"20260826","fcstTime":"0700","fcstValue":"25"},
                  {"category":"POP","fcstDate":"20260826","fcstTime":"0700","fcstValue":"20"},
                  {"category":"SKY","fcstDate":"20260826","fcstTime":"0700","fcstValue":"1"},
                  {"category":"PTY","fcstDate":"20260826","fcstTime":"0700","fcstValue":"0"},
                  {"category":"TMP","fcstDate":"20260826","fcstTime":"1500","fcstValue":"31"},
                  {"category":"POP","fcstDate":"20260826","fcstTime":"1500","fcstValue":"20"},
                  {"category":"SKY","fcstDate":"20260826","fcstTime":"1500","fcstValue":"1"},
                  {"category":"PTY","fcstDate":"20260826","fcstTime":"1500","fcstValue":"0"}
                 ]},"pageNo":1,"numOfRows":1000,"totalCount":8}}}""");

        assertThatThrownBy(() -> client.forecast(SEOHYEON, days(4)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("모자랍니다");
    }

    @Test
    @DisplayName("⚠️ 격자 안이어도 시간대가 다르면 안 맡는다 — 후쿠오카·평양이 격자 안이다")
    void refusesPlacesOutsideKoreaEvenInsideTheGrid() {
        assertThat(client.supports(
                KmaFixtures.abroad("후쿠오카", KmaFixtures.FUKUOKA_LATITUDE,
                        KmaFixtures.FUKUOKA_LONGITUDE, "Asia/Tokyo"),
                days(1), TODAY)).isFalse();
        assertThat(client.supports(
                KmaFixtures.abroad("평양", KmaFixtures.PYONGYANG_LATITUDE,
                        KmaFixtures.PYONGYANG_LONGITUDE, "Asia/Pyongyang"),
                days(1), TODAY)).isFalse();
        assertThat(client.supports(
                KmaFixtures.abroad("파리", 48.8566, 2.3522, "Europe/Paris"),
                days(1), TODAY)).isFalse();
        assertThat(server.getAllServeEvents())
                .as("맡지 않는 조회는 호출을 태우지 않는다 — supports가 있는 이유다").isEmpty();
    }

    @Test
    @DisplayName("먼 섬은 맡는다 — 울릉도·독도·마라도도 국내다")
    void takesRemoteDomesticIslands() {
        assertThat(client.supports(
                KmaFixtures.domestic("울릉도", KmaFixtures.ULLEUNG_LATITUDE,
                        KmaFixtures.ULLEUNG_LONGITUDE), days(1), TODAY)).isTrue();
        assertThat(client.supports(
                KmaFixtures.domestic("마라도", KmaFixtures.MARA_LATITUDE,
                        KmaFixtures.MARA_LONGITUDE), days(1), TODAY)).isTrue();
    }

    @Test
    @DisplayName("키가 없으면 아예 안 맡는다 — AccuWeather처럼 헛호출을 태우지 않는다")
    void staysOutOfTheWayWithoutAKey() {
        assertThat(clientWith("  ").supports(SEOHYEON, days(1), TODAY)).isFalse();
    }

    @Test
    @DisplayName("시간별을 함께 준다 — Open-Meteo 보충 호출이 아예 안 나간다")
    void carriesItsOwnPrecipitationHours() {
        assertThat(client.providesPrecipitationHours()).isTrue();
    }

    // --- 단기예보 ---------------------------------------------------------

    @Test
    @DisplayName("실측 하루가 오전·오후 두 줄이 된다 — 안 읽는 항목 일곱 개가 섞여 있어도")
    void foldsTheMeasuredDayIntoTwoHalves() {
        stubVillage();

        Weather weather = client.forecast(SEOHYEON, days(2));

        assertThat(weather.source()).isEqualTo(WeatherSource.KMA);
        assertThat(weather.precipitationSource())
                .as("일별과 시간별이 한 출처에서 왔으므로 강수 출처를 따로 적을 것이 없다")
                .isNull();
        Weather.Daily today = weather.days().get(0);
        assertThat(today.date()).isEqualTo(TODAY);
        // ⚠️ 오늘은 TMN이 없다 — 새벽이 지나면 어느 발표에도 안 온다(실측: 05시 발표에
        //    8/27~29의 TMN은 있고 8/26만 없다). 그래서 **최저·최고를 둘 다 시간별로** 낸다 —
        //    섞으면 「이미 지난 낮 최고 / 남은 밤 최저」로 한 줄에 두 시간축이 선다.
        //    이 픽스처에서는 시간별 최고(32.0)가 TMX와 같아 값이 겹친다
        assertThat(today.low())
                .as("남은 18시각의 최저").isEqualByComparingTo("24.0");
        assertThat(today.high())
                .as("남은 18시각의 최고").isEqualByComparingTo("32.0");
        assertThat(weather.days())
                .filteredOn(day -> day.date().equals(TODAY.plusDays(1)))
                .singleElement()
                .satisfies(tomorrow -> assertThat(tomorrow.low())
                        .as("내일은 TMN이 와서 그것을 쓴다 — 온종일 값이다")
                        .isEqualByComparingTo("24.0"));
        assertThat(today.halves()).satisfiesExactly(
                morning -> {
                    assertThat(morning.half()).isEqualTo(HalfDay.Half.MORNING);
                    assertThat(morning.kind()).isEqualTo(SkyCondition.CLOUDY);
                    assertThat(morning.wet()).as("POP 30은 문턱(50) 아래라 마른 반나절이다").isFalse();
                    assertThat(morning.chance())
                            .as("마른 반나절도 제 봉우리를 든다 — 하루 요약이 그것을 쓴다")
                            .isEqualTo(30);
                },
                afternoon -> {
                    assertThat(afternoon.half()).isEqualTo(HalfDay.Half.AFTERNOON);
                    assertThat(afternoon.kind()).isEqualTo(SkyCondition.CLOUDY);
                    assertThat(afternoon.wet()).isFalse();
                });
        assertThat(today.sky()).isEqualTo(SkyCondition.CLOUDY);
        assertThat(today.precipitationChance())
                .as("반나절 봉우리로 다시 센 값이다").isEqualTo(30);
    }

    @Test
    @DisplayName("젖은 토막이 시각과 확률을 그대로 든다 — 실측 8/29 오후 12시~21시 60%")
    void pinsTheMeasuredWetStretch() {
        // ⚠️ 122KB 실측 픽스처를 두고 「kind가 null이 아니다」만 보던 자리다 — KmaSky는
        //    최악에도 UNKNOWN을 주므로 그 단언은 **언제나 참**이었다(ARCHITECTURE §6 규칙 4).
        //    +3일은 3시간 간격 여덟 칸으로 오는데 그 간격에서도 토막이 이어지는지가 요점이다
        stubVillage();

        Weather.Daily wet = client.forecast(SEOHYEON, days(4)).days().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 8, 29)))
                .findFirst().orElseThrow();

        assertThat(wet.sky())
                .as("요약이 반나절과 어긋나면 반나절 쪽으로 맞춘다")
                .isEqualTo(SkyCondition.RAIN);
        assertThat(wet.low()).isEqualByComparingTo("24.0");
        assertThat(wet.high()).isEqualByComparingTo("29.0");
        assertThat(wet.precipitationChance()).isEqualTo(60);
        assertThat(wet.halves()).satisfiesExactly(
                morning -> {
                    assertThat(morning.kind()).isEqualTo(SkyCondition.CLOUDY);
                    assertThat(morning.wet()).isFalse();
                },
                afternoon -> {
                    assertThat(afternoon.kind()).isEqualTo(SkyCondition.RAIN);
                    assertThat(afternoon.from()).isEqualTo(LocalTime.of(12, 0));
                    assertThat(afternoon.to()).isEqualTo(LocalTime.of(21, 0));
                    assertThat(afternoon.chance()).isEqualTo(60);
                });
    }

    @Test
    @DisplayName("⚠️ 오후에 물어도 오전·오후 두 줄이 나온다 — 이른 발표에서 오전을 메운다")
    void keepsBothHalvesInTheAfternoon() {
        // 신고된 버그다. 기상청은 행을 **발표시각 + 1시간부터만** 주므로 11시 발표부터는
        // 오늘 오전 슬롯이 0개가 되고 오후 한 줄만 나갔다(실측 증상 창 11:10~23:09 KST).
        // 실측 14시 발표: 오늘 15~23시 9칸 · 오전 0칸 · TMN·TMX 둘 다 없음.
        // 실측 02시 발표: 오늘 03~23시 21칸 · 오전 9칸(03~11시) · TMN 24.0 · TMX 32.0.
        // ⚠️ 이 테스트가 없던 이유는 KMA 테스트가 전부 06:30 시계였기 때문이다 — 그 시각엔
        //    05시 발표라 오전이 있어서 버그가 보이지 않았다
        KmaWeatherClient afternoon = clientAt(AFTERNOON);
        stubAt("1400", "fixtures/kma-vilage-20260826-1400.json");
        stubAt("0200", "fixtures/kma-vilage-20260826-0200.json");

        Weather.Daily today = afternoon.forecast(SEOHYEON, days(1)).days().get(0);

        assertThat(today.halves())
                .as("반나절이 하나면 읽는 사람이 나머지 반나절을 짐작하게 된다")
                .satisfiesExactly(
                        morning -> assertThat(morning.half()).isEqualTo(HalfDay.Half.MORNING),
                        afternoonHalf ->
                                assertThat(afternoonHalf.half()).isEqualTo(HalfDay.Half.AFTERNOON));
        assertThat(today.low())
                .as("02시 발표의 TMN — 14시 발표에는 없다").isEqualByComparingTo("24.0");
        assertThat(today.high())
                .as("02시 발표의 TMX — 14시 발표에는 없다").isEqualByComparingTo("32.0");
    }

    @Test
    @DisplayName("⚠️ 이른 발표가 죽으면 오늘을 버리고 폴백한다 — 반쪽을 내보내지 않는다")
    void failsOverWhenTheMorningCannotBeFilled() {
        KmaWeatherClient afternoon = clientAt(AFTERNOON);
        stubAt("1400", "fixtures/kma-vilage-20260826-1400.json");
        server.stubFor(get(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("base_time", WireMock.equalTo("0200"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> afternoon.forecast(SEOHYEON, days(1)))
                .as("던져야 AccuWeather·Open-Meteo가 두 줄을 온전히 준다")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("오늘이 온전한 발표에는 이른 발표를 안 부른다 — 헛호출이 된다")
    void neverAsksTheEarlierPublicationWhenTodayIsComplete() {
        // 02시 판이 최신인 시각(02:40)에는 그 판이 오전(03~11시)과 TMN·TMX를 다 들고 있다.
        // ⚠️ 05시 판으로는 이 단언이 못 선다 — 오전은 있는데 **TMN이 없어서** 보충이 여전히
        //    필요하다. 처음에 그렇게 적었다가 틀렸다: 두 조건은 함께 사라지지 않는다
        stubAt("0200", "fixtures/kma-vilage-20260826-0200.json");

        Weather.Daily today = clientAt(Instant.parse("2026-08-25T17:40:00Z"))
                .forecast(SEOHYEON, days(1)).days().get(0);

        assertThat(today.halves()).hasSize(2);
        assertThat(server.getAllServeEvents())
                .as("한 판이 오전과 일 극값을 다 들고 있으면 추가 호출이 0이다").hasSize(1);
    }

    @Test
    @DisplayName("⚠️ 오늘의 일 최저·최고는 02시 발표에서 받아 온다 — 남은 시간으로 근사하지 않는다")
    void takesTodaysDailyExtremesFromTheEarliestPublication() {
        // 실측(2026-08-26): 오늘 것이 이른 발표에만 남는다 — 02시에는 TMN 24.0·TMX 32.0이
        // 있고 05·11시에는 TMN이, 14시에는 둘 다 없다. 그 02시 판은 오후에도 조회된다.
        // 안 받아 오면 최고는 온종일의 TMX, 최저는 남은 시간의 최저가 되어 한 줄에 두 시간축이 선다
        server.stubFor(get(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("base_time", WireMock.equalTo("0500"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TestFixtures.text("fixtures/kma-vilage-20260826-0500.json"))));
        server.stubFor(get(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("base_time", WireMock.equalTo("0200"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
                                 "body":{"dataType":"JSON","items":{"item":[
                                  {"category":"TMN","fcstDate":"20260826","fcstTime":"0600","fcstValue":"19.5"},
                                  {"category":"TMX","fcstDate":"20260826","fcstTime":"1500","fcstValue":"33.5"}
                                 ]},"pageNo":1,"numOfRows":1000,"totalCount":2}}}""")));

        Weather.Daily today = client.forecast(SEOHYEON, days(1)).days().get(0);

        assertThat(today.low())
                .as("최신 발표에 없어서 02시에서 채운다 — 남은 시간의 최저(24.0)가 아니다")
                .isEqualByComparingTo("19.5");
        assertThat(today.high())
                .as("⚠️ 빠진 것만 채운다 — 최고는 최신 발표(05시)의 32.0이 더 신선하다. "
                        + "둘 다 **일** 극값이라 발표가 갈려도 시간축은 안 섞인다")
                .isEqualByComparingTo("32.0");
    }

    @Test
    @DisplayName("보충이 죽으면 남은 시간으로 낸다 — 둘 다 시간별이라 앞뒤는 맞는다")
    void fallsBackToTheHourlyWindowWhenTheExtremesAreUnavailable() {
        stubVillage();
        server.stubFor(get(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("base_time", WireMock.equalTo("0200"))
                .willReturn(aResponse().withStatus(500)));

        Weather.Daily today = client.forecast(SEOHYEON, days(1)).days().get(0);

        assertThat(today.low()).isEqualByComparingTo("24.0");
        assertThat(today.high())
                .as("최고도 시간별에서 낸다 — 하나만 갈아 끼우면 두 시간축이 섞인다")
                .isEqualByComparingTo("32.0");
    }

    @Test
    @DisplayName("⚠️ 오늘을 안 묻는 조회에는 보충을 안 부른다 — 헛호출이 된다")
    void neverAsksForExtremesWhenTodayIsNotRequested() {
        stubVillage();

        client.forecast(SEOHYEON, WeatherPeriod.of(TODAY, TODAY.plusDays(1), null, 1));

        assertThat(server.getAllServeEvents())
                .as("최신 발표 하나뿐이어야 한다").hasSize(1);
    }

    @Test
    @DisplayName("가장 최근 발표를 묻는다 — 앞선 판은 이미 지났고 뒷선 판은 아직 안 실렸다")
    void asksForTheLatestPublication() {
        stubVillage();

        client.forecast(SEOHYEON, days(1));

        server.verify(getRequestedFor(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("base_date", WireMock.equalTo("20260826"))
                .withQueryParam("base_time", WireMock.equalTo("0500"))
                .withQueryParam("nx", WireMock.equalTo("62"))
                .withQueryParam("ny", WireMock.equalTo("123")));
    }

    @Test
    @DisplayName("⚠️ 서비스키를 다시 인코딩하지 않는다 — 하면 「등록되지 않은 서비스키」가 온다")
    void neverReEncodesTheServiceKey() {
        stubVillage();

        client.forecast(SEOHYEON, days(1));

        // 실측(2026-08-26): --data-urlencode로 보내면 SERVICE_KEY_IS_NOT_REGISTERED_ERROR가
        // 오는데 그것이 「활용신청이 안 됐다」로 읽혔다. 키를 그대로 실으니 같은 키로 통했다.
        //
        // WireMock은 쿼리 파라미터를 디코딩해 비교한다. 우리가 키를 그대로 보냈다면 디코딩
        // 결과가 원래 값이고, 한 번 더 인코딩했다면 디코딩해도 %2B가 남아 API_KEY 그대로
        // 보인다 — 그게 「등록되지 않은 서비스키」를 부르는 상태다.
        // (StockPriceApiTest·MarketIndexApiTest가 같은 규칙을 같은 모양으로 지킨다)
        server.verify(getRequestedFor(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("serviceKey", WireMock.equalTo(DECODED_KEY)));
        server.verify(0, getRequestedFor(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("serviceKey", WireMock.equalTo(API_KEY)));
    }

    @Test
    @DisplayName("⚠️ 반나절이 하나뿐인 날은 뺀다 — 실측 +4일이 00시 한 칸으로 온다")
    void dropsDaysWithOnlyOneHalf() {
        stubVillage();

        Weather weather = client.forecast(SEOHYEON, days(4));

        // ⚠️ 예전 단언은 공허했다 — 필터 결과가 빈 스트림이라 allSatisfy가 무조건 통과했고,
        //    「오늘은 반나절 하나여도 된다」를 허용하면서 한 번도 시험하지 않았다
        assertThat(weather.days())
                .as("오늘도 예외가 아니다 — 두 줄은 언제나 나온다")
                .allSatisfy(day -> assertThat(day.halves()).hasSize(2));
        assertThat(weather.days()).extracting(Weather.Daily::date)
                .as("+4일(8/30)은 00시 한 칸이라 목록에 없어야 한다")
                .doesNotContain(TODAY.plusDays(4))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("⚠️ 에러는 다른 봉투로 온다 — HTTP 200에 response가 통째로 없다")
    void treatsTheErrorEnvelopeAsAFailure() {
        // 실측: 서비스키가 틀리면 {"OpenAPI_ServiceResponse":{"cmmMsgHeader":…}}가 200으로 온다.
        // 이 검사가 없으면 그 자리에서 NullPointerException이 난다
        stub(VILLAGE_PATH, """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
                  "errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                  "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}""");

        assertThatThrownBy(() -> client.forecast(SEOHYEON, days(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("서비스키");
    }

    @Test
    @DisplayName("⚠️ 실패해도 서비스키가 예외 메시지에 없다 — 로그를 타고 유출된다")
    void neverLeaksTheServiceKey() {
        server.stubFor(get(urlPathEqualTo(VILLAGE_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.forecast(SEOHYEON, days(1)))
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining("serviceKey");
    }

    @Test
    @DisplayName("요청한 날이 하나도 없으면 던진다 — 빈 답을 돌려주면 폴백이 안 일어난다")
    void throwsWhenNoRequestedDayIsInTheResponse() {
        stubVillage();

        assertThatThrownBy(() -> client.forecast(SEOHYEON,
                WeatherPeriod.of(TODAY, LocalDate.of(2026, 9, 20), null, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- 도구 -------------------------------------------------------------

    private KmaWeatherClient clientWith(String apiKey) {
        return new KmaWeatherClient(new KmaVillageApi(RestClient.builder(), server.baseUrl(),
                apiKey, Clock.fixed(NOW, SEOUL)), apiKey);
    }

    /** 시계만 갈아 끼운 클라이언트 — 증상이 시각에 딸려 있다. */
    private KmaWeatherClient clientAt(java.time.Instant at) {
        return new KmaWeatherClient(new KmaVillageApi(RestClient.builder(), server.baseUrl(),
                API_KEY, Clock.fixed(at, SEOUL)), API_KEY);
    }

    private void stubAt(String baseTime, String fixture) {
        server.stubFor(get(urlPathEqualTo(VILLAGE_PATH))
                .withQueryParam("base_time", WireMock.equalTo(baseTime))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TestFixtures.text(fixture))));
    }

    private static WeatherPeriod days(int count) {
        return WeatherPeriod.of(TODAY, null, null, count);
    }

    /** 2026-08-26 05시 발표, 서현역 격자. <b>줄이지 않은 907행</b>이다. */
    private void stubVillage() {
        stub(VILLAGE_PATH, TestFixtures.text("fixtures/kma-vilage-20260826-0500.json"));
    }

    private void stub(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

}
