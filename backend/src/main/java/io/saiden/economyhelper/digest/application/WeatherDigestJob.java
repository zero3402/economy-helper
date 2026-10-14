package io.saiden.economyhelper.digest.application;

import io.saiden.economyhelper.config.EconomyHelperProperties.WeatherLocation;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.digest.application.port.out.DigestMessage;
import io.saiden.economyhelper.digest.application.port.out.DigestNotifier;
import io.saiden.economyhelper.digest.application.port.out.SendHistory;
import io.saiden.economyhelper.digest.domain.DigestResult;
import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.weather.application.WeatherService;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 8시(KST) 날씨 알람 — 설정에 적힌 지역들(지금은 미금·서현·잠실·삼성중앙역).
 *
 * <p>{@link DailyDigestJob}과 같은 구조다: 발송 창 안에서 10분마다 돌고 <b>슬롯(KST 날짜)이
 * 하루 한 번을 보장한다.</b> 잠들어 있었으면 깨어난 뒤 첫 틱이 보낸다 — "정확히 8시에 깨어
 * 있어야 한다"는 요구가 사라진다.
 *
 * <p>⚠️ <b>슬롯 키에 접두사를 붙인다</b> — 이유는 {@link DigestSlot}.
 *
 * <p><b>지오코딩을 타지 않는다.</b> 지역이 좌표로 설정에 박혀 있다 — 지명 검색이 죽어도 아침
 * 알람은 나가고, LLM 호출도 붙지 않는다(브리핑이 종목코드를 박아 LLM을 안 타는 것과 같다).
 */
@Component
public class WeatherDigestJob extends TriggerableJob {

    private static final Logger log = LoggerFactory.getLogger(WeatherDigestJob.class);

    /** 브리핑 슬롯과 섞이지 않게 하는 접두사. 이게 없으면 둘 중 하나만 나간다. */
    private static final String SLOT_PREFIX = "weather-";

    private final WeatherService weatherService;
    private final DigestNotifier notifier;
    private final DigestSlot slot;
    private final List<GeoLocation> places;

