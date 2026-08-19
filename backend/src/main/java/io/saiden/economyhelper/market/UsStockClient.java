package io.saiden.economyhelper.market;

import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;

/**
 * 미국 시세 한 곳 — {@link DomesticStockClient}의 미국판이다. 같은 규칙으로 <b>값을 주거나
 * 던진다.</b>
 *
 * <p>지수와 종목을 한 메서드로 받는 것이 국내와 다른 점이다. 2순위인 FMP가 둘을 같은
 * 엔드포인트로 주고, 1순위인 KIS는 갈리지만 그 갈림은 {@link UsSymbol}이 이미 담고 있다
 * — 지수만 {@code ^}로 시작한다.
 */
public interface UsStockClient extends StockClient {

    /**
     * @param symbol 심볼·표시 이름·KIS 조회 키를 함께 든다. <b>이름이 인자에 있는 이유는
     *               응답에서 못 얻기 때문이다</b> — KIS는 이름을 아예 안 주고 FMP는 영문명을 준다
     * @throws RuntimeException 못 주면 무조건 던진다. KIS 대응이 없는 심볼도 여기에 해당한다
     */
    StockQuote quote(UsSymbol symbol);
}
