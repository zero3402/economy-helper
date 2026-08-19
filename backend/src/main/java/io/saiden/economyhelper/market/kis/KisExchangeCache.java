package io.saiden.economyhelper.market.kis;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 미국 심볼이 <b>어느 거래소에 있는지</b>를 기억한다.
 *
 * <p><b>왜 필요한가.</b> KIS는 미국 종목을 조회할 때 거래소 코드({@code EXCD})를 요구하는데
 * 사용자도 LLM도 그걸 주지 않는다. 그래서 나스닥부터 물어보고 비면 뉴욕을 물어보는데
 * ({@code KisStockApi.usStock}) 그 탐색이 조회 한 번을 <b>두 번</b>으로 만든다.
 * 초당 1건 한도라 그 한 번이 곧 1초다.
 *
 * <p><b>거래소는 바뀌지 않는다.</b> 한 번 찾으면 그 뒤로는 물어볼 것이 없다 —
 * 그래서 30일을 잡는다({@code accu-location}이 좌표→지점 키를 30일 잡는 것과 같은 성질이다).
 *
 * <p><b>{@code @Cacheable}을 쓰지 않는다.</b> 그건 "부르면 계산해서 담는" 모양인데 여기는
 * 값을 <b>찾아낸 뒤에 담는</b> 자리다. {@code KisTokenStore}·{@code FmpQuotaGuard}가 같은
 * 이유로 {@code StringRedisTemplate}을 직접 쓴다.
 *
 * <p><b>Redis가 죽어도 조회는 된다.</b> 못 읽으면 탐색으로 돌아갈 뿐이고, 못 쓰면 다음에
 * 다시 탐색할 뿐이다 — 그래서 여기서는 예외를 삼킨다. 이 캐시는 정확성이 아니라 속도다.
 */
@Component
public class KisExchangeCache {

    private static final Logger log = LoggerFactory.getLogger(KisExchangeCache.class);

    private static final String PREFIX = "kis:excd:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;

    public KisExchangeCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 기억해 둔 거래소 코드. 없거나 못 읽으면 {@code null} — 그러면 탐색한다 */
    String of(String symbol) {
        try {
            return redis.opsForValue().get(PREFIX + symbol);
        } catch (RuntimeException e) {
            log.warn("[kis] 거래소 캐시 조회 실패 — 탐색으로 갑니다: {}", e.toString());
            return null;
        }
    }

    void remember(String symbol, String exchange) {
        try {
            redis.opsForValue().set(PREFIX + symbol, exchange, TTL);
        } catch (RuntimeException e) {
            log.warn("[kis] 거래소 캐시 저장 실패 — 다음에 다시 탐색합니다: {}", e.toString());
        }
    }
}
