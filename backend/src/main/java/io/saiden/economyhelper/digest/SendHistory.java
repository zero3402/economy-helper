package io.saiden.economyhelper.digest;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이미 발송한 시간대를 Redis에 표시해 같은 슬롯이 두 번 나가지 않게 한다.
 *
 * <p><b>ShedLock과 역할이 다르다.</b> ShedLock은 <i>동시 실행</i>을 막는다 — 두 인스턴스가
 * 같은 순간에 잡을 도는 것만 막을 뿐, 한쪽 시계가 크게 밀려 락이 이미 풀린 뒤에 도는 것은
 * 막지 못한다. 이 클래스는 <b>실행 시각이 아니라 예정된 슬롯</b>(KST 날짜 + 시)으로 키를 잡으므로
 * 시계가 얼마나 밀렸든 "8월 11일 09시 발송"은 한 번뿐이다. 수동 트리거에도 같은 보호가 걸린다.
 *
 * <p>Redis가 죽으면 예외가 그대로 올라간다. 삼키고 발송하면 replicas 2에서 중복이 나가는데,
 * 중복 발송보다는 한 번 거르는 쪽이 낫다. 어차피 ShedLock도 같은 Redis를 쓴다.
 */
@Component
public class SendHistory {

    private static final String KEY_PREFIX = "digest:sent:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SendHistory(StringRedisTemplate redis, EconomyHelperProperties properties) {
        this.redis = redis;
        this.ttl = properties.digest().sentHistoryTtl();
    }

    /**
     * 슬롯을 선점한다. {@code SET NX EX} 한 번이라 여러 인스턴스가 동시에 불러도
     * 참을 받는 쪽은 하나뿐이다.
     *
     * @return 이 호출이 슬롯을 차지했으면 {@code true}, 이미 누가 보냈으면 {@code false}
     */
    public boolean claim(String slot) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + slot, "1", ttl));
    }

    /**
     * 선점을 되돌린다 — 발송이 실패했을 때만 부른다.
     *
     * <p>보낸 적 없는 슬롯을 "보냄"으로 남겨 두면 그 시간대는 영영 복구되지 않는다.
     * 되돌린 결과 아주 드물게 중복이 나갈 수는 있지만, 뉴스 다이제스트에서는
     * 중복 한 번이 통째로 빠지는 것보다 낫다.
     */
    public void release(String slot) {
        redis.delete(KEY_PREFIX + slot);
    }
}
