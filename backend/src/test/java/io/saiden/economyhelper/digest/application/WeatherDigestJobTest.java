package io.saiden.economyhelper.digest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.config.EconomyHelperProperties.WeatherLocation;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.digest.application.DailyDigestJobTest.InMemoryHistory;
import io.saiden.economyhelper.digest.application.port.out.SendHistory;
import io.saiden.economyhelper.digest.domain.DigestResult;
import io.saiden.economyhelper.telegram.adapter.out.TelegramClient;
import io.saiden.economyhelper.telegram.adapter.out.TelegramDigestNotifier;
import io.saiden.economyhelper.telegram.presentation.WeatherFormatter;
import io.saiden.economyhelper.testsupport.RecordingTelegram;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.testsupport.TestWeather;
import io.saiden.economyhelper.weather.application.WeatherService;
import io.saiden.economyhelper.weather.application.port.out.WeatherClient;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.SkyCondition;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import io.saiden.economyhelper.weather.domain.WeatherSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오전 8시 알람의 계약을 고정한다.
 *
 * <p><b>가장 중요한 건 슬롯 접두사다.</b> {@link SendHistory}는 브리핑과 같은 저장소를 쓰고
 * 두 잡의 슬롯이 둘 다 {@code yyyy-MM-dd}라, 접두사가 빠지면 8시 날씨가 슬롯을 잡아
 * <b>9시 브리핑이 통째로 안 나간다.</b> 증상이 "브리핑이 조용히 사라짐"이라 원인에서 아주 멀다.
 */
class WeatherDigestJobTest {

    /** UTC 2026-08-16 23:00 = KST 2026-08-17 08:00 — 알람 시각이다. 크론 없이 만든 잡은 매 틱이 마지막 틱이라 미루지 않는다. */
    private static final Instant NOW = Instant.parse("2026-08-16T23:00:00Z");
    private static final String SLOT = "weather-2026-08-17";

    @Test
    @DisplayName("슬롯에 weather- 접두사를 붙인다 — 안 붙이면 9시 브리핑이 조용히 사라진다")
    void neverEatsTheBriefingSlot() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram telegram = new RecordingTelegram();

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
        RecordingTelegram telegram = new RecordingTelegram();
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
        RecordingTelegram telegram = new RecordingTelegram();

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
    @DisplayName("빠진 곳에는 실제 사유를 남긴다 — 고정 문구로는 무엇이 터졌는지 알 수 없다")
    void recordsTheRealFailureReason() {
        // 2026-10-14 08:00 알람에서 세 곳이 빠졌는데 사유가 전부 「날씨를 가져오지 못했습니다」라
        // 기상청이 죽었는지 Open-Meteo 브레이커가 열렸는지 응답도 로그도 말하지 못했다
        WeatherClient succeeding = alwaysSucceeds();
        WeatherClient failing = new WeatherClient() {
            @Override
            public WeatherSource source() {
                return WeatherSource.OPEN_METEO;
            }

            @Override
            public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
                return true;
            }

            @Override
            public Weather forecast(GeoLocation place, WeatherPeriod period) {
                if (!place.name().equals("미금역")) {
                    throw org.springframework.web.client.HttpServerErrorException.create(
                            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "", null, null, null);
                }
                return succeeding.forecast(place, period);
            }
        };

        DigestResult result = job(new RecordingTelegram(), new InMemoryHistory(), failing).run(false);

