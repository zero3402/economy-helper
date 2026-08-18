package io.saiden.economyhelper.digest;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.WeatherLocation;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherService;
import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.telegram.MessageFormatter;
import io.saiden.economyhelper.telegram.TelegramClient;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 6시(KST) 날씨 알람 — 설정에 적힌 지역들(지금은 미금·서현·잠실·삼성중앙역).
 *
 * <p>{@link DailyDigestJob}과 같은 구조다: 발송 창 안에서 10분마다 돌고 <b>슬롯(KST 날짜)이
 * 하루 한 번을 보장한다.</b> 잠들어 있었으면 깨어난 뒤 첫 틱이 보낸다 — "정확히 6시에 깨어
 * 있어야 한다"는 요구가 사라진다.
 *
 * <p>⚠️ <b>슬롯 키에 접두사를 붙인다.</b> {@link SendHistory}는 {@code digest:sent:} 하나를
 * 쓰는데 이 잡과 브리핑의 슬롯이 둘 다 {@code yyyy-MM-dd}다. 그대로 두면 <b>6시 날씨가 슬롯을
 * 잡아 9시 브리핑이 통째로 안 나간다.</b> {@code SendHistory}를 고치지 않고 여기서 접두사를
 * 붙이는 이유는 기존 키를 그대로 두기 위해서다.
 *
 * <p><b>지오코딩을 타지 않는다.</b> 지역이 좌표로 설정에 박혀 있다 — 지명 검색이 죽어도 아침
 * 알람은 나가고, LLM 호출도 붙지 않는다(브리핑이 종목코드를 박아 LLM을 안 타는 것과 같다).
 */
@Component
public class WeatherDigestJob {

    private static final Logger log = LoggerFactory.getLogger(WeatherDigestJob.class);

    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 브리핑 슬롯과 섞이지 않게 하는 접두사. 이게 없으면 둘 중 하나만 나간다. */
    private static final String SLOT_PREFIX = "weather-";

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final WeatherService weatherService;
    private final TelegramClient telegram;
    private final SendHistory history;
    private final Clock clock;
    private final ZoneId zone;
    private final List<GeoLocation> places;

    public WeatherDigestJob(WeatherService weatherService,
                            TelegramClient telegram,
                            SendHistory history,
                            Clock clock,
                            EconomyHelperProperties properties) {
        this.weatherService = weatherService;
        this.telegram = telegram;
        this.history = history;
        this.clock = clock;
        this.zone = zoneOf(properties.weather());
        this.places = toPlaces(properties.weather(), this.zone);
    }

    private static ZoneId zoneOf(EconomyHelperProperties.Weather weather) {
        return weather == null || weather.zone() == null
                ? DEFAULT_ZONE : ZoneId.of(weather.zone());
    }

    /**
     * 설정의 좌표를 조회 지점으로 옮긴다.
     *
     * <p>이름은 설정에 적은 것을 그대로 쓴다 — 지오코딩이 붙여 줄 {@code 성남시}보다
     * {@code 서현역}이 알람을 받는 사람에게 정확하다. 나라는 적지 않는다(전부 국내다).
     */
    private static List<GeoLocation> toPlaces(EconomyHelperProperties.Weather weather, ZoneId zone) {
        if (weather == null || weather.locations() == null) {
            return List.of();
        }
        List<GeoLocation> places = new ArrayList<>(weather.locations().size());
        for (WeatherLocation location : weather.locations()) {
            places.add(new GeoLocation(location.name(), null,
                    location.latitude(), location.longitude(), zone));
        }
        return List.copyOf(places);
    }

    /**
     * 스케줄 진입점.
     *
     * <p>{@code @SchedulerLock} 이름을 브리핑과 따로 준다 — 같은 이름을 쓰면 6시와 9시가
     * 서로를 막는다.
     */
    @Scheduled(cron = "${economy-helper.weather.cron}", zone = "${economy-helper.weather.zone}")
    @SchedulerLock(name = "weatherDigest", lockAtLeastFor = "PT5M", lockAtMostFor = "PT10M")
    public void sendScheduled() {
        DigestResult result = run(false);
        // 스케줄 경로는 아무도 응답을 보지 않는다. 실패했으면 여기서라도 크게 남겨야
        // "아침에 날씨가 안 왔다"가 다음 날에야 발견되는 일을 막는다
        if (!result.sent() && !result.failed().isEmpty()) {
            log.error("[weather] 스케줄 발송이 아무것도 내보내지 못했습니다: {}", result.failed());
        }
    }

    /**
     * 마지막 실행 결과 — {@code GET /actuator/weather}가 이걸 돌려준다.
     *
     * <p>확인하려고 실제 알람을 한 번 더 쏘지 않아도 되게 하려고 들고 있는다.
     */
    private volatile DigestResult lastResult =
            new DigestResult(false, null, List.of(), List.of(), "아직 실행된 적이 없습니다");

