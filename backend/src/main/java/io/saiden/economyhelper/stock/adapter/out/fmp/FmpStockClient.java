package io.saiden.economyhelper.stock.adapter.out.fmp;

import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import io.saiden.economyhelper.stock.adapter.out.fmp.FmpApi.FmpQuote;
import io.saiden.economyhelper.stock.application.port.out.StockClient;
import io.saiden.economyhelper.stock.application.port.out.UsStockClient;
import io.saiden.economyhelper.stock.domain.StockQuote;
import io.saiden.economyhelper.stock.domain.StockSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Financial Modeling Prep — <b>미국 이중화의 2순위.</b>
 *
 * <p>⚠️ <b>1순위인 한국투자증권보다 좁다.</b> 무료 티어가 <b>심볼별 임의 허용목록</b>이어서다 —
 * 실측으로 {@code PATH}·{@code ORCL}·{@code SNOW} 같은 초대형주도 402다. 그래서 미국 종목의
 * 2순위는 <b>이름만 있는 자리</b>이고 KIS가 임의 심볼을 다 맡아야 한다.
 *
 * <p>부르는 방식은 단순하다: KIS는 지수마다 제 심볼이 필요한데({@code ^IXIC}가 아니라 {@code COMP})
 * 여기는 티커 하나면 된다. 그래서 KIS 지수 표에 없는 지수는 이쪽이 맡는다.
 *
 * <p>대신 하루 250회 한도가 있다({@link FmpQuotaGuard}). 1순위가 성공하면 여기는 호출조차
 * 되지 않으므로, 이중화가 그 한도를 지키는 장치이기도 하다.
 */
@Component
public class FmpStockClient implements UsStockClient {

    /** 체결 시각이 「오늘」인지는 그 시장의 달력으로 본다 — KST로 보면 서울 새벽의 뉴욕 장중 체결이 어제가 된다. */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** 종가의 기준 시각 — 종가일 00시(KST). {@code DataGoStockClient}·{@code KisHeaders.startOfDay}와 같은 모양이다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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
            throw new StockClient.NotFound("'" + symbol.symbol() + "' 심볼이 없습니다");
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
        boolean index = symbol.isIndex();
        String name = symbol.name() == null || symbol.name().isBlank() ? quote.name() : symbol.name();
        // 미국 폴백의 마지막 자리다 — 여기서 빈 값이 새면 받아 줄 출처가 없다.
        // FMP는 상장폐지·장전 무거래에 price를 null로 주고, 그건 "없다"이지 값이 아니다
        Basis basis = basisOf(quote);
        return new StockQuote(name, Price.require(quote.price(), "FMP " + symbol.symbol()),
                PercentChange.ofNullable(quote.changePercentage()),
                index ? StockQuote.Money.NONE : StockQuote.Money.USD, StockQuote.Market.US,
                StockSource.FMP, basis.at(), basis.live());
    }

    /**
     * FMP는 <b>체결 시각을 준다</b> — KIS와 달리 읽은 시각으로 대신할 필요가 없다.
     *
     * <p>그 체결이 뉴욕 달력으로 <b>어제 이전이면 종가다.</b> 토요일에 금요일 16시 체결가를 실시간이라 찍으면
     * 낡은 값을 신선하게 꾸미는 셈이다 — KIS 종가와 같은 모양(종가일 00시 KST, {@code realtime=false})으로 두면
     * 화면이 날짜와 (종가)를 찍는다. 체결 시각이 없으면 지금까지처럼 읽은 시각·실시간이다 — 모른다고 낡았다고
     * 단정하지 않는다.
     */
    private Basis basisOf(FmpQuote quote) {
        if (quote.timestamp() == null) {
            return new Basis(clock.instant(), true);
        }
        Instant traded = Instant.ofEpochSecond(quote.timestamp());
        LocalDate tradedOn = LocalDate.ofInstant(traded, NEW_YORK);
        if (tradedOn.isBefore(LocalDate.ofInstant(clock.instant(), NEW_YORK))) {
            return new Basis(tradedOn.atStartOfDay(SEOUL).toInstant(), false);
        }
        return new Basis(traded, true);
    }

    private record Basis(Instant at, boolean live) {}
}
