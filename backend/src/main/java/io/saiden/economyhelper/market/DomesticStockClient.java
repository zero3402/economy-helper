package io.saiden.economyhelper.market;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;

/**
 * 국내 시세 한 곳 — <b>{@code StockService}가 순서대로 시도한다.</b>
 *
 * <p>{@link FxRateClient}와 같은 규칙이다: <b>값을 주거나 던진다.</b> 빈 결과를 돌려주면
 * 이중화가 폴백하지 않고 그대로 빈손이 나간다. 못 주는 이유가 상대 장애든(브레이커 열림)
 * 우리 사정이든(조회 키가 없다) 전부 예외다 — 판단은 부르는 쪽이 한다.
 *
 * <p><b>미국과 나누는 이유는 조회 키가 다르기 때문이다.</b> 국내는 6자리 종목코드와 업종코드,
 * 미국은 티커와 거래소 코드다. 하나로 합치면 한쪽에는 언제나 의미 없는 인자가 남는다.
 *
 * <p><b>이름으로 찾는 경로는 여기 없다.</b> 한국투자증권에 종목명 검색이 없어서다 —
 * 조회가 언제나 코드 → 이름 방향이다. 그래서 그 경로는 SPI 밖, 공공데이터포털 쪽에만 있다
 * ({@code DataGoStockClient.byName}).
 */
public interface DomesticStockClient extends StockClient {

    /**
     * @param code 6자리 종목코드
     * @throws RuntimeException 못 주면 무조건 던진다 — 그래야 다음 출처가 시도된다
     */
    StockQuote stock(String code);

    /**
     * @param index 이름과 코드를 함께 든다. 출처마다 쓰는 쪽이 다르다 —
     *              공공데이터포털은 이름으로 찾고 한국투자증권은 업종코드를 요구한다
     */
    StockQuote index(Index index);
}
