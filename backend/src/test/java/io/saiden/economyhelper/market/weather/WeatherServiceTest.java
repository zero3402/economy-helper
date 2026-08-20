package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.time.LocalTime;
import io.saiden.economyhelper.market.weather.PrecipitationSpell;
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
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);

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
        PrecipitationSpell spell = PrecipitationSpell.withChance(
                LocalTime.of(13, 0), LocalTime.of(19, 0), SkyCondition.RAIN, 80);
        WeatherService service = new WeatherService(List.of(new FakeClient(WeatherSource.ACCU_WEATHER, false)),
                fixedClock(), TestWeather.hourly(Map.of(day, List.of(spell))));

        Optional<Weather> weather = service.forecast(SEOUL, WeatherPeriod.of(day, null, null, null));

        assertThat(weather).get().extracting(Weather::days).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(Weather.Daily.class))
                .singleElement()
                .satisfies(each -> assertThat(each.precipitation()).containsExactly(spell));
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
                .satisfies(each -> assertThat(each.precipitation())
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
        private int maxDays = Integer.MAX_VALUE;

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
        public boolean supports(WeatherPeriod period, LocalDate today) {
            if (source == WeatherSource.OPEN_METEO_ARCHIVE) {
                return period.past(today);
            }
            return !period.past(today) && !period.to().isAfter(today.plusDays(maxDays - 1L));
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
