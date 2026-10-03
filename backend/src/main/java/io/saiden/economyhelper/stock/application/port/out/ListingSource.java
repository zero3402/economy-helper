package io.saiden.economyhelper.stock.application.port.out;

import io.saiden.economyhelper.stock.domain.Listing;
import java.util.List;

/**
 * 국내 상장 종목 전부 — 「이름 → 종목코드」 색인({@code StockListings})의 재료.
 *
 * <p>한국투자증권에 종목명 검색이 없어 색인을 우리가 만든다. 실패는 던진다 — 색인이 삼킨다.
 */
public interface ListingSource {

    List<Listing> listings();
}
