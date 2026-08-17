package io.saiden.economyhelper.digest;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.WeatherLocation;
import io.saiden.economyhelper.digest.DailyDigestJobTest.InMemoryHistory;
import io.saiden.economyhelper.digest.DailyDigestJobTest.RecordingClient;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherClient;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherService;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오전 6시 알람의 계약을 고정한다.
 *
 * <p><b>가장 중요한 건 슬롯 접두사다.</b> {@link SendHistory}는 브리핑과 같은 저장소를 쓰고
 * 두 잡의 슬롯이 둘 다 {@code yyyy-MM-dd}라, 접두사가 빠지면 6시 날씨가 슬롯을 잡아
 * <b>9시 브리핑이 통째로 안 나간다.</b> 증상이 "브리핑이 조용히 사라짐"이라 원인에서 아주 멀다.
 */
class WeatherDigestJobTest {

    /** UTC 2026-08-16 21:00 = KST 2026-08-17 06:00 — 알람 시각이다. */
    private static final Instant NOW = Instant.parse("2026-08-16T21:00:00Z");
    private static final String SLOT = "weather-2026-08-17";

    @Test
    @DisplayName("슬롯에 weather- 접두사를 붙인다 — 안 붙이면 9시 브리핑이 조용히 사라진다")
    void neverEatsTheBriefingSlot() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingClient telegram = new RecordingClient();

        job(telegram, history, alwaysSucceeds()).run(false);

        assertThat(history.claimed)
                .as("날씨 슬롯은 접두사를 이고 있어야 한다")
                .contains(SLOT)
                .as("브리핑이 쓸 슬롯(2026-08-17)을 건드리면 안 된다")
                .doesNotContain("2026-08-17");
    }

    @Test
    @DisplayName("같은 날 두 번째 틱은 건너뛴다 — 발송 창 안에서 10분마다 돌기 때문이다")
    void sendsOnlyOncePerDay() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingClient telegram = new RecordingClient();
        WeatherDigestJob job = job(telegram, history, alwaysSucceeds());

        job.run(false);
        DigestResult second = job.run(false);

        assertThat(telegram.sent).hasSize(1);
        assertThat(second.sent()).isFalse();
    }

    @Test
    @DisplayName("한 곳이 실패해도 나머지는 보낸다 — 한 곳 때문에 아침 알람을 통째로 잃지 않는다")
    void sendsWhatItCouldGather() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingClient telegram = new RecordingClient();

        // 미금역만 성공하고 나머지 셋은 실패한다
        DigestResult result = job(telegram, history, onlyFor("미금역")).run(false);

        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.sent.get(0)).contains("미금역").doesNotContain("서현역");
        assertThat(result.sent()).isTrue();
        assertThat(result.failed())
                .as("실패한 지역의 사유를 버리지 않는다 — 수동 점검이 응답만 보고 판단해야 한다")
                .hasSize(3);
    }

    @Test
    @DisplayName("한 곳도 못 가져오면 슬롯을 되돌린다 — 안 보낸 날을 '보냄'으로 남기면 복구가 안 된다")
    void releasesTheSlotWhenNothingCouldBeSent() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingClient telegram = new RecordingClient();

        DigestResult result = job(telegram, history, onlyFor("없는곳")).run(false);

        assertThat(telegram.sent).isEmpty();
        assertThat(result.sent()).isFalse();
        assertThat(history.claimed).as("되돌려야 다음 틱이 다시 시도한다").doesNotContain(SLOT);
    }

    @Test
    @DisplayName("알람은 물어본 사람이 없다 — 제목에 검색어를 적지 않는다")
    void alarmTitleCarriesNoQuery() {
        RecordingClient telegram = new RecordingClient();

        job(telegram, new InMemoryHistory(), alwaysSucceeds()).run(false);

        assertThat(telegram.sent.get(0)).startsWith("<b>날씨</b>").doesNotContain("'");
    }

    private static WeatherDigestJob job(RecordingClient telegram, InMemoryHistory history,
                                        WeatherClient client) {
        WeatherService weather = new WeatherService(List.of(client), clock());
        return new WeatherDigestJob(weather, telegram, history, clock(), properties());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static EconomyHelperProperties properties() {
        return new EconomyHelperProperties(null, null, null, null,
                new EconomyHelperProperties.Weather("Asia/Seoul", "0 0/10 6-7 * * *", List.of(
                        new WeatherLocation("미금역", 37.35, 127.10889),
                        new WeatherLocation("서현역", 37.3851167, 127.1232944),
                        new WeatherLocation("잠실역", 37.51325, 127.100111),
                        new WeatherLocation("삼성중앙역", 37.512806, 127.052612))));
    }

    private static WeatherClient alwaysSucceeds() {
        return new StubClient(null);
    }

    /** 이름이 일치하는 지역만 값을 준다 — 부분 실패를 만든다. */
    private static WeatherClient onlyFor(String name) {
        return new StubClient(Set.of(name));
    }

    private record StubClient(Set<String> only) implements WeatherClient {

        @Override
        public WeatherSource source() {
            return WeatherSource.OPEN_METEO;
        }

        @Override
        public boolean supports(WeatherPeriod period, LocalDate today) {
            return !period.past(today);
        }

        @Override
        public Weather forecast(GeoLocation place, WeatherPeriod period) {
            if (only != null && !only.contains(place.name())) {
                throw new IllegalStateException("조회 실패");
            }
            return new Weather(place,
                    List.of(Weather.Daily.withChance(period.from(), SkyCondition.CLOUDY,
                            new BigDecimal("18.2"), new BigDecimal("29.6"), 20)),
                    source());
        }
    }
}