    public WeatherDigestJob(WeatherService weatherService,
                            DigestNotifier notifier,
                            SendHistory history,
                            Clock clock,
                            EconomyHelperProperties properties) {
        this.weatherService = weatherService;
        this.notifier = notifier;
        // 브리핑(DailyDigestJob)과 같이 설정의 시간대를 그대로 쓴다 — application.yml이 늘 채운다
        ZoneId zone = ZoneId.of(properties.weather().zone());
        this.slot = new DigestSlot(history, clock, zone, SLOT_PREFIX, "weather");
        this.places = toPlaces(properties.weather(), zone);
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
     * <p>{@code @SchedulerLock} 이름을 브리핑과 따로 준다 — 같은 이름을 쓰면 8시와 9시가
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
     * 지역별 부분 실패를 허용한다 — "한 곳이 안 된다고 아침 알람을 통째로 잃을 이유가 없다".
     *
     * <p><b>지역이 하나도 설정돼 있지 않으면 슬롯을 잡지 않는다.</b> 보낼 것이 없는데 오늘
     * 몫을 선점하면, 설정을 고쳐 넣어도 그날은 영영 안 나간다.
     */
    @Override
    protected DigestResult execute(boolean force) {
        if (places.isEmpty()) {
            log.warn("[weather] 알람 지역이 설정돼 있지 않습니다 — 보낼 것이 없습니다");
            return DigestResult.skipped(slot.id(), "알람 지역이 설정돼 있지 않습니다");
        }

        DigestSlot.Claim claim = slot.claim(force);
        if (!claim.proceed()) {
            return DigestResult.skipped(claim.id(), claim.blockedReason());
        }

        List<DigestResult.Failure> failed = new ArrayList<>();
        List<Weather> collected;
        try {
            collected = collect(failed);
        } catch (RuntimeException | Error e) {
            // forecastOf가 RuntimeException은 삼키지만 Error와 인터럽트는 새어 온다 — 배포 중 종료가
            // 그 경로다. 슬롯을 잡은 채 새면 그날 알람이 영영 안 나간다(DailyDigestJob과 같은 자리)
            slot.release(claim);
            log.error("[weather] {} 수집 중 예외 — 슬롯을 되돌립니다: {}", claim.id(), e.toString());
            throw e;
        }
        if (collected.isEmpty()) {
            // 보낸 적 없는 슬롯을 "보냄"으로 남기면 그날 알람이 영영 복구되지 않는다
            slot.release(claim);
            log.warn("[weather] {} 슬롯에 보낼 날씨가 없습니다 — 발송하지 않습니다", claim.id());
            return DigestResult.allFailed(claim.id(), failed);
        }

        DigestNotifier.Delivery delivery = notifier.send(new DigestMessage.WeatherAlarm(collected));
        if (delivery.failure() != null) {
            // 한 통짜리라 실패면 아무것도 안 나갔다
            slot.release(claim);
            log.error("[weather] {} 슬롯 발송 실패: {}", claim.id(), delivery.failure().toString());
            failed.add(DigestResult.Failure.of("날씨", delivery.failure()));
            return DigestResult.allFailed(claim.id(), failed);
        }
        log.info("[weather] {} 슬롯 발송 완료 — {}곳 (실패 {})", claim.id(), collected.size(), failed);
        return DigestResult.completed(claim.id(), List.of("날씨"), failed);
    }

    /**
     * 설정된 지역을 <b>겹쳐</b> 조회한다. 실패한 곳은 <b>실제 사유를 남기고</b> 빠진다 — 수동 점검이
     * 응답만 보고 판단할 수 있어야 한다. 한때 사유가 늘 「날씨를 가져오지 못했습니다」라, 2026-10-14
     * 08:00 알람에서 세 곳이 빠졌을 때 무엇이 터졌는지 남은 것이 없었다.
     */
    private List<Weather> collect(List<DigestResult.Failure> failed) {
        List<Gathered> results = Concurrently.map(places, this::forecastOf);
        List<Weather> collected = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            Gathered result = results.get(i);
            if (result.weather() != null) {
                collected.add(result.weather());
            } else {
                failed.add(new DigestResult.Failure(places.get(i).name(), result.reason()));
            }
        }
        return List.copyOf(collected);
    }

    /**
     * 오늘 하루치. 알람은 언제나 그날 하루다.
     *
     * <p>출처별 실패는 {@code WeatherService}가 지역 이름과 함께 한 줄씩 남긴다. 여기서는 그 사유를
     * 모아 결과에 싣는다 — 「기상청 IllegalStateException — HTTP 500 · Open-Meteo 브레이커 열림」처럼.
     */
    private Gathered forecastOf(GeoLocation place) {
        // 한 지역의 출처는 차례로 불리므로(Failover.first) 이 목록에 동시에 쓰는 일이 없다
        List<String> reasons = new ArrayList<>();
        try {
            LocalDate today = weatherService.today(place);
            WeatherPeriod period = WeatherPeriod.of(today, null, null, null);
            Optional<Weather> found = weatherService.forecast(place, period, today,
                    (source, e) -> reasons.add(source.displayName() + " " + FailureReason.of(e)));
            if (found.isPresent()) {
                return new Gathered(found.get(), null);
            }
            String reason = reasons.isEmpty() ? "맡을 출처가 없습니다" : String.join(" · ", reasons);
            log.warn("[weather] {} 알람에서 빠집니다: {}", place.name(), reason);
            return new Gathered(null, reason);
        } catch (RuntimeException e) {
            log.error("[weather] {} 조회 실패: {}", place.name(), FailureReason.of(e));
            return new Gathered(null, FailureReason.of(e));
        }
    }

    /** 한 지역의 결과 — 날씨나 빠진 사유 중 하나다. */
    private record Gathered(Weather weather, String reason) {
    }
}
