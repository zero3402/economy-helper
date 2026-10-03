package io.saiden.economyhelper.translate.adapter.out.cache;

import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.translate.application.port.out.TranslationCache;
import io.saiden.economyhelper.translate.domain.Translation;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * {@link TranslationCache}를 스프링 캐시({@link CacheNames#TRANSLATION})로.
 *
 * <p>캐시가 등록돼 있지 않으면 늘 미스이고 담지 않는다 — 번역은 캐시 없이도 나간다.
 */
@Component
public class SpringTranslationCache implements TranslationCache {

    /**
     * 캐시 이름.
     *
     * <p>⚠️ <b>공개해 둔다.</b> {@code CacheConfigTest}가 {@code @Cacheable}을 훑어 캐시 등록 누락을
     * 잡는데, 이 캐시는 애너테이션이 없어 그 그물에 안 걸린다 — 테스트가 이 상수를 직접 읽어 목록에 더한다.
     */
    public static final String CACHE = CacheNames.TRANSLATION;

    private static final Logger log = LoggerFactory.getLogger(SpringTranslationCache.class);

    private final CacheManager cacheManager;

    public SpringTranslationCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * ⚠️ 캐시를 직접 부르므로 {@code CacheErrorConfig}의 처리기를 안 탄다 — Redis 장애를 여기서 미스로 바꾼다.
     */
    @Override
    public Optional<Translation> find(String key) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(cache.get(key, Translation.class));
        } catch (RuntimeException e) {
            log.warn("[cache] {} 읽기 실패 — 캐시 없이 번역합니다: {}", CACHE, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /** 담기 실패는 건너뛴다 — 번역은 이미 나왔다. */
    @Override
    public void put(String key, Translation translation) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache == null) {
            return;
        }
        try {
            cache.put(key, translation);
        } catch (RuntimeException e) {
            log.warn("[cache] {} 쓰기 실패 — 담지 않습니다: {}", CACHE, FailureReason.of(e));
        }
    }
}
