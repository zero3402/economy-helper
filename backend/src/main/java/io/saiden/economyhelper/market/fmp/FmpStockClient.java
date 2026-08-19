package io.saiden.economyhelper.market.fmp;

import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.UsStockClient;
import io.saiden.economyhelper.market.fmp.FmpApi.FmpQuote;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Financial Modeling Prep — <b>미국 이중화의 2순위.</b>
 *
 * <p>1순위인 한국투자증권보다 <b>받을 수 있는 심볼이 넓다.</b> KIS는 지수마다 제 심볼이
 * 필요하고({@code ^IXIC}가 아니라 {@code COMP}) 종목에는 거래소 코드를 요구하는데, 여기는
 * 티커 하나면 된다. 그래서 설정에 KIS 대응이 없는 심볼은 이쪽이 통째로 맡는다.
 *
 * <p>대신 하루 250회 한도가 있다({@link FmpQuotaGuard}). 1순위가 성공하면 여기는 호출조차
 * 되지 않으므로, 이중화가 그 한도를 지키는 장치이기도 하다.
 */
@Component
public class FmpStockClient implements UsStockClient {

    private final FmpApi api;
    private final Clock clock;

    public FmpStockClient(FmpApi api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public StockSource source() {
        return StockSource.FMP;
    }

    @Override
    public StockQuote quote(UsSymbol symbol) {
        FmpQuote found = api.quote(symbol.symbol());
        if (found == null) {
            // LLM이 지어낸 티커가 여기서 걸러진다 — FMP가 빈 배열을 준다
            throw new IllegalStateException("'" + symbol.symbol() + "' 심볼이 없습니다");
        }
        return toQuote(found, symbol);
    }

    /**
     * <p>지수 판별을 {@code ^} 접두로 한다. FMP가 종목과 지수를 같은 엔드포인트로 주고
     * 응답에 구분 필드가 없어서, 심볼 관례가 유일한 단서다({@code ^IXIC}·{@code ^GSPC}·{@code ^DJI}).
     *
     * <p>이름은 <b>우리 것을 먼저 쓴다.</b> FMP는 {@code Apple Inc.}처럼 영문명을 주는데
     * 국내 종목은 한글명이고 코인도 한글이라 한 화면에서 표기가 갈린다 — 브리핑은 설정 이름,
     * 검색은 LLM이 해석한 한국어 이름이 {@link UsSymbol#name()}에 담겨 온다.
     */
    private StockQuote toQuote(FmpQuote quote, UsSymbol symbol) {
        boolean index = symbol.symbol() != null && symbol.symbol().startsWith("^");
        String name = symbol.name() == null || symbol.name().isBlank() ? quote.name() : symbol.name();
        return new StockQuote(name, quote.price(), quote.changePercentage(),
                index ? StockQuote.Money.NONE : StockQuote.Money.USD, StockQuote.Market.US,
                StockSource.FMP, at(quote), true);
    }

    /** FMP는 <b>체결 시각을 준다</b> — KIS와 달리 읽은 시각으로 대신할 필요가 없다. */
    private Instant at(FmpQuote quote) {
        return quote.timestamp() == null ? clock.instant() : Instant.ofEpochSecond(quote.timestamp());
    }
}
