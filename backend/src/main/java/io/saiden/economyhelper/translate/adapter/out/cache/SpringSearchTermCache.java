package io.saiden.economyhelper.translate.adapter.out.cache;

import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.translate.application.port.out.SearchTermCache;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * {@link SearchTermCache}를 스프링 캐시({@link CacheNames#QUERY})로 — {@link SpringTranslationCache}와 같은 모양이다.
 *
 * <p>캐시가 등록돼 있지 않으면 늘 미스이고 담지 않는다 — 검색은 캐시 없이도 나간다.
 */
@Component
public class SpringSearchTermCache implements SearchTermCache {

    /**
     * 캐시 이름.
     *
     * <p>⚠️ <b>공개해 둔다.</b> 애너테이션이 없어 {@code CacheConfigTest}의 그물에 안 걸린다 — 테스트가 이 상수를
     * 직접 읽어 등록 누락을 잡는다.
     */
    public static final String CACHE = CacheNames.QUERY;

    private static final Logger log = LoggerFactory.getLogger(SpringSearchTermCache.class);

    private final CacheManager cacheManager;

    public SpringSearchTermCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * ⚠️ 캐시를 직접 부르므로 {@code CacheErrorConfig}의 처리기를 안 탄다 — Redis 장애를 여기서 미스로 바꾼다.
     * 빈 목록은 담지 않으므로 읽힌 빈 목록도 미스다.
     */
    @Override
    public Optional<List<String>> find(String token) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> terms = cache.get(token, List.class);
            return terms == null || terms.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(terms));
        } catch (RuntimeException e) {
            log.warn("[cache] {} 읽기 실패 — 캐시 없이 번역합니다: {}", CACHE, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /** 담기 실패는 건너뛴다 — 번역은 이미 나왔다. */
    @Override
    public void put(String token, List<String> terms) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache == null) {
            return;
        }
        try {
            cache.put(token, terms);
        } catch (RuntimeException e) {
            log.warn("[cache] {} 쓰기 실패 — 담지 않습니다: {}", CACHE, FailureReason.of(e));
        }
    }
}
