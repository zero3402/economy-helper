package io.saiden.economyhelper.translate.application;

import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.translate.application.port.out.TranslationCache;
import io.saiden.economyhelper.translate.application.port.out.Translator;
import io.saiden.economyhelper.translate.domain.Translation;
import io.saiden.economyhelper.translate.domain.TranslationRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final Translator gemini;
    private final TranslationCache cache;

    public TranslationService(Translator gemini, TranslationCache cache) {
        this.gemini = gemini;
        this.cache = cache;
    }

    /**
     * 여러 건을 번역한다 — <b>캐시에 없는 것만 묶어 Gemini를 한 번 부른다.</b>
     *
     * <p>왜 묶는가는 {@code GeminiTranslator.translateAll}. <b>캐시를 포기하지 않는다</b> —
     * 키({@link TranslationRequest#key}, 뉴스는 기사 링크) 단위 캐시가 무료 티어를 아끼는 가장 큰
     * 수단이라, 캐시를 먼저 훑고 <b>빈 자리만</b> 묶어 부른 뒤 결과를 키별로 되돌려 넣는다.
     *
     * <p><b>실패해도 캐시에서 건진 것은 그대로 남는다</b> — 한 번의 429로 이미 가진 번역까지
     * 잃을 이유가 없다. 미스만 원문으로 강등된다.
     *
     * @return 입력과 같은 순서, 같은 개수
     */
    public List<Translation> translateAll(List<TranslationRequest> articles) {
        Map<String, Translation> known = new HashMap<>();
        List<TranslationRequest> misses = new ArrayList<>();
        for (TranslationRequest article : articles) {
            cache.find(article.key()).ifPresentOrElse(
                    cached -> known.put(article.key(), cached),
                    () -> misses.add(article));
        }

        if (!misses.isEmpty()) {
            putAll(misses, translateMisses(misses), known);
        }

        List<Translation> ordered = new ArrayList<>(articles.size());
        for (TranslationRequest article : articles) {
            ordered.add(known.get(article.key()));
        }
        return List.copyOf(ordered);
    }

    /** 실패는 여기서 값으로 바꾼다 — 미스가 원문으로 내려갈 뿐 발송은 멈추지 않는다. */
    private List<Translation> translateMisses(List<TranslationRequest> misses) {
        try {
            return gemini.translateAll(misses);
        } catch (Exception e) {
            log.warn("[translate] {}건 묶음 번역 실패 — 원문 그대로 내보냅니다: {}",
                    misses.size(), FailureReason.of(e));
            return misses.stream().map(Translation::untranslated).toList();
        }
    }

    /**
     * 새로 번역한 것만 캐시에 넣는다.
     *
     * <p><b>강등된 결과는 넣지 않는다.</b>
     * 일시적 429 때문에 영문 원문이 7일간 굳으면 그 기간 내내 번역 없이 나간다.
     */
    private void putAll(List<TranslationRequest> misses, List<Translation> fresh,
                        Map<String, Translation> into) {
        for (int i = 0; i < misses.size(); i++) {
            Translation translation = fresh.get(i);
            into.put(misses.get(i).key(), translation);
            if (translation.translated()) {
                cache.put(misses.get(i).key(), translation);
            }
        }
    }

}
