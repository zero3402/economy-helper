package io.saiden.economyhelper.market.data;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.market.DomesticStockClient;
import io.saiden.economyhelper.market.Price;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 공공데이터포털(금융위원회) — <b>국내 이중화의 2순위.</b>
 *
 * <p>1순위인 한국투자증권이 죽거나 앱키가 없을 때 여기가 받는다. <b>제약이 적은 쪽이 뒤에
 * 선다</b>는 규칙 그대로다 — 이쪽은 하루 1만 회고 종목명 검색까지 된다.
 *
 * <p>대신 <b>전일 종가만</b> 준다({@code realtime=false}). 그 사실이 화면의 기준 줄에
 * "(종가)"로 드러나므로, 폴백이 일어났다는 것이 사용자에게 그대로 보인다.
 *
 * <p><b>공공데이터포털 응답 모양을 아는 것은 이 클래스뿐이다.</b> 예전에는 {@code StockService}가
 * {@code StockPrice}를 직접 뜯어 시가총액을 비교하고 날짜를 옮겼는데, 출처가 둘이 되는 순간
 * 그 지식이 서비스에 남아 있으면 안 된다 — 서비스는 "어느 출처가 먼저냐"만 알아야 한다.
 */
@Component
public class DataGoStockClient implements DomesticStockClient {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockPriceApi stocks;
    private final MarketIndexApi indices;

    public DataGoStockClient(StockPriceApi stocks, MarketIndexApi indices) {
        this.stocks = stocks;
        this.indices = indices;
    }

    @Override
    public StockSource source() {
        return StockSource.DATA_GO;
    }

    @Override
    public StockQuote stock(String code) {
        return best(stocks.searchByCode(code))
                .orElseThrow(() -> new IllegalStateException("종목코드 " + code + " 시세가 없습니다"));
    }

    /**
     * <b>업종코드가 아니라 이름으로 찾는다</b> — 이 API에는 코드 조회가 없다.
     * 그래서 {@link Index#code()}는 여기서 쓰이지 않는다(1순위인 KIS가 그걸 쓴다).
     */
    @Override
    public StockQuote index(Index index) {
        MarketIndex found = indices.searchByName(index.name());
        if (found == null) {
            throw new IllegalStateException("'" + index.name() + "' 지수를 찾지 못했습니다");
        }
        // 이름도 응답 것을 쓴다 — 완전일치로 고른 것이라 설정 이름과 같고, 부분일치로 떨어졌다면
        // 실제로 찾아낸 지수의 정식명이 맞다(KIS가 '종합'을 주는 것과 사정이 다르다)
        return new StockQuote(found.idxNm(), price(found.clpr(), "지수 " + index.name()),
                percent(found.fltRt()),
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.DATA_GO,
                atSeoulMidnight(found.basDt()), false);
    }

    /**
     * 종목명으로 찾는다 — <b>SPI 밖에 있는 이유가 있다.</b>
     *
     * <p>한국투자증권에는 종목명 검색이 아예 없다(조회가 언제나 코드 → 이름 방향이다).
     * 이중화할 상대가 없는 경로를 SPI에 넣으면 1순위가 언제나 못 하는 메서드를 갖게 된다.
     *
     * @return 시가총액 1위 후보. 걸리는 것이 없으면 {@link Optional#empty()} —
     *         <b>이건 장애가 아니라 "그런 종목이 없다"이므로 던지지 않는다</b>
     */
    public Optional<StockQuote> byName(String name) {
        return best(stocks.searchByName(name));
    }

    /**
     * 후보 중 시가총액 1위.
     *
     * <p>같은 종목의 여러 날짜가 섞여 오므로 <b>가장 최근 기준일만</b> 남긴 뒤 비교한다 —
     * 안 그러면 어제 삼성전자와 그제 삼성전자가 서로 다른 후보로 보인다.
     */
    private static Optional<StockQuote> best(List<StockPrice> prices) {
        return onlyLatestDate(prices).stream()
                .max(Comparator.comparing(price -> amountForRanking(price.mrktTotAmt())))
                .map(DataGoStockClient::toQuote);
    }

    private static List<StockPrice> onlyLatestDate(List<StockPrice> prices) {
        // ⚠️ 널을 먼저 걸러야 한다. Comparator.naturalOrder()는 널 원소에서 NPE인데
        //    orElse("")는 빈 스트림만 막는다 — 기준일 없는 항목 하나가 조회 전체를 죽였다
        List<StockPrice> dated = prices.stream().filter(price -> price.basDt() != null).toList();
        String latest = dated.stream().map(StockPrice::basDt).max(Comparator.naturalOrder()).orElse("");
        return dated.stream().filter(price -> latest.equals(price.basDt())).toList();
    }

    /** <b>전일 종가</b>다. {@code realtime=false}가 화면에서 "(종가)"로 드러난다. */
    private static StockQuote toQuote(StockPrice price) {
        return new StockQuote(price.itmsNm(), price(price.clpr(), "종목 " + price.itmsNm()),
                percent(price.fltRt()),
                StockQuote.Money.KRW, StockQuote.Market.DOMESTIC, StockSource.DATA_GO,
                atSeoulMidnight(price.basDt()), false);
    }

    /** 종가일을 시각으로 옮긴다. 그날 장이 끝난 값이므로 KST 자정으로 두고 표기는 날짜만 쓴다. */
    private static java.time.Instant atSeoulMidnight(String basDt) {
        return LocalDate.parse(basDt, BAS_DT).atStartOfDay(SEOUL).toInstant();
    }

    /**
     * 등락률 전용 파서 — 없거나 깨진 값은 {@code null}이다.
     *
     * <p><b>{@link #parse}를 쓰면 안 된다.</b> 그쪽의 폴백인 {@code 0}은 등락률에서
     * "보합"이라는 <b>값</b>이지 "모른다"가 아니다. 못 구한 것을 보합으로 찍으면 화면이
     * 거짓말을 한다.
     */
    private static BigDecimal percent(String value) {
        return number(value, null);
    }

    /**
     * <b>순위를 매길 때만</b> 쓴다 — 시가총액이다. 값이 비거나 깨져 있어도 조회 전체를
     * 실패시키지 않는다: 0으로 보면 후보 순위에서 뒤로 밀릴 뿐이고, 그 종목이 답이 아니게
     * 되는 것으로 충분하다.
     *
     * <p><b>표시 가격에는 쓰지 않는다.</b> 예전에는 같은 함수가 {@code clpr}에도 쓰였는데,
     * 그러면 빈 종가가 {@code price=0}으로 <b>성공</b> 반환되어 「코스피 0」이 화면에 나가고
     * 이중화의 폴백도 돌지 않았다 — 바로 아래 {@link #percent}가 정확히 그 이유로 {@code null}을
     * 쓰면서 "못 구한 것을 보합으로 찍으면 화면이 거짓말을 한다"고 적어 둔 것과 같은 함정이다.
     * 이름을 갈라 둔 것이 그 재발 방지다.
     */
    private static BigDecimal amountForRanking(String value) {
        return number(value, BigDecimal.ZERO);
    }

    /** 화면에 찍히는 값 — 못 구하면 {@link Price}가 던져 다음 출처로 넘어간다. */
    private static BigDecimal price(String value, String what) {
        return Price.require(number(value, null), "공공데이터포털 " + what);
    }

    private static BigDecimal number(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
