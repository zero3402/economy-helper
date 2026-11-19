package io.saiden.economyhelper.translate.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class SpringSearchTermCacheTest {

    @Test
    @DisplayName("담은 표현들을 토큰으로 다시 읽는다")
    void readsBackWhatWasStored() {
        SpringSearchTermCache cache =
                new SpringSearchTermCache(new ConcurrentMapCacheManager(SpringSearchTermCache.CACHE));

        cache.put("비트코인", List.of("bitcoin", "btc"));

        assertThat(cache.find("비트코인")).contains(List.of("bitcoin", "btc"));
        assertThat(cache.find("금리")).isEmpty();
    }

    @Test
    @DisplayName("캐시가 등록돼 있지 않으면 늘 미스이고 담지 않는다 — 검색은 캐시 없이도 나간다")
    void missesWhenTheCacheIsNotRegistered() {
        SpringSearchTermCache cache = new SpringSearchTermCache(new NoCaches());

        cache.put("비트코인", List.of("bitcoin"));

        assertThat(cache.find("비트코인")).isEmpty();
    }

    @Test
    @DisplayName("Redis가 읽기·쓰기에서 던져도 미스로 바꾼다 — 캐시가 죽어도 답은 나간다")
    void turnsCacheFailuresIntoMisses() {
        CacheManager broken = new ConcurrentMapCacheManager(SpringSearchTermCache.CACHE) {
            @Override
            protected Cache createConcurrentMapCache(String name) {
                return new ConcurrentMapCache(name) {
                    @Override
                    public <T> T get(Object key, Class<T> type) {
                        throw new IllegalStateException("Redis 연결이 끊겼습니다");
                    }

                    @Override
                    public void put(Object key, Object value) {
                        throw new IllegalStateException("Redis 연결이 끊겼습니다");
                    }
                };
            }
        };
        SpringSearchTermCache cache = new SpringSearchTermCache(broken);

        cache.put("비트코인", List.of("bitcoin"));

        assertThat(cache.find("비트코인")).isEmpty();
    }

    /** 어떤 이름도 등록돼 있지 않은 관리자. */
    private static final class NoCaches implements CacheManager {

        @Override
        public Cache getCache(String name) {
            return null;
        }

        @Override
        public Collection<String> getCacheNames() {
            return List.of();
        }
    }
}