        assertThat(result.failed())
                .extracting(DigestResult.Failure::section)
                .containsExactlyInAnyOrder("서현역", "잠실역", "삼성중앙역");
        assertThat(result.failed())
                .extracting(DigestResult.Failure::reason)
                .as("출처 이름과 FailureReason이 남는다")
                .allSatisfy(reason -> assertThat(reason).isEqualTo("Open-Meteo HTTP 503"));
    }

    @Test
    @DisplayName("한 곳도 못 가져오면 슬롯을 되돌린다 — 안 보낸 날을 '보냄'으로 남기면 복구가 안 된다")
    void releasesTheSlotWhenNothingCouldBeSent() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram telegram = new RecordingTelegram();

        DigestResult result = job(telegram, history, onlyFor("없는곳")).run(false);

        assertThat(telegram.sent).isEmpty();
        assertThat(result.sent()).isFalse();
        assertThat(history.claimed).as("되돌려야 다음 틱이 다시 시도한다").doesNotContain(SLOT);
    }

    @Test
    @DisplayName("닿았는지 모르는 발송 실패는 슬롯을 잡은 채 둔다 — 되돌리면 다음 틱에 같은 알람이 또 간다")
    void keepsTheSlotWhenTheOutcomeIsUnknown() {
        // 읽기 타임아웃: 텔레그램이 받고 응답만 잃었을 수 있다. 되돌리면 10분 뒤 네 곳 알람이 두 번 간다
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram unknown = new RecordingTelegram() {
            @Override
            public void send(String text, boolean preview) {
                throw new TelegramClient.TelegramUnknownOutcome("텔레그램 sendMessage 결과를 모릅니다", null);
            }
        };

        DigestResult result = job(unknown, history, alwaysSucceeds()).run(false);

        assertThat(result.sent()).isFalse();
        assertThat(result.deferred()).isFalse();
        assertThat(result.reason()).contains("모르는");
        assertThat(history.claimed).as("두 번 가는 것보다 한 번 빠지는 쪽이 낫다").contains(SLOT);
    }

    @Test
    @DisplayName("닿지 못한 발송 실패는 슬롯을 되돌린다 — 아무것도 안 나갔으니 다음 틱이 보낸다")
    void releasesTheSlotWhenTheSendNeverReached() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram unreachable = new RecordingTelegram() {
            @Override
            public void send(String text, boolean preview) {
                throw new TelegramClient.TelegramUnavailable("연결 거부");
            }
        };

        DigestResult result = job(unreachable, history, alwaysSucceeds()).run(false);

        assertThat(result.sent()).isFalse();
        assertThat(history.claimed).doesNotContain(SLOT);
    }

    @Test
    @DisplayName("알람은 물어본 사람이 없다 — 제목에 검색어를 적지 않는다")
    void alarmTitleCarriesNoQuery() {
        RecordingTelegram telegram = new RecordingTelegram();

        job(telegram, new InMemoryHistory(), alwaysSucceeds()).run(false);

        assertThat(telegram.sent.get(0)).startsWith("<b>날씨</b>").doesNotContain("'");
    }

    @Test
    @DisplayName("알람은 검색과 글자까지 같은 통이다 — 포매터가 하나이고 알람이 제 것을 덧대지 않는다")
    void rendersExactlyLikeSearch() {
        RecordingTelegram telegram = new RecordingTelegram();

        job(telegram, new InMemoryHistory(), alwaysSucceeds()).run(false);

        // `/weather`(TelegramWebhookController의 case FOUND)와 알람(TelegramDigestNotifier)이
        // 부르는 바로 그 함수다. 알람이 제목·머리말·꼬리를 따로 붙이기 시작하면 여기서 갈린다
        List<Weather> collected = properties().weather().locations().stream()
                .map(location -> new GeoLocation(location.name(), null,
                        location.latitude(), location.longitude(), ZoneId.of("Asia/Seoul")))
                .map(place -> new Weather(place,
                        List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17),
                                SkyCondition.CLOUDY,
                                new BigDecimal("18.2"), new BigDecimal("29.6"), 20)),
                        WeatherSource.OPEN_METEO))
                .toList();

        assertThat(telegram.sent.get(0)).isEqualTo(WeatherFormatter.format(collected));
    }

    @Test
    @DisplayName("수집이 예외로 새면 슬롯을 되돌린다 — 브리핑과 같은 자리, 같은 이유")
    void releasesTheSlotWhenCollectionEscapes() {
        InMemoryHistory history = new InMemoryHistory();
        WeatherClient exploding = new WeatherClient() {
            @Override
            public WeatherSource source() {
                return WeatherSource.OPEN_METEO;
            }

            @Override
            public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
                return true;
            }

            @Override
            public Weather forecast(GeoLocation place, WeatherPeriod period) {
                // forecastOf가 RuntimeException은 삼킨다 — Error만이 이 경로로 샌다
                throw new AssertionError("종료 신호");
            }
        };

        assertThatThrownBy(() -> job(new RecordingTelegram(), history, exploding).run(false))
                .isInstanceOf(AssertionError.class);

        assertThat(history.claimed).doesNotContain(SLOT);
    }

    // --- 빠진 지역은 창의 마지막 틱까지 기다린다 (2026-10-14 08:00 사고) ---------------------

    /** 운영 그대로의 창 — 08:00~09:50, 10분마다. */
    private static final String WINDOW = "0 0/10 8-9 * * *";
    /** UTC 2026-08-16 23:00 = KST 08:00 — 창의 첫 틱. */
    private static final Instant EIGHT = Instant.parse("2026-08-16T23:00:00Z");
    /** KST 09:50 — 창의 마지막 틱. */
    private static final Instant LAST_TICK = Instant.parse("2026-08-17T00:50:00Z");

    @Test
    @DisplayName("창에 틱이 남았는데 한 곳이라도 빠지면 보내지 않고 슬롯을 되돌린다 — 보내고 잡아 두면 그날 나머지가 영영 안 나간다")
    void defersWhenARegionIsMissingAndTheWindowHasMoreTicks() {
        // 2026-10-14 08:00 첫 틱이 미금역 하나만 보내고 슬롯을 잡아, 남은 틱 11번이 전부 「이미 발송」이었다
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram telegram = new RecordingTelegram();

        DigestResult result = job(telegram, history, onlyFor("미금역"), Clock.fixed(EIGHT, ZoneOffset.UTC), WINDOW)
                .run(false);

        assertThat(telegram.sent).as("아무것도 안 나가야 되돌려도 중복이 없다").isEmpty();
        assertThat(result.sent()).isFalse();
        assertThat(result.deferred()).isTrue();
        assertThat(result.failed())
                .extracting(DigestResult.Failure::section)
                .containsExactlyInAnyOrder("서현역", "잠실역", "삼성중앙역");
        assertThat(result.failed()).extracting(DigestResult.Failure::reason)
                .as("빠진 사유는 미룬 결과에도 남는다").allSatisfy(reason -> assertThat(reason).isNotBlank());
        assertThat(history.claimed).as("다음 틱이 다시 잡아야 한다").doesNotContain(SLOT);
    }

    @Test
    @DisplayName("창의 마지막 틱이면 모인 것만 보낸다 — 더 기다릴 틱이 없다")
    void sendsWhatItHasOnTheLastTickOfTheWindow() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram telegram = new RecordingTelegram();

        // 09:50 — 크론의 다음 틱은 내일 08:00이다
        DigestResult result = job(telegram, history, onlyFor("미금역"),
                Clock.fixed(LAST_TICK, ZoneOffset.UTC), WINDOW).run(false);

        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.sent.get(0)).contains("미금역").doesNotContain("서현역");
        assertThat(result.sent()).isTrue();
        assertThat(result.deferred()).isFalse();
        assertThat(result.failed()).hasSize(3);
        assertThat(history.claimed).contains(SLOT);
    }

    @Test
    @DisplayName("force는 미루지 않는다 — 수동 점검은 지금 답을 원한다")
    void forceSendsThePartialResultWhileTheWindowHasMoreTicks() {
        RecordingTelegram telegram = new RecordingTelegram();

        DigestResult result = job(telegram, new InMemoryHistory(), onlyFor("미금역"),
                Clock.fixed(EIGHT, ZoneOffset.UTC), WINDOW).run(true);

        assertThat(telegram.sent).hasSize(1);
        assertThat(result.sent()).isTrue();
    }

    @Test
    @DisplayName("빠진 지역이 다음 틱에 돌아오면 네 곳이 한 통으로 나간다 — 2026-10-14 사고의 거울상")
    void sendsAllFourOnTheTickWhereTheMissingRegionsRecover() {
        InMemoryHistory history = new InMemoryHistory();
        RecordingTelegram telegram = new RecordingTelegram();
        SteppingClock clock = new SteppingClock(EIGHT);
        // 세 곳은 첫 호출만 실패한다 — 08:00의 일시 장애
        WeatherDigestJob job = job(telegram, history, new FailsOnceClient(Set.of("서현역", "잠실역", "삼성중앙역")),
                clock, WINDOW);

        DigestResult first = job.run(false);
        clock.set(EIGHT.plusSeconds(600));
        DigestResult second = job.run(false);

        assertThat(first.deferred()).isTrue();
        assertThat(second.sent()).isTrue();
        assertThat(second.failed()).isEmpty();
        assertThat(telegram.sent).hasSize(1);
        assertThat(telegram.sent.get(0)).contains("미금역", "서현역", "잠실역", "삼성중앙역");
        assertThat(history.claimed).contains(SLOT);
    }

    @Test
    @DisplayName("한 곳도 못 모으면 미룸이 아니라 전부 실패다 — 사유가 ERROR로 남아야 한다")
    void stillReleasesWhenNothingAtAllCouldBeSentWhileTheWindowHasMoreTicks() {
        InMemoryHistory history = new InMemoryHistory();

        DigestResult result = job(new RecordingTelegram(), history, onlyFor("없는곳"),
                Clock.fixed(EIGHT, ZoneOffset.UTC), WINDOW).run(false);

        assertThat(result.deferred()).isFalse();
        assertThat(result.sent()).isFalse();
        assertThat(result.failed()).hasSize(4);
        assertThat(history.claimed).doesNotContain(SLOT);
    }

    private static WeatherDigestJob job(RecordingTelegram telegram, InMemoryHistory history,
                                        WeatherClient client) {
        // 크론 없음 = 매 틱이 마지막 틱 — 위의 테스트들은 빠진 지역을 기다리지 않고 「보냄」을 본다
        return job(telegram, history, client, clock(), null);
    }

    private static WeatherDigestJob job(RecordingTelegram telegram, InMemoryHistory history,
                                        WeatherClient client, Clock clock, String cron) {
        WeatherService weather = new WeatherService(List.of(client), clock, TestWeather.noHourly());
        return new WeatherDigestJob(weather, new TelegramDigestNotifier(telegram), history, clock,
                properties(cron));
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static EconomyHelperProperties properties() {
        return properties(null);
    }

    private static EconomyHelperProperties properties(String cron) {
        return TestProperties.builder()
                .weatherZone("Asia/Seoul")
                .weatherCron(cron)
                .locations(List.of(
                        new WeatherLocation("미금역", 37.35, 127.10889),
                        new WeatherLocation("서현역", 37.3851167, 127.1232944),
                        new WeatherLocation("잠실역", 37.51325, 127.100111),
                        new WeatherLocation("삼성중앙역", 37.512806, 127.052612)))
                .build();
    }

    /** 틱 사이에 시각을 옮기는 시계 — 같은 잡을 두 번 돌리는 데 쓴다. */
    private static final class SteppingClock extends Clock {

        private volatile Instant now;

        private SteppingClock(Instant start) {
            this.now = start;
        }

        void set(Instant at) {
            this.now = at;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 이름이 든 지역은 <b>첫 호출만</b> 실패한다 — 08:00의 일시 장애를 흉내 낸다. */
    private static final class FailsOnceClient implements WeatherClient {

        private final Set<String> failOnce;
        private final Set<String> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final WeatherClient delegate = alwaysSucceeds();

        private FailsOnceClient(Set<String> failOnce) {
            this.failOnce = failOnce;
        }

        @Override
        public WeatherSource source() {
            return WeatherSource.OPEN_METEO;
        }

        @Override
        public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
            return !period.past(today);
        }

        @Override
        public Weather forecast(GeoLocation place, WeatherPeriod period) {
            if (failOnce.contains(place.name()) && failed.add(place.name())) {
                throw new IllegalStateException("첫 호출 실패");
            }
            return delegate.forecast(place, period);
        }
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
        public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
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
