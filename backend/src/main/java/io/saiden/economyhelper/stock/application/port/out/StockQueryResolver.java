package io.saiden.economyhelper.stock.application.port.out;

import io.saiden.economyhelper.stock.domain.ResolvedStock;
import java.util.Optional;

/**
 * 검색어 → 종목 해석(LLM). 실재는 여기서 확정하지 않는다 — 시세 출처와 색인이 확정한다.
 */
public interface StockQueryResolver {

    /**
     * @param normalizedQuery {@code QueryNormalizer.normalize}로 다듬은 검색어 — 캐시 키다
     * @return 짚어 낸 종목. 실패하거나 특정하지 못하면 {@link Optional#empty()}
     */
    Optional<ResolvedStock> resolve(String normalizedQuery);
}
