package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.news.Article;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * 번역의 단일 진입점 — 캐시 → Gemini → 원문 강등.
 *
 * <p>폴백 판단을 여기 모아 둔 이유는 번역기가 스스로 원문을 돌려주면
 * 실패가 조용히 묻히기 때문이다. 어디서 강등이 일어나는지 한 곳에서 보여야 한다.
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /**
     * 번역 캐시의 이름.
     *
     * <p><b>{@code @Cacheable}이 아니라 {@link CacheManager}로 직접 읽고 쓴다.</b> 캐시에 없는
     * 것만 골라 한 번에 묶어 부르려면 "무엇이 미스인지"를 알아야 하는데, 애너테이션은 메서드
     * 하나를 통째로 감싸므로 그 판단을 할 자리가 없다.
     *
     * <p>⚠️ 그래서 <b>공개해 둔다.</b> {@code CacheConfigTest}가 {@code @Cacheable}을 훑어
     * 캐시 등록 누락을 잡는데, 이 캐시는 애너테이션이 없어 그 그물에 안 걸린다 —
     * 테스트가 이 상수를 직접 읽어 목록에 더한다.
     */
    public static final String CACHE = "translation";

    private final GeminiTranslator gemini;
    private final CacheManager cacheManager;

    public TranslationService(GeminiTranslator gemini, CacheManager cacheManager) {
        this.gemini = gemini;
        this.cacheManager = cacheManager;
    }

    /**
     * 여러 건을 번역한다 — <b>캐시에 없는 것만 묶어 Gemini를 한 번 부른다.</b>
     *
     * <p><b>왜 묶는가.</b> 건별로 부르면 호출이
     * 건수만큼 늘고, 번역은 관련도 채점 다음에 오므로 <b>리미터가 소진됐을 때 잘려 나가는 쪽이
     * 정확히 번역</b>이었다. 그게 "번역이 일시적으로 불가"의 원인이다.
     *
     * <p><b>캐시를 포기하지 않는다.</b> 목록 전체를 그냥 묶으면 이미 번역한 기사까지 다시 태운다 —
     * 링크 단위 캐시가 무료 티어를 아끼는 가장 큰 수단이라 버릴 수 없다. 그래서 캐시를 먼저 훑고
     * <b>빈 자리만</b> 묶어 부른 뒤 결과를 링크별로 되돌려 넣는다.
     *
     * <p><b>실패해도 캐시에서 건진 것은 그대로 남는다</b> — 한 번의 429로 이미 가진 번역까지
     * 잃을 이유가 없다. 미스만 원문으로 강등된다.
     *
     * @return 입력과 같은 순서, 같은 개수
     */
    public List<Translation> translateAll(List<Article> articles) {
        Cache cache = cacheManager.getCache(CACHE);
        Map<String, Translation> known = new HashMap<>();
        List<Article> misses = new ArrayList<>();
        for (Article article : articles) {
            Translation cached = cache == null ? null : cache.get(article.link(), Translation.class);
            if (cached == null) {
                misses.add(article);
            } else {
                known.put(article.link(), cached);
            }
        }

        if (!misses.isEmpty()) {
            putAll(cache, misses, translateMisses(misses), known);
        }

        List<Translation> ordered = new ArrayList<>(articles.size());
        for (Article article : articles) {
            ordered.add(known.get(article.link()));
        }
        return List.copyOf(ordered);
    }

    /** 실패는 여기서 값으로 바꾼다 — 미스가 원문으로 내려갈 뿐 발송은 멈추지 않는다. */
    private List<Translation> translateMisses(List<Article> misses) {
        try {
            return gemini.translateAll(misses);
        } catch (Exception e) {
            log.warn("[translate] {}건 묶음 번역 실패 — 원문 그대로 내보냅니다: {}",
                    misses.size(), e.toString());
            return misses.stream().map(Translation::untranslated).toList();
        }
    }

    /**
     * 새로 번역한 것만 캐시에 넣는다.
     *
     * <p><b>강등된 결과는 넣지 않는다.</b>
     * 일시적 429 때문에 영문 원문이 7일간 굳으면 그 기간 내내 번역 없이 나간다.
     */
    private static void putAll(Cache cache, List<Article> misses, List<Translation> fresh,
                               Map<String, Translation> into) {
        for (int i = 0; i < misses.size(); i++) {
            Translation translation = fresh.get(i);
            into.put(misses.get(i).link(), translation);
            if (cache != null && translation.translated()) {
                cache.put(misses.get(i).link(), translation);
            }
        }
    }

}