    public DigestResult lastResult() {
        return lastResult;
    }

    /**
     * @param force 이미 보낸 슬롯이어도 다시 보낸다. 수동 점검용이다
     */
    public DigestResult run(boolean force) {
        DigestResult result = execute(force);
        lastResult = result;
        return result;
    }

    /**
     * <b>{@code boolean}이 아니라 {@link DigestResult}를 돌려준다.</b> 지역별 부분 실패를
     * 허용하는 잡이라("한 곳이 안 된다고 아침 알람을 통째로 잃을 이유가 없다") 수동 점검이
     * <b>무엇이 나갔고 무엇이 왜 실패했는지</b>를 알아야 한다 — 배포처에서는 로그를 보기 어렵고
     * 스모크 테스트는 응답만 보고 판단할 수 있어야 한다. 브리핑이 같은 이유로 그렇게 한다.
     */
    private DigestResult execute(boolean force) {
        String slot = SLOT_PREFIX + clock.instant().atZone(zone).format(SLOT_FORMAT);
        if (places.isEmpty()) {
            log.warn("[weather] 알람 지역이 설정돼 있지 않습니다 — 보낼 것이 없습니다");
            return DigestResult.skipped(slot, "알람 지역이 설정돼 있지 않습니다");
        }

        boolean claimed;
        try {
            claimed = history.claim(slot);
        } catch (RuntimeException e) {
            // Redis가 죽으면 슬롯을 판단할 수 없다. 사유를 결과에 담아 밖에서 보이게 한다
            log.error("[weather] 발송 이력 조회 실패 — Redis 연결을 확인하세요: {}", e.toString());
            return DigestResult.skipped(slot, "발송 이력(Redis) 조회 실패: " + e);
        }
        if (!claimed && !force) {
            // 발송 창 안에서 10분마다 도는 구조라 이 분기가 하루에 열 번 넘게 지나간다
            log.debug("[weather] {} 슬롯은 이미 발송됐습니다 — 건너뜁니다", slot);
            return DigestResult.skipped(slot, "오늘은 이미 발송했습니다");
        }

        List<DigestResult.Failure> failed = new ArrayList<>();
        List<Weather> collected = collect(failed);
        if (collected.isEmpty()) {
            // 보낸 적 없는 슬롯을 "보냄"으로 남기면 그날 알람이 영영 복구되지 않는다
            releaseIfClaimed(claimed, slot);
            log.warn("[weather] {} 슬롯에 보낼 날씨가 없습니다 — 발송하지 않습니다", slot);
            return DigestResult.allFailed(slot, failed);
        }

        try {
            // 알람은 물어본 사람이 없다 — 제목에 검색어를 적지 않는다
            telegram.send(MessageFormatter.formatWeather(collected), false);
        } catch (RuntimeException e) {
            releaseIfClaimed(claimed, slot);
            log.error("[weather] {} 슬롯 발송 실패: {}", slot, e.toString());
            failed.add(new DigestResult.Failure("날씨", reasonOf(e)));
            return DigestResult.allFailed(slot, failed);
        }
        log.info("[weather] {} 슬롯 발송 완료 — {}곳 (실패 {})", slot, collected.size(), failed);
        return DigestResult.completed(slot, List.of("날씨"), failed);
    }

    private static String reasonOf(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * 설정된 지역을 <b>겹쳐</b> 조회한다. 서로 무관한 외부 호출이라 줄줄이 기다릴 이유가 없다.
     *
     * <p>일부가 실패해도 나머지는 보낸다 — 한 곳이 안 된다고 아침 알람을 통째로 잃을 이유가 없다.
     * 다만 <b>사유는 버리지 않는다</b> — 수동 점검이 응답만 보고 판단할 수 있어야 한다.
     */
    private List<Weather> collect(List<DigestResult.Failure> failed) {
        List<Optional<Weather>> results = Concurrently.map(places, this::forecastOf);
        List<Weather> collected = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).isPresent()) {
                collected.add(results.get(i).get());
            } else {
                failed.add(new DigestResult.Failure(places.get(i).name(), "날씨를 가져오지 못했습니다"));
            }
        }
        return List.copyOf(collected);
    }

    /** 오늘 하루치. 알람은 언제나 그날 하루다. */
    private Optional<Weather> forecastOf(GeoLocation place) {
        try {
            WeatherPeriod today = WeatherPeriod.of(weatherService.today(place), null, null, null);
            return weatherService.forecast(place, today);
        } catch (RuntimeException e) {
            log.error("[weather] {} 조회 실패: {}", place.name(), e.toString());
            return Optional.empty();
        }
    }

    private void releaseIfClaimed(boolean claimed, String slot) {
        if (claimed) {
            history.release(slot);
        }
    }
}
