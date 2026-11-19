package io.saiden.economyhelper.translate.application.port.out;

import java.util.List;
import java.util.Optional;

/**
 * 토큰 단위 검색어 번역 캐시.
 *
 * <p>{@code @Cacheable}이 아니라 포트인 이유는 {@link TranslationCache}와 같다 — 캐시에 없는 토큰만 골라
 * 한 번에 묶어 물으려면 「무엇이 미스인가」를 {@code QueryTranslationService}가 알아야 한다.
 */
public interface SearchTermCache {

    Optional<List<String>> find(String token);

    /** 못 옮긴 토큰은 넣지 않는다 — 그 판단은 호출자가 한다. */
    void put(String token, List<String> terms);
}
