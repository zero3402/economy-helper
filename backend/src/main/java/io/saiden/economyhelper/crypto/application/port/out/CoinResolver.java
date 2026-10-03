package io.saiden.economyhelper.crypto.application.port.out;

import io.saiden.economyhelper.crypto.domain.ResolvedCoin;
import java.util.Optional;

/**
 * 검색어 → 티커 해석(LLM). <b>업비트 이름 매칭에 안 걸린 검색어만</b> 여기로 온다.
 */
public interface CoinResolver {

    /**
     * @param normalizedQuery {@code QueryNormalizer.normalize}로 다듬은 검색어 — 캐시 키다
     * @return 짚어 낸 코인. 실패하거나 특정하지 못하면 {@link Optional#empty()}
     */
    Optional<ResolvedCoin> resolve(String normalizedQuery);
}
