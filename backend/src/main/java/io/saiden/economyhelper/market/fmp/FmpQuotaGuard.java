package io.saiden.economyhelper.market.fmp;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * FMP 무료 티어의 <b>하루 250회</b>를 센다.
 *
 * <p>resilience4j 리미터로는 지킬 수 없다 — 그건 초·분 단위 창이고 이건 <b>일 단위 총량</b>이다.
 * {@code KEXIM}(1,000회/일)은 1시간 캐시가 실질 방어였지만 여기는 "현재가"를 보여야 해서
 * 캐시를 1분까지밖에 못 잡는다. 그래서 직접 센다.
 *
 * <p>카운터를 Redis에 두는 이유는 <b>인스턴스가 늘어도 한도는 하나</b>이기 때문이다.
 * 메모리에 두면 replicas 수만큼 한도를 넘긴다({@code SendHistory}가 슬롯을 Redis에 두는 것과 같다).
 *
 * <p><b>Redis가 죽으면 통과시킨다.</b> 카운터를 못 읽는다고 미국 시세를 통째로 막는 것은
 * 과하다 — 한도 초과의 대가는 그날 남은 호출이 실패하는 것뿐이고, 여기서 막으면 확실히 실패한다.
 */
@Component
public class FmpQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(FmpQuotaGuard.class);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String KEY_PREFIX = "fmp:quota:";

    /**
     * 카운터 수명. 하루보다 넉넉히 잡아 자정 경계에서 키가 먼저 사라지는 일을 막는다.
     * 날짜가 키에 들어 있어 오래 남아도 다음 날 집계를 오염시키지 않는다.
     */
    private static final Duration TTL = Duration.ofHours(26);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int dailyLimit;

    public FmpQuotaGuard(StringRedisTemplate redis, Clock clock,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${economy-helper.market.fmp.daily-limit:240}") int dailyLimit) {
        this.redis = redis;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    /**
     * 호출 한 번을 기록하고 아직 여유가 있는지 답한다.
     *
     * @return 호출해도 되면 true. false면 <b>호출하지 않는다</b> — 어차피 FMP가 거절한다
     */
    public boolean tryAcquire() {
        String key = KEY_PREFIX + LocalDate.ofInstant(clock.instant(), SEOUL).format(DAY);
        try {
            Long used = redis.opsForValue().increment(key);
            if (used == null) {
                return true;
            }
            if (used == 1L) {
                // 키가 새로 생겼을 때만 만료를 건다. 매번 걸면 창이 계속 밀려 영영 안 지워진다
                redis.expire(key, TTL);
            }
            if (used > dailyLimit) {
                log.warn("[fmp] 오늘 호출이 한도({})를 넘었습니다 — {}회. 자정(KST)에 풀립니다", dailyLimit, used);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // 카운터를 못 세는 것과 시세를 못 주는 것 중 후자가 더 나쁘다
            log.warn("[fmp] 쿼터 카운터 접근 실패 — 그냥 호출합니다: {}", e.toString());
            return true;
        }
    }
}
