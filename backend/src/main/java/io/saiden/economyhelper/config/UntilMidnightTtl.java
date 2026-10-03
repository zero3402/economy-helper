package io.saiden.economyhelper.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * 수명 {@code cap}, 단 <b>그 시장의 다음 자정을 넘기지 않는다.</b>
 *
 * <p>전망 캐시(목표가·실적발표일·배당)에 담기는 값은 <b>담을 때의 「오늘」로 이미 골라져 있다</b>
 * ({@code Dividend.nextOf}·실적발표일 고르기). 12시간을 그대로 두면 기준일 당일 밤 22시에 담긴
 * 「배당기준일 09.30」이 다음 날 09시 브리핑에 「지난」 없이 나간다. 자정에 끊으면 다음 조회가 새 오늘로 다시 고른다.
 * 대가는 자정 직후 한 번씩 다시 부르는 것뿐이다 — 한도(FMP 하루 250)는 그대로 지켜진다.
 */
final class UntilMidnightTtl implements RedisCacheWriter.TtlFunction {

    private static final Duration FLOOR = Duration.ofSeconds(1);

    private final Duration cap;
    private final ZoneId zone;
    private final Clock clock;

    UntilMidnightTtl(Duration cap, ZoneId zone, Clock clock) {
        this.cap = cap;
        this.zone = zone;
        this.clock = clock;
    }

    UntilMidnightTtl(Duration cap, ZoneId zone) {
        this(cap, zone, Clock.systemUTC());
    }

    @Override
    public Duration getTimeToLive(Object key, Object value) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        Duration untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone));
        // ⚠️ 1초 밑으로는 안 내린다 — 1ms 미만은 PX 0이 되어 담기 자체가 실패한다. 자정을 1초 못 넘기는 값은 해롭지 않다
        Duration ttl = untilMidnight.compareTo(cap) < 0 ? untilMidnight : cap;
        return ttl.compareTo(FLOOR) < 0 ? FLOOR : ttl;
    }
}
