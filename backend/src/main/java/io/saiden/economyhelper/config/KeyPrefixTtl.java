package io.saiden.economyhelper.config;

import java.time.Duration;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * 키 접두사로 수명을 가른다 — 한 캐시 이름에 성질이 다른 값이 섞여 있을 때.
 *
 * <p>{@code stock-series}는 국내 종목({@code stock:})·국내 지수({@code index:})·미국({@code us:})이 한 이름을 쓴다
 * (담기는 타입이 {@code List<DailyBar>} 하나라 섞여도 된다). 그런데 <b>미국 종목 일봉만 진행 중인 오늘 봉을 담는다</b>
 * ({@code dailyprice}가 오늘 행을 준다 — ADR-0007). 국내는 시세 호출이 매번 {@code output2}로 다시 채우지만
 * 미국은 12시간 그대로라, 차트 caption의 끝값이 1분 시세와 최대 12시간 어긋난다 — 한 종목 값이 한 통에 두 개 찍히는
 * 모양(ADR-0007이 {@code MODP=1}에서 거절한 그것). 그래서 {@code us:}만 뉴욕 자정까지로 줄인다({@link UntilMidnightTtl}) —
 * 자정이 지나면 그 봉은 굳고 다음 조회가 새 하루로 받는다. 어긋남이 사라지는 것은 아니고 창이 그날 안으로 줄어드는 것이다.
 *
 * <p>키는 {@code @Cacheable}이 SpEL로 만든 객체 <b>그대로</b> 온다 — {@code RedisCache.put}이 바이트로 바꾸기 전의
 * 키를 넘긴다(spring-data-redis 4.1.0). 문자열이 아니면 {@code otherwise}다.
 */
final class KeyPrefixTtl implements RedisCacheWriter.TtlFunction {

    private final String prefix;
    private final RedisCacheWriter.TtlFunction matched;
    private final RedisCacheWriter.TtlFunction otherwise;

    KeyPrefixTtl(String prefix, RedisCacheWriter.TtlFunction matched, RedisCacheWriter.TtlFunction otherwise) {
        this.prefix = prefix;
        this.matched = matched;
        this.otherwise = otherwise;
    }

    @Override
    public Duration getTimeToLive(Object key, Object value) {
        return key instanceof String text && text.startsWith(prefix)
                ? matched.getTimeToLive(key, value)
                : otherwise.getTimeToLive(key, value);
    }
}
