package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 캐시가 값을 했는지 <b>로그 한 줄로 보여야 한다.</b> 실패는 {@code CacheErrorConfig}가 남기고,
 * 여기서는 히트·미스를 남긴다. 키는 남기지 않는다 — 검색어가 그대로 키다.
 */
@ExtendWith(OutputCaptureExtension.class)
class CacheHitLogTest {

    @Test
    @DisplayName("@Cacheable이 처음엔 미스, 다음엔 히트로 남는다 — 키(검색어)는 남기지 않는다")
    void logsMissThenHitWithoutKey(CapturedOutput output) {
        try (var context = new AnnotationConfigApplicationContext(Setup.class)) {
            Quoted quoted = context.getBean(Quoted.class);

            quoted.price("삼성전자");
            quoted.price("삼성전자");

            assertThat(quoted.calls()).isEqualTo(1);
            assertThat(output).contains("[cache] quote 미스", "[cache] quote 히트")
                    .doesNotContain("삼성전자");
        }
    }

    @Test
    @DisplayName("직접 꺼내 쓰는 캐시도 같은 장식을 받는다 — CacheManager.getCache로 읽는 번역·거래소 캐시")
    void decoratesProgrammaticAccess(CapturedOutput output) {
        CacheManager manager = CacheHitLog.decorate(new ConcurrentMapCacheManager("translation"));

        Cache cache = manager.getCache("translation");
        assertThat(cache.get("k", String.class)).isNull();
        cache.put("k", "v");
        assertThat(cache.get("k", String.class)).isEqualTo("v");

        assertThat(output).contains("[cache] translation 미스", "[cache] translation 히트");
        assertThat(manager.getCacheNames()).containsExactly("translation");
    }

    @Test
    @DisplayName("값을 계산해 채운 get(key, loader)는 미스, 이미 있던 값은 히트")
    void loaderVariantTellsHitFromMiss(CapturedOutput output) {
        Cache cache = new CacheHitLog.LoggingCache(new ConcurrentMapCache("quote"));

        assertThat(cache.get("k", () -> "v")).isEqualTo("v");
        assertThat(output).contains("[cache] quote 미스").doesNotContain("[cache] quote 히트");

        assertThat(cache.get("k", () -> "other")).isEqualTo("v");
        assertThat(output).contains("[cache] quote 히트");
    }

    @Test
    @DisplayName("읽기 실패는 그대로 던진다 — 삼키고 이유를 남기는 것은 CacheErrorConfig의 일이다")
    void rethrowsFailures() {
        Cache broken = new ConcurrentMapCache("quote") {
            @Override
            public ValueWrapper get(Object key) {
                throw new IllegalStateException("redis down");
            }
        };

        assertThatThrownBy(() -> new CacheHitLog.LoggingCache(broken).get("k"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Configuration
    @EnableCaching
    @Import(CacheHitLog.class)
    static class Setup {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("quote");
        }

        @Bean
        Quoted quoted() {
            return new Quoted();
        }
    }

    static class Quoted {
        private final AtomicInteger calls = new AtomicInteger();

        // 프록시 너머의 필드는 비어 있다 — 메서드로 읽는다
        public int calls() {
            return calls.get();
        }

        @Cacheable("quote")
        public String price(String query) {
            return query + "=" + calls.incrementAndGet();
        }
    }
}
