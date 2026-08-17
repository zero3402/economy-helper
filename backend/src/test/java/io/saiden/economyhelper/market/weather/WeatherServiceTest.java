package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;

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
        FakeClient primary = FakeClient.returning(WeatherSource.OPEN_METEO);
        FakeClient fallback = FakeClient.returning(WeatherSource.MET_NO);

        Optional<Weather> weather = service(primary, fallback).forecast(SEOUL, today());

        assertThat(weather).isPresent();
        assertThat(weather.get().source()).isEqualTo(WeatherSource.OPEN_METEO);
        assertThat(fallback.calls).hasValue(0);
    }

    @Test
    @DisplayName("1순위가 죽으면 2순위로 넘어가고 출처도 그쪽으로 바뀐다 — 숨기면 거짓말이 된다")
    void fallsBackAndSaysSo() {
        FakeClient primary = FakeClient.failing(WeatherSource.OPEN_METEO);
        FakeClient fallback = FakeClient.returning(WeatherSource.MET_NO);

        Optional<Weather> weather = service(primary, fallback).forecast(SEOUL, today());

        assertThat(weather).isPresent();
        assertThat(weather.get().source())
                .as("met.no가 답했는데 Open-Meteo라고 적으면 거짓말이 된다")
                .isEqualTo(WeatherSource.MET_NO);
        assertThat(primary.calls).hasValue(1);
        assertThat(fallback.calls).hasValue(1);
    }

    @Test
    @DisplayName("전부 죽으면 빈손이다 — 지어내지 않는다")
    void returnsEmptyWhenEverySourceFails() {
        WeatherService service = service(
                FakeClient.failing(WeatherSource.OPEN_METEO), FakeClient.failing(WeatherSource.MET_NO));

        assertThat(service.forecast(SEOUL, today())).isEmpty();
    }

    @Test
    @DisplayName("주입 순서를 뒤집어도 코드가 정한 순서가 이긴다")
    void orderIsDeclaredNotInjected() {
        FakeClient primary = FakeClient.returning(WeatherSource.OPEN_METEO);
        FakeClient fallback = FakeClient.returning(WeatherSource.MET_NO);

        // met.no를 먼저 주입해도 Open-Meteo가 1순위여야 한다
        WeatherService service = new WeatherService(List.of(fallback, primary), fixedClock());

        assertThat(service.forecast(SEOUL, today()).orElseThrow().source())
                .isEqualTo(WeatherSource.OPEN_METEO);
        assertThat(fallback.calls).hasValue(0);
    }

    @Test
    @DisplayName("지난 날짜는 예보 출처를 아예 부르지 않는다 — 못 하는 줄 알면서 부르면 브레이커만 상한다")
    void skipsForecastSourcesForPastDates() {
        FakeClient forecast = FakeClient.returning(WeatherSource.OPEN_METEO);
        FakeClient archive = FakeClient.returning(WeatherSource.OPEN_METEO_ARCHIVE);
        WeatherPeriod past = WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1);

        Optional<Weather> weather = new WeatherService(List.of(forecast, archive), fixedClock())
                .forecast(SEOUL, past);

        assertThat(weather.orElseThrow().source()).isEqualTo(WeatherSource.OPEN_METEO_ARCHIVE);
        assertThat(forecast.calls).as("예보 출처는 과거를 맡지 않는다").hasValue(0);
    }

    @Test
    @DisplayName("맡을 출처가 하나도 없으면 빈손이다 — 실패와 구분해 남긴다")
    void returnsEmptyWhenNoSourceCoversTheRange() {
        // 예보 출처만 있는데 지난 날짜를 물었다
        WeatherService service = service(FakeClient.returning(WeatherSource.OPEN_METEO));
        WeatherPeriod past = WeatherPeriod.of(TODAY, LocalDate.of(2025, 8, 19), null, 1);

        assertThat(service.forecast(SEOUL, past)).isEmpty();
    }

    @Test
    @DisplayName("오늘은 그 지역의 오늘이다 — 우리 달력으로 자르면 남의 하루가 쪼개진다")
    void todayFollowsTheLocationsOwnZone() {
        WeatherService service = service(FakeClient.returning(WeatherSource.OPEN_METEO));
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
        return new WeatherService(List.of(clients), fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** 성공/실패를 지정하는 스텁. 호출 수를 세어 "안 불렀다"까지 단언할 수 있게 한다. */
    private static final class FakeClient implements WeatherClient {

        private final WeatherSource source;
        private final boolean fails;
        private final AtomicInteger calls = new AtomicInteger();

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

        @Override
        public WeatherSource source() {
            return source;
        }

        @Override
        public boolean supports(WeatherPeriod period, LocalDate today) {
            return source == WeatherSource.OPEN_METEO_ARCHIVE
                    ? period.past(today)
                    : !period.past(today);
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
}
