package io.saiden.economyhelper.config;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 읽기마다 <b>히트·미스를 INFO 한 줄로</b> 남긴다 — 캐시가 값을 하는지, 판 번호를 올린 뒤
 * 다시 차는지를 운영 로그에서 바로 본다.
 *
 * <p>{@code CacheManager}를 통째로 감싸므로 {@code @Cacheable}과 {@code getCache}로 직접 읽는 곳
 * (번역·거래소 캐시)이 같은 줄을 남긴다. 실패는 여기서 남기지 않는다 — 예외는 그대로 던지고
 * {@link CacheErrorConfig}가 삼키며 이유를 적는다.
 *
 * <p><b>키는 남기지 않는다</b> — 검색어가 그대로 키라서 그룹 대화가 로그로 흘러든다
 * ({@link CacheErrorConfig}와 같은 이유).
 */
@Configuration(proxyBeanMethods = false)
public class CacheHitLog {

    private static final Logger log = LoggerFactory.getLogger(CacheHitLog.class);

    /** static이어야 한다 — 후처리기가 설정 빈을 일찍 끌어오면 그 빈이 다른 후처리를 못 받는다. */
    @Bean
    static BeanPostProcessor cacheHitLogPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof CacheManager manager && !(bean instanceof LoggingCacheManager)
                        ? decorate(manager)
                        : bean;
            }
        };
    }

    static CacheManager decorate(CacheManager manager) {
        return new LoggingCacheManager(manager);
    }

    private static void logHit(Cache cache, boolean hit) {
        log.info("[cache] {} {}", cache.getName(), hit ? "히트" : "미스");
    }

    record LoggingCacheManager(CacheManager delegate) implements CacheManager {

        @Override
        public Cache getCache(String name) {
            Cache cache = delegate.getCache(name);
            return cache == null ? null : new LoggingCache(cache);
        }

        @Override
        public Collection<String> getCacheNames() {
            return delegate.getCacheNames();
        }
    }

    /** 읽기만 지켜본다. 나머지는 그대로 넘긴다. */
    record LoggingCache(Cache delegate) implements Cache {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            ValueWrapper found = delegate.get(key);
            logHit(delegate, found != null);
            return found;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            T found = delegate.get(key, type);
            logHit(delegate, found != null);
            return found;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            boolean[] loaded = {false};
            T value = delegate.get(key, () -> {
                loaded[0] = true;
                return valueLoader.call();
            });
            logHit(delegate, !loaded[0]);
            return value;
        }

        @Override
        public CompletableFuture<?> retrieve(Object key) {
            return delegate.retrieve(key);
        }

        @Override
        public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
            return delegate.retrieve(key, valueLoader);
        }

        @Override
        public void put(Object key, Object value) {
            delegate.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key);
        }

        @Override
        public boolean evictIfPresent(Object key) {
            return delegate.evictIfPresent(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public boolean invalidate() {
            return delegate.invalidate();
        }
    }
}
