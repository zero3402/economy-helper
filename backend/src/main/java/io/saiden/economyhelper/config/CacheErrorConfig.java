package io.saiden.economyhelper.config;

import io.saiden.economyhelper.shared.support.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시가 던지면 로그만 남기고 캐시 없이 간다.
 *
 * <p>기본 처리기는 그대로 던진다 — Redis가 끊기면 {@code @Cacheable}이 붙은 모든 조회가 실패해
 * <b>출처는 멀쩡한데 모든 명령이 「잠시 후」로 나간다.</b> 캐시는 덧붙임이지 출처가 아니다.
 * 읽기 실패는 미스로, 쓰기·비우기 실패는 건너뛰기로 본다.
 *
 * <p>⚠️ 대가: Redis가 죽은 동안은 캐시가 한도를 막아 주지 않는다 — 리미터·브레이커가 그 몫을 맡는다.
 * 저장된 JSON이 지금 타입과 안 맞아 읽기가 깨지는 것도 여기서 미스가 된다(판 번호 규칙이 1차 방어다).
 */
@Configuration
public class CacheErrorConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheErrorConfig.class);

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                warn("읽기", cache, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                warn("쓰기", cache, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                warn("지우기", cache, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                warn("비우기", cache, e);
            }
        };
    }

    /** 키는 남기지 않는다 — 검색어가 그대로 키라서 그룹 대화가 로그로 흘러든다. */
    private static void warn(String action, Cache cache, RuntimeException e) {
        log.warn("[cache] {} {} 실패 — 캐시 없이 갑니다: {}", cache.getName(), action, FailureReason.of(e));
    }
}
