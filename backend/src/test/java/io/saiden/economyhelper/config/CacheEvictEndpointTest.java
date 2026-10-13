package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 키 하나를 버리는 엔드포인트 — <b>진단하러 부르는 자리라 「했다」는 답이 사실이어야 한다.</b>
 *
 * <p>운영의 {@code RedisCacheManager}는 <b>모르는 이름에도 캐시를 즉석에서 만든다</b> — 그래서
 * {@code getCache(오타)}가 {@code null}이 아니고, 오타를 낸 호출이 {@code evicted:true}를 받았다.
 * 같은 성질의 {@link ConcurrentMapCacheManager}(이름 없이 만들면 즉석 생성)로 재현한다.
 */
class CacheEvictEndpointTest {

    @Test
    @DisplayName("키를 버리고 했다고 답한다")
    void evictsTheKey() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager();
        manager.getCache("geocode-v2").put("성남시|KR", "좌표");

        Map<String, Object> result = new CacheEvictEndpoint(manager).evict("geocode-v2", "성남시|KR");

        assertThat(result).containsEntry("evicted", true);
        assertThat(manager.getCache("geocode-v2").get("성남시|KR")).isNull();
    }

    @Test
    @DisplayName("없는 이름이면 버렸다고 하지 않는다 — 캐시 관리자가 즉석에서 만들어 null이 안 온다")
    void refusesAnUnknownNameEvenWhenTheManagerCreatesCachesOnDemand() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager();
        manager.getCache("geocode-v2");

        Map<String, Object> result = new CacheEvictEndpoint(manager).evict("geocode-v1", "성남시|KR");

        assertThat(result).containsEntry("evicted", false).containsKey("reason");
        assertThat(manager.getCacheNames())
                .as("오타 하나로 빈 캐시가 생겨 다음 판정까지 속이면 안 된다")
                .containsExactly("geocode-v2");
    }

    @Test
    @DisplayName("운영의 RedisCacheManager도 설정한 캐시를 이름 목록에 올린다 — 판정의 바탕이 실제로 서 있다")
    void redisCacheManagerListsConfiguredCaches() {
        // 연결은 하지 않는다 — 캐시 관리자는 만들 때 Redis에 닿지 않는다
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("localhost", 1);
        org.springframework.data.redis.cache.RedisCacheManager manager =
                org.springframework.data.redis.cache.RedisCacheManager.builder(factory)
                        .withCacheConfiguration("geocode-v2",
                                org.springframework.data.redis.cache.RedisCacheConfiguration.defaultCacheConfig())
                        .build();
        manager.afterPropertiesSet();

        assertThat(manager.getCacheNames()).contains("geocode-v2");
        assertThat(new CacheEvictEndpoint(manager).evict("geocode-v1", "성남시|KR"))
                .containsEntry("evicted", false);
    }

    @Test
    @DisplayName("Redis가 죽어 있으면 500이 아니라 이유를 담아 답한다")
    void reportsAFailedEvictInsteadOfThrowing() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(new ConcurrentMapCache("geocode-v2") {
            @Override
            public void evict(Object key) {
                throw new RedisConnectionFailureException("Unable to connect to localhost:6379");
            }
        }));
        manager.afterPropertiesSet();

        Map<String, Object> result = new CacheEvictEndpoint(manager).evict("geocode-v2", "성남시|KR");

        assertThat(result).containsEntry("evicted", false);
        assertThat(String.valueOf(result.get("reason"))).contains("RedisConnectionFailureException");
    }

    @Test
    @DisplayName("이름이나 키가 비면 통째로 비우지 않는다")
    void refusesWithoutNameOrKey() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager();
        manager.getCache("geocode-v2").put("성남시|KR", "좌표");

        assertThat(new CacheEvictEndpoint(manager).evict("geocode-v2", " ")).containsEntry("evicted", false);
        assertThat(manager.getCache("geocode-v2").get("성남시|KR")).isNotNull();
    }
}
