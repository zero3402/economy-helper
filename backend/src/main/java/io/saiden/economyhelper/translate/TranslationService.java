package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.news.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 번역의 단일 진입점 — 캐시 → Gemini → 원문 강등.
 *
 * <p>폴백 판단을 여기 모아 둔 이유는 {@link Translator} 구현체가 스스로 원문을 돌려주면
 * 실패가 조용히 묻히기 때문이다. 어디서 강등이 일어나는지 한 곳에서 보여야 한다.
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final GeminiTranslator gemini;
    private final PassthroughTranslator passthrough;

    public TranslationService(GeminiTranslator gemini, PassthroughTranslator passthrough) {
        this.gemini = gemini;
        this.passthrough = passthrough;
    }

    /**
     * 링크로 캐시한다 — 같은 기사를 두 번 번역하지 않는 게 무료 티어를 아끼는 가장 큰 수단이다.
     *
     * <p>강등된 결과는 캐시하지 않는다({@code unless}). 일시적 429 때문에 영문 원문이
     * 7일간 굳으면 그 기간 내내 번역 없이 나간다.
     */
    @Cacheable(cacheNames = "translation", key = "#article.link()", unless = "!#result.translated()")
    public Translation translate(Article article) {
        try {
            return gemini.translate(article);
        } catch (Exception e) {
            log.warn("[{}] 번역 실패 — 원문 그대로 내보냅니다: {}",
                    article.source(), e.toString());
            return passthrough.translate(article);
        }
    }
}
