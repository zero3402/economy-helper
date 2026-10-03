package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.translate.adapter.out.cache.SpringTranslationCache;
import io.saiden.economyhelper.translate.domain.Translation;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Redis가 죽어도 캐시가 답을 막지 않는다 — 캐시는 덧붙임이지 출처가 아니다.
 */
class CacheErrorTolerantTest {

    @Test
    @DisplayName("@Cacheable의 읽기·쓰기가 던져도 메서드를 불러 답한다 — Redis 장애가 모든 명령을 실패시키던 것")
    void cacheableSurvivesBrokenCache() {
        try (var context = new AnnotationConfigApplicationContext(Setup.class)) {
            Quoted quoted = context.getBean(Quoted.class);

            assertThat(quoted.price("BTC")).isEqualTo("BTC=1");
            assertThat(quoted.price("BTC")).as("캐시가 없으니 다시 부른다").isEqualTo("BTC=2");
        }
    }

    @Test
    @DisplayName("번역 캐시가 던져도 미스로 보고 담기는 조용히 건너뛴다")
    void translationCacheSurvivesBrokenCache() {
        SpringTranslationCache cache = new SpringTranslationCache(new BrokenCacheManager());

        assertThat(cache.find("key")).isEmpty();
        cache.put("key", new Translation("제목", "본문", true));
    }

    @Configuration
    @EnableCaching
    @Import(CacheErrorConfig.class)
    static class Setup {

        @Bean
        CacheManager cacheManager() {
            return new BrokenCacheManager();
        }

        @Bean
        Quoted quoted() {
            return new Quoted();
        }
    }

    static class Quoted {

        private final AtomicInteger calls = new AtomicInteger();

        @Cacheable("broken")
        public String price(String symbol) {
            return symbol + "=" + calls.incrementAndGet();
        }
    }

    /** 이름마다 읽기·쓰기에서 던지는 캐시를 준다 — 연결이 끊긴 Redis 모양이다. */
    static class BrokenCacheManager implements CacheManager {

        @Override
        public Cache getCache(String name) {
            return new AbstractValueAdaptingCache(true) {
                @Override
                protected Object lookup(Object key) {
                    throw new IllegalStateException("Redis 연결 끊김");
                }

                @Override
                public String getName() {
                    return name;
                }

                @Override
                public Object getNativeCache() {
                    return this;
                }

                @Override
                public <T> T get(Object key, Callable<T> valueLoader) {
                    throw new IllegalStateException("Redis 연결 끊김");
                }

                @Override
                public void put(Object key, Object value) {
                    throw new IllegalStateException("Redis 연결 끊김");
                }

                @Override
                public void evict(Object key) {
                    throw new IllegalStateException("Redis 연결 끊김");
                }

                @Override
                public void clear() {
                    throw new IllegalStateException("Redis 연결 끊김");
                }
            };
        }

        @Override
        public Collection<String> getCacheNames() {
            return List.of();
        }
    }
}
