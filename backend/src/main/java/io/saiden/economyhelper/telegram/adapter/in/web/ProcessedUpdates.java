package io.saiden.economyhelper.telegram.adapter.in.web;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이미 받은 텔레그램 업데이트 — <b>같은 {@code update_id}가 다시 오면 답하지 않는다.</b>
 *
 * <p>웹훅은 받자마자 200을 주지만, 그 200이 텔레그램에 닿지 못하면(네트워크·재배포·잠든 호스트가 깨는 중)
 * 텔레그램이 같은 업데이트를 다시 보낸다. 그때 또 답하면 같은 답이 두 번 간다 — 인스턴스가 둘이면 다른 쪽이
 * 받을 수도 있어 Redis에 둔다.
 *
 * <p><b>Redis를 못 쓰면 가리지 않고 받는다</b>({@code KisTokenStore}의 락과 같은 방향) — 중복 답 한 번이
 * 우리 명령을 놓치는 것보다 덜 나쁘다.
 */
@Component
public class ProcessedUpdates {

    private static final Logger log = LoggerFactory.getLogger(ProcessedUpdates.class);

    private static final String KEY = "telegram:update:";

    /** 텔레그램은 받지 못한 업데이트를 최대 24시간 붙든다 — 그보다 길게 기억할 이유가 없다. */
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;

    /** @param redis {@code null}이면 기억하지 않는다 — 테스트와 Redis 없는 로컬 실행 */
    public ProcessedUpdates(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param updateId 텔레그램 {@code update_id}. 없으면 가릴 수 없으므로 처음으로 본다
     * @return 처음 온 업데이트인가. 같은 번호가 이미 왔으면 {@code false}
     */
    public boolean firstTime(Long updateId) {
        if (updateId == null || redis == null) {
            return true;
        }
        try {
            return !Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(KEY + updateId, "1", TTL));
        } catch (RuntimeException e) {
            log.warn("[webhook] update_id 기록(Redis) 실패 — 중복을 가리지 않고 받습니다: {}", e.toString());
            return true;
        }
    }
}
