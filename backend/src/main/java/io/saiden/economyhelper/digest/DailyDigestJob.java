package io.saiden.economyhelper.digest;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.telegram.MessageFormatter;
import io.saiden.economyhelper.telegram.TelegramClient;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 09시·21시(KST) 매체별 1건씩 텔레그램으로 보낸다.
 *
 * <p>중복 발송은 <b>두 겹</b>으로 막는다. {@link SchedulerLock}이 인스턴스 간 동시 실행을 막아
 * 수집·번역을 두 번 하지 않게 하고, {@link SendHistory}가 슬롯 단위로 발송 자체를 한 번으로 묶는다.
 * 락만으로 부족한 이유는 {@link SendHistory}에 적어 두었다.
 */
@Component
public class DailyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);

    /** 슬롯 = KST 날짜 + 시. 09시 발송과 21시 발송이 다른 키를 갖는다. */
    private static final DateTimeFormatter SLOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    private final NewsFacade facade;
    private final TelegramClient telegram;
    private final SendHistory history;
    private final Clock clock;
    private final ZoneId zone;

    public DailyDigestJob(NewsFacade facade,
                          TelegramClient telegram,
                          SendHistory history,
                          Clock clock,
                          EconomyHelperProperties properties) {
        this.facade = facade;
        this.telegram = telegram;
        this.history = history;
        this.clock = clock;
        this.zone = ZoneId.of(properties.digest().zone());
    }

    /**
     * 스케줄 진입점.
     *
     * <p>{@code @SchedulerLock}은 프록시로 걸리므로 <b>이 메서드에만</b> 유효하다.
     * {@link #run(boolean)}을 여기서 직접 부르는 건 자기 호출이라 락을 타지 않는다 —
     * 의도한 것이다. 락은 스케줄 실행에만 필요하고, 수동 트리거는 슬롯 선점으로 충분하다.
     */
    @Scheduled(cron = "${economy-helper.digest.cron}", zone = "${economy-helper.digest.zone}")
    @SchedulerLock(name = "dailyDigest", lockAtLeastFor = "PT5M", lockAtMostFor = "PT20M")
    public void sendScheduled() {
        run(false);
    }

    /**
     * @param force 이미 보낸 슬롯이어도 다시 보낸다. 수동 트리거로 같은 시간대를 반복
     *              점검할 때만 쓴다
     */
    public DigestResult run(boolean force) {
        String slot = currentSlot();

        boolean claimed = history.claim(slot);
        if (!claimed && !force) {
            log.info("[digest] {} 슬롯은 이미 발송됐습니다 — 건너뜁니다", slot);
            return DigestResult.skipped(slot, "이미 발송된 시간대입니다");
        }

        try {
            List<NewsItem> items = facade.digest();
            if (items.isEmpty()) {
                // 전 매체 수집 실패다. "보냈다"로 남기면 이 시간대는 복구 후에도 영영 비어 있다
                releaseIfClaimed(claimed, slot);
                log.warn("[digest] {} 슬롯에 보낼 뉴스가 없습니다 — 발송하지 않습니다", slot);
                return DigestResult.skipped(slot, "가져온 뉴스가 없습니다");
            }

            telegram.send(MessageFormatter.formatDigest(items));
            log.info("[digest] {} 슬롯 발송 완료 — 매체 {}개", slot, items.size());
            return DigestResult.completed(slot, items.size());
        } catch (RuntimeException e) {
            releaseIfClaimed(claimed, slot);
            log.error("[digest] {} 슬롯 발송 실패: {}", slot, e.toString(), e);
            return DigestResult.skipped(slot, "발송 실패: " + e);
        }
    }

    private void releaseIfClaimed(boolean claimed, String slot) {
        // force로 들어와 남의 선점을 지나쳤을 수 있다. 내가 잡은 것만 되돌린다
        if (claimed) {
            history.release(slot);
        }
    }

    private String currentSlot() {
        return clock.instant().atZone(zone).format(SLOT_FORMAT);
    }
}
