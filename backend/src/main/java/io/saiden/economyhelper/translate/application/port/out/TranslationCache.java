package io.saiden.economyhelper.translate.application.port.out;

import io.saiden.economyhelper.translate.domain.Translation;
import java.util.Optional;

/**
 * 키({@code TranslationRequest.key}) 단위 번역 캐시.
 *
 * <p>{@code @Cacheable}이 아니라 포트인 이유: 캐시에 없는 것만 골라 한 번에 묶어 번역하려면
 * 「무엇이 미스인가」를 {@code TranslationService}가 알아야 한다. 애너테이션은 메서드 하나를
 * 통째로 감싸므로 그 판단을 할 자리가 없다.
 */
public interface TranslationCache {

    Optional<Translation> find(String key);

    /** 강등된(원문 그대로인) 결과는 넣지 않는다 — 그 판단은 호출자가 한다. */
    void put(String key, Translation translation);
}
