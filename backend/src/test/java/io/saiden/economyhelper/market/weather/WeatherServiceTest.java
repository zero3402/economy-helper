package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.time.LocalTime;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.support.TestWeather;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>이중화가 실제로 도는지</b>를 고정한다 — 하나가 죽으면 다른 쪽으로 넘어가야 한다.
 *
 * <p>{@code FxServiceTest}와 같은 방식이다: WireMock 없이 SPI를 구현한 가짜에 호출 수를 달아
 * "1순위가 성공하면 2순위를 부르지 않는다"까지 본다.
 *
 * <p>순서는 {@code AccuWeather → Open-Meteo → Open-Meteo Archive}다. 1순위가 유일하게 키를
 * 쓰고 무료 등급이 하루 50회·5일까지라, 받쳐 주는 쪽은 제약이 적은 Open-Meteo다.
 */
class WeatherServiceTest {

    /** KST 2026-08-17 09:00. */
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final GeoLocation SEOUL =
            new GeoLocation("성남시", "대한민국", 37.35, 127.10889, ZoneId.of("Asia/Seoul"));
    private static final GeoLocation PARIS =
            new GeoLocation("파리", "프랑스", 48.8566, 2.3522, ZoneId.of("Europe/Paris"));
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);

    @Test
    @DisplayName("국내는 기상청이 먼저다 — AccuWeather는 안 불린다")
    void putsKmaFirstForDomesticPlaces() {
        FakeClient kma = FakeClient.domesticOnly(WeatherSource.KMA);
        FakeClient accu = FakeClient.returning(WeatherSource.ACCU_WEATHER);

        Optional<Weather> weather = service(accu, kma).forecast(SEOUL, today());

        assertThat(weather.orElseThrow().source())
                .as("주입 순서가 아니라 ORDER가 순서를 정한다")
                .isEqualTo(WeatherSource.KMA);
        assertThat(accu.calls).hasValue(0);
    }

    @Test
    @DisplayName("⚠️ 국외는 기상청을 아예 안 부른다 — 부르면 애먼 실패가 브레이커에 쌓인다")
    void neverAsksKmaAboutPlacesAbroad() {
        FakeClient kma = FakeClient.domesticOnly(WeatherSource.KMA);
        FakeClient accu = FakeClient.returning(WeatherSource.ACCU_WEATHER);

        Optional<Weather> weather = service(kma, accu).forecast(PARIS, today());

        assertThat(weather.orElseThrow().source()).isEqualTo(WeatherSource.ACCU_WEATHER);
        assertThat(kma.calls)
                .as("supports가 좌표를 보는 이유가 이것이다 — 못 하는 줄 알면서 부르지 않는다")
                .hasValue(0);
    }

    @Test
    @DisplayName("기상청이 죽으면 AccuWeather로 내려앉고 출처도 그쪽으로 바뀐다")
    void fallsBackFromKma() {
        FakeClient kma = FakeClient.failing(WeatherSource.KMA);
        FakeClient accu = FakeClient.returning(WeatherSource.ACCU_WEATHER);

        Optional<Weather> weather = service(kma, accu).forecast(SEOUL, today());

        assertThat(weather.orElseThrow().source()).isEqualTo(WeatherSource.ACCU_WEATHER);
        assertThat(kma.calls).hasValue(1);
    }

    @Test
    @DisplayName("1순위가 성공하면 2순위를 부르지 않는다 — 폴백은 장애 때만이다")
    void doesNotTouchTheFallbackWhenThePrimaryWorks() {
        FakeClient primary = FakeClient.returning(WeatherSource.ACCU_WEATHER);
        FakeClient fallback = FakeClient.returning(WeatherSource.OPEN_METEO);

        Optional<Weather> weather = service(primary, fallback).forecast(SEOUL, today());

        assertThat(weather).isPresent();
        assertThat(weather.get().source()).isEqualTo(WeatherSource.ACCU_WEATHER);
        assertThat(fallback.calls).hasValue(0);
    }

    @Test
    @DisplayName("1순위가 죽으면 2순위로 넘어가고 출처도 그쪽으로 바뀐다 — 숨기면 거짓말이 된다")
    void fallsBackAndSaysSo() {
        FakeClient primary = FakeClient.failing(WeatherSource.ACCU_WEATHER);
        FakeClient fallback = FakeClient.returning(WeatherSource.OPEN_METEO);

        Optional<Weather> weather = service(primary, fallback).forecast(SEOUL, today());

        assertThat(weather).isPresent();
        assertThat(weather.get().source())
                .as("Open-Meteo가 답했는데 AccuWeather라고 적으면 거짓말이 된다")
                .isEqualTo(WeatherSource.OPEN_METEO);
        assertThat(primary.calls).hasValue(1);
        assertThat(fallback.calls).hasValue(1);
    }

    @Test
    @DisplayName("전부 죽으면 빈손이다 — 지어내지 않는다")
    void returnsEmptyWhenEverySourceFails() {
        WeatherService service = service(
                FakeClient.failing(WeatherSource.ACCU_WEATHER), FakeClient.failing(WeatherSource.OPEN_METEO));

        assertThat(service.forecast(SEOUL, today())).isEmpty();
    }

    @Test
    @DisplayName("주입 순서를 뒤집어도 코드가 정한 순서가 이긴다")
    void orderIsDeclaredNotInjected() {
        FakeClient primary = FakeClient.returning(WeatherSource.ACCU_WEATHER);
        FakeClient fallback = FakeClient.returning(WeatherSource.OPEN_METEO);

        // Open-Meteo를 먼저 주입해도 AccuWeather가 1순위여야 한다
        WeatherService service = new WeatherService(List.of(fallback, primary), fixedClock(), TestWeather.noHourly());

        assertThat(service.forecast(SEOUL, today()).orElseThrow().source())
                .isEqualTo(WeatherSource.ACCU_WEATHER);
        assertThat(fallback.calls).hasValue(0);
    }

    @Test
    @DisplayName("지난 날짜는 예보 출처를 아예 부르지 않는다 — 못 하는 줄 알면서 부르면 브레이커만 상한다")
    void skipsForecastSourcesForPastDates() {
        FakeClient forecast = FakeClient.returning(WeatherSource.ACCU_WEATHER);
        FakeClient archive = FakeClient.returning(WeatherSource.OPEN_METEO_ARCHIVE);
        WeatherPeriod past = WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1);

        Optional<Weather> weather = new WeatherService(List.of(forecast, archive), fixedClock(), TestWeather.noHourly())
                .forecast(SEOUL, past);

        assertThat(weather.orElseThrow().source()).isEqualTo(WeatherSource.OPEN_METEO_ARCHIVE);
        assertThat(forecast.calls).as("예보 출처는 과거를 맡지 않는다").hasValue(0);
    }

    @Test
    @DisplayName("1순위가 못 맡는 기간은 부르지도 않고 2순위가 답한다 — 닷새 넘는 요청이 그렇다")
    void skipsThePrimaryWhenItCannotCoverTheRange() {
        // AccuWeather 무료는 5일까지다. 일주일치를 물으면 supports에서 빠지고 Open-Meteo가 받는다
        FakeClient primary = FakeClient.decliningLongRanges(WeatherSource.ACCU_WEATHER);
        FakeClient fallback = FakeClient.returning(WeatherSource.OPEN_METEO);
        WeatherPeriod week = WeatherPeriod.of(TODAY, null, null, 7);

        Optional<Weather> weather = service(primary, fallback).forecast(SEOUL, week);

        assertThat(weather.orElseThrow().source()).isEqualTo(WeatherSource.OPEN_METEO);
        assertThat(primary.calls)
                .as("못 하는 줄 알면서 부르면 브레이커도 상하고 하루 50회 한도만 축낸다")
                .hasValue(0);
    }

    @Test
    @DisplayName("맡을 출처가 하나도 없으면 빈손이다 — 실패와 구분해 남긴다")
    void returnsEmptyWhenNoSourceCoversTheRange() {
        // 예보 출처만 있는데 지난 날짜를 물었다
        WeatherService service = service(FakeClient.returning(WeatherSource.ACCU_WEATHER));
        WeatherPeriod past = WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1);

        assertThat(service.forecast(SEOUL, past)).isEmpty();
    }

    @Test
    @DisplayName("오늘은 그 지역의 오늘이다 — 우리 달력으로 자르면 남의 하루가 쪼개진다")
    void todayFollowsTheLocationsOwnZone() {
        WeatherService service = service(FakeClient.returning(WeatherSource.ACCU_WEATHER));
        GeoLocation buenosAires = new GeoLocation("부에노스아이레스", "아르헨티나",
                -34.61315, -58.37723, ZoneId.of("America/Argentina/Buenos_Aires"));

        // KST 2026-08-17 09:00은 부에노스아이레스에서 아직 08-16 21:00이다
        assertThat(service.today(SEOUL)).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(service.today(buenosAires)).isEqualTo(LocalDate.of(2026, 8, 16));
    }

    private static WeatherPeriod today() {
        return WeatherPeriod.of(TODAY, null, null, null);
    }

    private static WeatherService service(WeatherClient... clients) {
        return new WeatherService(List.of(clients), fixedClock(), TestWeather.noHourly());
    }

    @Test
    @DisplayName("1순위가 시간 단위를 못 주면 강수 시각만 보충한다 — AccuWeather는 낮/밤뿐이다")
    void fillsInThePrecipitationHoursWhenThePrimaryCannot() {
        // AccuWeather가 일별을 맡으면 시각이 없다. 그것만 Open-Meteo에 따로 묻는다 —
        // 키도 한도도 없어 공짜이고, 12시간별 엔드포인트는 기간을 못 덮는다
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        HalfDay spell = HalfDay.withChance(
                LocalTime.of(13, 0), LocalTime.of(19, 0), SkyCondition.RAIN, 80);
        WeatherService service = new WeatherService(List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.hourly(Map.of(day, List.of(spell))));

        Optional<Weather> weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null));

        assertThat(weather).get().extracting(Weather::days).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(Weather.Daily.class))
                .singleElement()
                .satisfies(each -> assertThat(each.halves()).containsExactly(spell));
    }

    /**
     * ⚠️ <b>이것이 「강수확률은 높은데 오전/오후 줄이 없다」의 정체다.</b>
     *
     * <p>확률은 AccuWeather의 낮 값이고 시각은 Open-Meteo 시간별이라 <b>두 숫자가 서로 다른
     * 예보</b>였다. AccuWeather가 80%라고 하는 날 Open-Meteo 봉우리가 40%면 문턱을 넘는 시간이
     * 하나도 없어 <b>「강수확률 80%」만 찍히고 시각 줄은 통째로 사라진다.</b>
     * 지금은 시간별을 얹는 순간 확률도 그 시간별로 다시 세므로 그 어긋남이 성립할 수 없다.
     */
    @Test
    @DisplayName("보충은 시각만이 아니라 강수확률도 함께 간다 — 한 블록에 두 예보의 숫자가 서면 안 된다")
    void alsoRewritesTheChanceFromTheSuppliedHours() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        // FakeClient(AccuWeather)는 하루 확률을 20%로 준다. 시간별 봉우리는 80%다
        HalfDay spell = HalfDay.withChance(
                LocalTime.of(13, 0), LocalTime.of(19, 0), SkyCondition.RAIN, 80);
        WeatherService service = new WeatherService(
                List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.hourly(Map.of(day, List.of(spell))));

        Optional<Weather> weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null));

        assertThat(weather.orElseThrow().days()).singleElement().satisfies(each -> {
            assertThat(each.precipitationChance())
                    .as("화면의 확률과 시각이 같은 예보에서 나와야 한다")
                    .isEqualTo(80);
            assertThat(each.precipitationAmount()).as("강수량 칸은 건드리지 않는다").isNull();
        });
    }

    @Test
    @DisplayName("강수 줄이 다른 곳에서 왔으면 그 출처를 들고 간다 — 숨기면 화면이 거짓말을 한다")
    void carriesTheSupplementSourceSoTheScreenCanSayIt() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        HalfDay spell = HalfDay.withChance(
                LocalTime.of(13, 0), LocalTime.of(19, 0), SkyCondition.RAIN, 80);
        WeatherService service = new WeatherService(
                List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.hourly(Map.of(day, List.of(spell))));

        Weather weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null))
                .orElseThrow();

        assertThat(weather.source()).as("일별은 여전히 1순위 것이다")
                .isEqualTo(WeatherSource.ACCU_WEATHER);
        assertThat(weather.precipitationSource())
                .as("확률까지 이쪽 값이 되었으므로 출처 줄이 이 이름을 함께 적어야 한다")
                .isEqualTo(WeatherSource.OPEN_METEO);
    }

    @Test
    @DisplayName("시간별이 물어본 날짜와 하나도 안 겹치면 출처를 붙이지 않는다 — 강수 줄은 여전히 일별 것이다")
    void leavesTheSourceAloneWhenNoDayReceivedHours() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        HalfDay spell = HalfDay.withChance(
                LocalTime.of(13, 0), LocalTime.of(19, 0), SkyCondition.RAIN, 80);
        // 시각은 왔지만 **다음 날** 것이다 — 날짜 창이 어긋난 응답
        WeatherService service = new WeatherService(
                List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.hourly(Map.of(day.plusDays(1), List.of(spell))));

        Weather weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null))
                .orElseThrow();

        assertThat(weather.precipitationSource())
                .as("Open-Meteo가 화면의 강수 줄에 아무것도 넣지 않았으면 출처에도 없어야 한다")
                .isNull();
        assertThat(weather.days()).allSatisfy(each -> assertThat(each.halves()).isEmpty());
    }

    /**
     * <b>부르지 않는 것</b>을 단언한다 — 마른 날에는 보충을 부르든 안 부르든 화면이 같아서
     * 결과로는 드러나지 않는다. 그래서 세는 자리가 필요하다.
     *
     * <p>Open-Meteo 예보는 일별과 시간별을 한 응답으로 주므로 이미 손에 있다. 알람은 지역이
     * 넷이라 여기서 새면 마른 날마다 헛호출이 넷씩 는다.
     */
    @Test
    @DisplayName("일별 출처가 시간별을 함께 주면 보충을 부르지 않는다 — 마른 날이어도 두 번 묻지 않는다")
    void doesNotAskTwiceWhenTheDailySourceAlreadyCarriesHours() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        // FakeClient는 토막 없는 하루를 준다 — 마른 날이다. 출처는 시간별을 함께 주는 Open-Meteo다
        WeatherService service = new WeatherService(
                List.of(new FakeClient(WeatherSource.OPEN_METEO, false)),
                fixedClock(), TestWeather.countingHourly(calls));

        service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null));

        assertThat(calls).as("한 응답에 이미 들어 있던 것을 다시 묻지 않는다").hasValue(0);
    }

    @Test
    @DisplayName("일별 출처가 시간별을 못 주면 그때는 보충을 부른다 — AccuWeather가 낮/밤뿐이다")
    void stillAsksWhenTheDailySourceCannotGiveHours() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        WeatherService service = new WeatherService(
                List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.countingHourly(calls));

        service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null));

        assertThat(calls).as("이쪽은 물어야 시각이 생긴다").hasValue(1);
    }

    @Test
    @DisplayName("보충이 빈손이면 출처도 그대로다 — 부르기만 한 것을 「거기서 왔다」고 적지 않는다")
    void leavesTheSupplementSourceEmptyWhenNothingWasAdded() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        WeatherService service = new WeatherService(
                List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.hourly(Map.of()));

        Weather weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null))
                .orElseThrow();

        assertThat(weather.precipitationSource()).isNull();
    }

    @Test
    @DisplayName("보충이 터져도 일일 예보는 나간다 — 보충은 폴백이 아니라 덧붙임이다")
    void neverLosesTheForecastWhenTheSupplementFails() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneId.of("Asia/Seoul"));
        WeatherService service = new WeatherService(List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.explodingHourly());

        Optional<Weather> weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null));

        assertThat(weather).as("일별은 이미 손에 있다 — 여기서 죽으면 안 된다").isPresent();
        assertThat(weather.get().days()).singleElement()
                .satisfies(each -> assertThat(each.halves())
                        .as("줄만 빠진다").isEmpty());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** 성공/실패를 지정하는 스텁. 호출 수를 세어 "안 불렀다"까지 단언할 수 있게 한다. */
    private static final class FakeClient implements WeatherClient {

        private final WeatherSource source;
        private final boolean fails;
        private final AtomicInteger calls = new AtomicInteger();
        /** 국내 판정에 쓰는 시간대 — 술어 안에서 매번 만들지 않는다. */
        private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

        private int maxDays = Integer.MAX_VALUE;
        private boolean domesticOnly;

        private FakeClient(WeatherSource source, boolean fails) {
            this.source = source;
            this.fails = fails;
        }

        static FakeClient returning(WeatherSource source) {
            return new FakeClient(source, false);
        }

        static FakeClient failing(WeatherSource source) {
            return new FakeClient(source, true);
        }

        /** 국내만 맡는다 — 기상청의 성질을 흉내 낸다. 시간대로 가르는 것까지 같다. */
        static FakeClient domesticOnly(WeatherSource source) {
            FakeClient client = new FakeClient(source, false);
            client.domesticOnly = true;
            return client;
        }

        /** 닷새를 넘는 기간을 사양한다 — AccuWeather 무료 등급의 성질을 흉내 낸다. */
        static FakeClient decliningLongRanges(WeatherSource source) {
            FakeClient client = new FakeClient(source, false);
            client.maxDays = 5;
            return client;
        }

        @Override
        public WeatherSource source() {
            return source;
        }

        @Override
        public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
            if (source == WeatherSource.OPEN_METEO_ARCHIVE) {
                return period.past(today);
            }
            if (domesticOnly && !KOREA.equals(place.zone())) {
                return false;
            }
            return !period.past(today) && !period.to().isAfter(today.plusDays(maxDays - 1L));
        }

        /** 실물과 같이 가른다 — Open-Meteo 둘은 한 응답에 담아 오고 AccuWeather는 낮/밤뿐이다. */
        @Override
        public boolean providesPrecipitationHours() {
            return source != WeatherSource.ACCU_WEATHER;
        }

        @Override
        public Weather forecast(GeoLocation place, WeatherPeriod period) {
            calls.incrementAndGet();
            if (fails) {
                throw new IllegalStateException("서킷브레이커 열림");
            }
            return new Weather(place,
                    List.of(Weather.Daily.withChance(period.from(), SkyCondition.CLOUDY,
                            new BigDecimal("18.2"), new BigDecimal("29.6"), 20)),
                    source);
        }
    }
    @Test
    @DisplayName("하루도 없는 날씨는 만들 수 없다 — from()이 days.get(0)을 무방비로 인덱싱한다")
    void refusesAWeatherWithoutAnyDay() {
        // 주석에만 있던 불변식이다. 빈 목록이 통과하면 렌더 시점에 IndexOutOfBounds가 나고,
        // 웹훅에서는 그게 침묵이 된다 — 생산자에서 멀리 떨어진 자리에서 터지는 것이 가장 나쁘다
        assertThatThrownBy(() -> new io.saiden.economyhelper.market.weather.Weather(
                new GeoLocation("미금역", null, 37.35, 127.10889, java.time.ZoneId.of("Asia/Seoul")),
                java.util.List.of(), WeatherSource.ACCU_WEATHER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
