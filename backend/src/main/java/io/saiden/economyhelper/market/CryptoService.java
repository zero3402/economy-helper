package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.binance.BinanceApi;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.binance.BinanceSymbol;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.market.upbit.UpbitApi.UpbitTicker;
import io.saiden.economyhelper.market.upbit.UpbitMarket;
import io.saiden.economyhelper.market.upbit.UpbitMarketIndex;
import io.saiden.economyhelper.text.QueryNormalizer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code /crypto {검색어}} — 검색어를 마켓으로 옮기고 현재가를 가져온다.
 *
 * <p><b>LLM을 쓰지 않는다.</b> 후보가 유한(원화 마켓 282개)하고 이름이 한글·영문 둘 다 있어
 * 대부분 그대로 걸리며, 남는 모호함은 <b>24시간 거래대금</b>이 가른다. 실제 데이터로 확인한 결과다:
 *
 * <table>
 *   <tr><th>검색어</th><th>1위(거래대금)</th><th>2위</th><th>배수</th></tr>
 *   <tr><td>비트</td><td>비트코인 591억</td><td>아비트럼 12.6억</td><td>47배</td></tr>
 *   <tr><td>이더</td><td>이더리움 265억</td><td>메가이더 13.2억</td><td>20배</td></tr>
 *   <tr><td>리플</td><td>엑스알피(리플) 759억</td><td>리플유에스디 0.2억</td><td>3,665배</td></tr>
 * </table>
 *
 * 셋 다 이름만 보면 오답이 앞에 왔고, 거래대금으로는 다섯 사례가 모두 정답이었다.
 * 시세 조회는 어차피 해야 하고 후보를 콤마로 묶어 <b>한 번</b>에 부르므로 공짜다 —
 * LLM보다 빠르고 정확하다.
 *
 * <p>해석 결과를 따로 캐시하지 않는 이유: 마켓 목록이 이미 6시간 캐시돼 있고 매칭은 순수 계산이라
 * 아낄 게 없다. 캐시는 비싼 것에만 건다.
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    /** 업비트 원화 마켓의 USDT. 바이낸스 USDT 가격을 원화로 옮길 때 쓴다. */
    private static final String USDT_MARKET = "KRW-USDT";

    private final UpbitApi upbitApi;
    private final BinanceApi binanceApi;

    public CryptoService(UpbitApi upbitApi, BinanceApi binanceApi) {
        this.upbitApi = upbitApi;
        this.binanceApi = binanceApi;
    }

    /**
     * USDT 1개의 원화값 — 바이낸스 시세를 원화로 옮기는 기준.
     *
     * <p><b>USD/KRW 환율이 아니라 업비트 실거래가를 쓴다.</b> 한국에서 실제로 바꿀 수 있는 값이
     * 이쪽이고 김치 프리미엄이 반영돼 있다. 환율로 환산하면 "USDT는 1달러"라는 가정이 들어가
     * 국내 시세와 나란히 놓았을 때 차이가 실제보다 커 보인다.
     */
    public Optional<BigDecimal> usdtKrw() {
        try {
            return upbitApi.tickers(List.of(USDT_MARKET)).stream()
                    .map(UpbitTicker::tradePrice)
                    .filter(java.util.Objects::nonNull)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("[crypto] USDT 원화 시세 조회 실패 — 바이낸스는 USDT로만 표시합니다: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 업비트 결과에 바이낸스 USDT 가격을 붙인다.
     *
     * <p><b>바이낸스가 죽어도 업비트 값은 그대로 나가야 한다.</b> 지역 차단(451)·타임아웃·
     * 브레이커 열림을 전부 같게 다뤄 {@code binanceUsdt}만 비우고 원래 목록을 돌려준다 —
     * 거래소 하나를 더 붙였다고 되던 것이 안 되면 개악이다.
     */
    private List<CryptoQuote> withBinance(List<CryptoQuote> quotes) {
        Map<String, String> symbolByMarket = quotes.stream()
                .flatMap(quote -> BinanceSymbol.of(quote.market())
                        .map(symbol -> Map.entry(quote.market(), symbol)).stream())
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (symbolByMarket.isEmpty()) {
            return quotes;
        }

        Map<String, BigDecimal> priceBySymbol;
        try {
            priceBySymbol = binanceApi.prices(symbolByMarket.values().stream().sorted().toList()).stream()
                    .collect(java.util.stream.Collectors.toMap(BinancePrice::symbol, BinancePrice::price));
        } catch (RuntimeException e) {
            log.warn("[crypto] 바이낸스 조회 실패 — 업비트 시세만 내보냅니다: {}", e.toString());
            return quotes;
        }

        return quotes.stream()
                .map(quote -> quote.withBinance(priceBySymbol.get(symbolByMarket.get(quote.market()))))
                .toList();
    }

    /**
     * @return 현재가. 검색어에 걸리는 코인이 없거나 업비트가 죽었으면 {@link Optional#empty()}
     */
    public Optional<CryptoQuote> quote(String query) {
        List<String> forms = QueryNormalizer.forLookup(query);
        if (forms.isEmpty()) {
            return Optional.empty();
        }

        try {
            List<UpbitMarket> candidates =
                    UpbitMarketIndex.candidates(forms, upbitApi.krwMarkets());
            if (candidates.isEmpty()) {
                log.info("[crypto] '{}'에 걸리는 마켓이 없습니다", query);
                return Optional.empty();
            }
            return pickAndQuote(candidates)
                    .map(quote -> withBinance(List.of(quote)).get(0));
        } catch (RuntimeException e) {
            // 브레이커가 열렸거나 업비트가 응답하지 않는다. 사용자에게는 "못 가져왔다"로 나간다
            log.error("[crypto] '{}' 조회 실패: {}", query, e.toString());
            return Optional.empty();
        }
    }

    /** 마켓 코드를 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 코인들이 여기로 온다. */
    public List<CryptoQuote> quotesOf(List<String> markets) {
        if (markets.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, UpbitMarket> byCode = upbitApi.krwMarkets().stream()
                    .collect(java.util.stream.Collectors.toMap(UpbitMarket::market, Function.identity()));
            List<String> known = markets.stream().filter(byCode::containsKey).toList();
            if (known.isEmpty()) {
                log.warn("[crypto] 설정된 마켓이 업비트 목록에 없습니다: {}", markets);
                return List.of();
            }
            return withBinance(upbitApi.tickers(known).stream()
                    .map(ticker -> toQuote(byCode.get(ticker.market()), ticker))
                    .toList());
        } catch (RuntimeException e) {
            log.error("[crypto] 시세 조회 실패 {}: {}", markets, e.toString());
            return List.of();
        }
    }

    /**
     * 후보가 여럿이면 거래대금 1위를 고른다.
     *
     * <p>후보 전부의 시세를 한 번에 받으므로, 고르는 것과 값을 얻는 것이 같은 호출로 끝난다.
     */
    private Optional<CryptoQuote> pickAndQuote(List<UpbitMarket> candidates) {
        List<String> codes = candidates.stream().map(UpbitMarket::market).toList();
        List<UpbitTicker> tickers = upbitApi.tickers(codes);
        if (tickers.isEmpty()) {
            return Optional.empty();
        }

        Map<String, UpbitMarket> byCode = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(UpbitMarket::market, Function.identity()));

        return tickers.stream()
                .filter(ticker -> ticker.accTradePrice24h() != null && byCode.containsKey(ticker.market()))
                .max(Comparator.comparing(UpbitTicker::accTradePrice24h))
                .map(ticker -> toQuote(byCode.get(ticker.market()), ticker));
    }

    private static CryptoQuote toQuote(UpbitMarket market, UpbitTicker ticker) {
        Instant at = ticker.tradeTimestamp() == null
                ? Instant.now()
                : Instant.ofEpochMilli(ticker.tradeTimestamp());
        // 바이낸스 값은 withBinance가 나중에 채운다 — 업비트 조회와 별개 호출이다
        return new CryptoQuote(market.market(), market.koreanName(), ticker.tradePrice(), at, null);
    }
}
