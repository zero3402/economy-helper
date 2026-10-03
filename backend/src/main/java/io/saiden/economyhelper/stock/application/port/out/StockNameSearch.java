package io.saiden.economyhelper.stock.application.port.out;

import io.saiden.economyhelper.stock.domain.StockQuote;
import java.util.Optional;

/**
 * 종목명으로 시세 찾기(국내, 전일 종가) — 색인에 없는 이름이 기대는 둘째 길.
 *
 * <p>{@link DomesticStockClient}와 따로 둔다 — 그쪽은 코드로 묻는 이중화 사슬이고, 이름 검색은
 * 한 출처(공공데이터포털)만 해 이중화 상대가 없다.
 */
public interface StockNameSearch {

    /**
     * @return 시가총액 1위 후보의 시세. 걸리는 종목이 없으면 빈손
     * @throws RuntimeException 조회 실패 — 부르는 쪽이 삼킨다
     */
    Optional<StockQuote> byName(String name);
}
