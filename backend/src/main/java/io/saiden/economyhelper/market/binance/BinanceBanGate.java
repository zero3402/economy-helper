package io.saiden.economyhelper.market.binance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 바이낸스가 우리 IP를 밴한 동안 <b>호출을 한 번도 내지 않게</b> 막는 문.
 *
 * <p><b>왜 브레이커로는 부족한가.</b> {@code binance} 브레이커는 기본값
 * ({@code slidingWindowSize 10} · {@code failureRateThreshold 50})을 물려받으므로
 * 418 하나로는 열리지 않는다 — <b>다섯 번을 더 맞아야</b> 열린다. 그런데 바이낸스는
 * <b>밴 중의 호출이 밴을 연장한다.</b> 즉 브레이커가 열릴 때까지 우리가 스스로 밴을 늘린다.
 * 게다가 반열림이 150초마다 <b>세 번</b>을 더 찔러 보는데, 밴은 2분에서 3일까지 간다.
 *
 * <p>그래서 상태를 직접 든다. 첫 418에 문이 닫히고, 그 뒤로는 HTTP가 <b>0회</b> 나간다.
 *
 * <p><b>{@code Retry-After}를 읽는다.</b> 바이낸스가 언제 풀리는지 직접 말해 주는데
 * 우리는 그동안 그걸 버리고 있었다. 없을 때만 최소값으로 넘겨짚는다.
 *
 * <p><b>Redis와 프로세스 사본을 함께 든다.</b> 한도가 IP 단위라 같은 이그레스를 쓰는
 * 인스턴스가 함께 물러서야 하므로 Redis가 필요하고 — 그런데 Redis가 죽었을 때
 * "기억 못 하니 그냥 부른다"로 떨어지면 <b>정확히 밴을 늘리는 쪽</b>으로 고장 난다.
 * 그래서 둘 중 <b>늦은 쪽</b>을 믿는다({@code KisTokenStore}가 발급이 비싸서 프로세스 사본을
 * 함께 두는 것과 같은 판단이다 — 밴도 비싸다).
 */
@Component
public class BinanceBanGate {

    private static final Logger log = LoggerFactory.getLogger(BinanceBanGate.class);

    private static final String KEY = "binance:ban-until";

    /**
     * {@code Retry-After}가 없을 때의 418 기본 대기.
     *
     * <p>바이낸스가 문서로 말하는 <b>최소 밴이 2분</b>이다. 짧게 잡으면 밴이 안 풀린 채로
     * 다시 찔러 밴을 늘리고, 길게 잡으면 이미 풀린 뒤에도 코인 칸이 비어 있다 —
     * 넘겨짚는 값은 <b>상대가 말한 최소값</b>이 가장 덜 틀린다.
     */
    static final Duration MIN_BAN = Duration.ofMinutes(2);

    /** 429는 밴이 아니라 경고다. 짧게 물러섰다가 돌아온다 — 여기서 계속 부르면 418이 된다. */
    static final Duration WARNING_BACKOFF = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final Clock clock;

    /** 프로세스 사본. Redis가 죽어도 이 인스턴스만은 물러선다. */
    private volatile Instant localUntil = Instant.EPOCH;

    /**
     * @param redis 밴 시각을 나눠 가질 곳. <b>테스트에서는 {@code null}</b>이고 그때는
     *              프로세스 사본만 쓴다 — Redis가 죽었을 때와 같은 경로라 따로 다룰 것이 없다
     */
    public BinanceBanGate(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** @return 아직 밴 중이면 풀리는 시각. 아니면 {@code null} */
    Instant bannedUntil() {
        Instant until = later(localUntil, fromRedis());
        return until.isAfter(clock.instant()) ? until : null;
    }

    /**
     * 밴을 기록한다. <b>줄이지 않는다</b> — 이미 더 늦게 풀리기로 돼 있으면 그대로 둔다.
     *
     * <p>줄이면 짧은 429 하나가 앞선 418을 지워 그 순간 다시 찌르게 된다.
     */
    void ban(Duration duration) {
        Instant until = clock.instant().plus(duration);
        if (until.isAfter(localUntil)) {
            localUntil = until;
        }
        if (redis == null) {
            return;
        }
        try {
            Instant shared = later(until, fromRedis());
            Duration ttl = Duration.between(clock.instant(), shared).plusMinutes(1);
            redis.opsForValue().set(KEY, String.valueOf(shared.toEpochMilli()), ttl);
        } catch (RuntimeException e) {
            log.warn("[crypto] 바이낸스 밴 시각을 공유하지 못했습니다 — 이 인스턴스만 물러섭니다: {}",
                    e.toString());
        }
    }

    private Instant fromRedis() {
        if (redis == null) {
            return Instant.EPOCH;
        }
        try {
            String stored = redis.opsForValue().get(KEY);
            return stored == null ? Instant.EPOCH : Instant.ofEpochMilli(Long.parseLong(stored));
        } catch (RuntimeException e) {
            // 못 읽어도 프로세스 사본이 남아 있다. 여기서 예외를 올리면 밴이 아닌 실패까지 만든다
            log.warn("[crypto] 바이낸스 밴 시각을 읽지 못했습니다: {}", e.toString());
            return Instant.EPOCH;
        }
    }

    private static Instant later(Instant one, Instant other) {
        return one.isAfter(other) ? one : other;
    }
}
