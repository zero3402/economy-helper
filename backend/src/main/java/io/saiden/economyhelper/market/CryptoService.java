package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * {@code /crypto {검색어}} — 검색어를 마켓으로 옮기고 현재가를 가져온다.
 *
 * <p><b>업비트 이름 매칭이 먼저고, LLM은 거기서 안 걸릴 때만 부른다.</b> 매칭에 드는 비용이
 * 사실상 0이라서다 — 원화 마켓 목록은 이미 6시간 캐시돼 있고 매칭은 순수 계산이며, 후보 시세는
 * 콤마로 묶어 한 번에 받는다. 후보가 유한하고 이름이 한글·영문 둘 다 있어 대부분 그대로 걸리고,
 * 남는 모호함은 <b>24시간 거래대금</b>이 가른다. 실측 결과다:
 *
 * <table>
 *   <tr><th>검색어</th><th>1위(거래대금)</th><th>2위</th><th>배수</th></tr>
 *   <tr><td>비트</td><td>비트코인 591억</td><td>아비트럼 12.6억</td><td>47배</td></tr>
 *   <tr><td>이더</td><td>이더리움 265억</td><td>메가이더 13.2억</td><td>20배</td></tr>
 *   <tr><td>리플</td><td>엑스알피(리플) 759억</td><td>리플유에스디 0.2억</td><td>3,665배</td></tr>
 * </table>
 *
 * 셋 다 이름만 보면 오답이 앞에 왔고, 거래대금으로는 다섯 사례가 모두 정답이었다.
 *
 * <p><b>{@link CryptoResolver}(LLM)가 메우는 자리는 업비트에 아예 없는 코인뿐이다.</b>
 * 실측: 원화 마켓 283개에 {@code KRW-BNB}가 없는데 바이낸스에는 {@code BNBUSDT}가 있다.
 * 이때는 후보 목록 자체가 없어 거래대금으로 가릴 대상이 없고, 바이낸스는 심볼만 주고 한글
 * 이름을 주지 않아 {@code 비앤비}를 받을 방법이 없다.
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    /** 업비트만 등락률을 비율로 준다 — %로 옮기는 데 쓴다. */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 업비트 원화 마켓의 USDT. 바이낸스 USDT 가격을 원화로 옮길 때 쓴다. */
    private static final String USDT_MARKET = "KRW-USDT";

    private final UpbitApi upbitApi;
    private final BinanceApi binanceApi;
    private final CryptoResolver resolver;

    public CryptoService(UpbitApi upbitApi, BinanceApi binanceApi, CryptoResolver resolver) {
        this.upbitApi = upbitApi;
        this.binanceApi = binanceApi;
        this.resolver = resolver;
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
                    .filter(Objects::nonNull)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("[crypto] USDT 원화 시세 조회 실패 — 바이낸스는 USDT로만 표시합니다: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * @return 두 거래소 시세. 어느 쪽에도 없으면 {@link Optional#empty()}
     */
    public Optional<CryptoQuote> quote(String query) {
        // 업비트 이름 매칭이 먼저다. 마켓 목록은 이미 6시간 캐시돼 있고 매칭은 순수 계산이라
        // 공짜인데, LLM을 앞에 두면 '비트코인'에도 Gemini가 나간다
        Optional<CryptoQuote> byName = byUpbitName(query)
                .map(quote -> withBinance(List.of(quote)).get(0));
        if (byName.isPresent()) {
            return byName;
        }
        // 업비트에 걸리는 것이 없다. 여기서만 LLM에게 티커를 묻는다
        return resolver.resolve(CryptoResolver.cacheKeyOf(query))
                .map(ResolvedCoin::upperSymbol)
                .filter(Objects::nonNull)
                .flatMap(this::quoteOf);
    }

    /**
     * 티커 하나로 <b>두 거래소를 각각</b> 조회한다.
     *
     * <p>한쪽이 없어도 다른 쪽을 내보낸다 — 그게 이 명령의 요지다. 다만 없는 쪽을 빼지 않고
     * {@code NOT_LISTED}로 적는다. 둘 다 없을 때만 "찾지 못했다"가 된다.
     */
    private Optional<CryptoQuote> quoteOf(String symbol) {
        UpbitSide upbit = upbitSide("KRW-" + symbol);
        Quote binance = binancePrice(symbol + "USDT");

        if (upbit.quote().state() == Quote.State.NOT_LISTED
                && binance.state() == Quote.State.NOT_LISTED) {
            log.info("[crypto] {}는 업비트·바이낸스 어디에도 없습니다", symbol);
            return Optional.empty();
        }
        UpbitMarket listed = upbit.market();
        // 업비트가 없으면 티커를 그대로 쓴다. LLM에게 한글 이름을 받아 쓰면 아무도 그렇게
        // 부르지 않는 표기(BNB → '비앤비')가 제목에 찍힌다
        return Optional.of(new CryptoQuote(
                listed == null ? symbol : listed.koreanName(),
                listed == null ? null : listed.market(),
                upbit.at() == null ? Instant.now() : upbit.at(),
                upbit.quote(), binance));
    }

    /**
     * 업비트 한 종목의 조회 결과.
     *
     * @param market 상장돼 있으면 그 마켓, 아니면 {@code null}
     * @param at     업비트 체결 시각. 모르면 {@code null} — 호출자가 조회 시각으로 대신한다
     */
    private record UpbitSide(UpbitMarket market, Quote quote, Instant at) {}

    /**
     * <p><b>마켓 목록을 못 받은 것과 목록에 없는 것을 가른다.</b> 전자는 상장 여부를 "모르는"
     * 것이지 "없는" 것이 아니다 — 업비트가 잠깐 죽었다고 화면에 {@code 미상장}이 찍히면
     * 사용자는 영영 안 나오는 코인으로 읽고 다시 시도하지 않는다.
     */
    private UpbitSide upbitSide(String market) {
        List<UpbitMarket> markets;
        try {
            markets = upbitApi.krwMarkets();
        } catch (RuntimeException e) {
            log.warn("[crypto] 업비트 마켓 목록 조회 실패: {}", e.toString());
            return new UpbitSide(null, Quote.FAILED, null);
        }

        Optional<UpbitMarket> listed = markets.stream()
                .filter(m -> m.market().equalsIgnoreCase(market)).findFirst();
        if (listed.isEmpty()) {
            return new UpbitSide(null, Quote.NOT_LISTED, null);
        }
        try {
            return upbitApi.tickers(List.of(listed.get().market())).stream().findFirst()
                    .map(ticker -> new UpbitSide(listed.get(),
                            Quote.of(ticker.tradePrice(), percentOf(ticker)), tradedAt(ticker)))
                    .orElseGet(() -> new UpbitSide(listed.get(), Quote.FAILED, null));
        } catch (RuntimeException e) {
            log.warn("[crypto] {} 업비트 시세 실패: {}", market, e.toString());
            return new UpbitSide(listed.get(), Quote.FAILED, null);
        }
    }

    /**
     * <p><b>400({@code Invalid symbol.})만</b> "그 거래소에 없다"로 읽는다. 451(지역 차단)·
     * 429·418(한도 초과)도 4xx지만 잠시 뒤 다시 치면 되는 것이라, 같이 묶으면 사용자가
     * 재시도할 수 있는 상황에서 영영 없다고 말하게 된다.
     */
    private Quote binancePrice(String symbol) {
        try {
            return binanceApi.prices(List.of(symbol)).stream().findFirst()
                    .map(price -> Quote.of(price.lastPrice(), price.priceChangePercent()))
                    .orElse(Quote.NOT_LISTED);
        } catch (RuntimeException e) {
            if (e instanceof HttpClientErrorException http && http.getStatusCode().value() == 400) {
                return Quote.NOT_LISTED;
            }
            log.warn("[crypto] {} 바이낸스 시세 실패: {}", symbol, e.toString());
            return Quote.FAILED;
        }
    }

    /** 1순위 — 업비트 이름 매칭 + 거래대금 1위. 캐시된 목록에 대한 순수 계산이라 공짜다. */
    private Optional<CryptoQuote> byUpbitName(String query) {
        List<String> forms = QueryNormalizer.forLookup(query);
        if (forms.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<UpbitMarket> candidates = UpbitMarketIndex.candidates(forms, upbitApi.krwMarkets());
            if (candidates.isEmpty()) {
                log.info("[crypto] '{}'에 걸리는 업비트 마켓이 없습니다", query);
                return Optional.empty();
            }
            return pickAndQuote(candidates);
        } catch (RuntimeException e) {
            log.error("[crypto] '{}' 업비트 조회 실패: {}", query, e.toString());
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
                    .collect(Collectors.toMap(UpbitMarket::market, Function.identity()));
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
     * 업비트 결과에 바이낸스 USDT 가격을 붙인다.
     *
     * <p><b>바이낸스가 죽어도 업비트 값은 그대로 나가야 한다.</b> 다만 그 사실을 숨기지 않는다 —
     * 지역 차단(451)·타임아웃·브레이커 열림은 {@code FAILED}, 상장되지 않은 코인은
     * {@code NOT_LISTED}로 갈라 적는다. 사용자에게 전자는 "잠시 뒤 다시", 후자는 "영영 없음"이다.
     */
    private List<CryptoQuote> withBinance(List<CryptoQuote> quotes) {
        Map<String, String> symbolByMarket = quotes.stream()
                .flatMap(quote -> BinanceSymbol.of(quote.market())
                        .map(symbol -> Map.entry(quote.market(), symbol)).stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (symbolByMarket.isEmpty()) {
            // 심볼을 유도할 수 있는 마켓이 하나도 없다(예: KRW-USDT뿐) — 부를 것이 없다
            return quotes.stream().map(quote -> withBinanceState(quote, Quote.NOT_LISTED)).toList();
        }

        Map<String, BinancePrice> priceBySymbol;
        try {
            priceBySymbol = binanceApi.prices(symbolByMarket.values().stream().sorted().toList()).stream()
                    .collect(Collectors.toMap(BinancePrice::symbol, Function.identity()));
        } catch (RuntimeException e) {
            log.warn("[crypto] 바이낸스 조회 실패 — 업비트 시세만 내보냅니다: {}", e.toString());
            return quotes.stream()
                    .map(quote -> withBinanceState(quote, symbolByMarket.containsKey(quote.market())
                            ? Quote.FAILED : Quote.NOT_LISTED))
                    .toList();
        }

        return quotes.stream().map(quote -> {
            String symbol = symbolByMarket.get(quote.market());
            BinancePrice price = symbol == null ? null : priceBySymbol.get(symbol);
            return withBinanceState(quote, price == null
                    ? Quote.NOT_LISTED
                    : Quote.of(price.lastPrice(), price.priceChangePercent()));
        }).toList();
    }

    private static CryptoQuote withBinanceState(CryptoQuote quote, Quote binance) {
        return new CryptoQuote(quote.name(), quote.market(), quote.at(), quote.upbit(), binance);
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
                .collect(Collectors.toMap(UpbitMarket::market, Function.identity()));

        return tickers.stream()
                .filter(ticker -> ticker.accTradePrice24h() != null && byCode.containsKey(ticker.market()))
                .max(Comparator.comparing(UpbitTicker::accTradePrice24h))
                .map(ticker -> toQuote(byCode.get(ticker.market()), ticker));
    }

    private static CryptoQuote toQuote(UpbitMarket market, UpbitTicker ticker) {
        Instant at = tradedAt(ticker);
        // 바이낸스 쪽은 withBinance가 나중에 채운다 — 업비트 조회와 별개 호출이다
        return new CryptoQuote(market.koreanName(), market.market(),
                at == null ? Instant.now() : at,
                Quote.of(ticker.tradePrice(), percentOf(ticker)), Quote.FAILED);
    }

    /**
     * 업비트 등락률을 %로 옮긴다.
     *
     * <p><b>업비트는 비율로 준다</b> — {@code -0.0070571945}가 -0.71%다. 바이낸스·FMP·
     * 공공데이터포털은 이미 %라 이 환산은 업비트에만 필요하고, <b>그래서 여기 한 곳에만 둔다.</b>
     * 표시하는 쪽까지 내려보내면 어느 출처가 비율이고 어느 쪽이 %인지를 화면이 알아야 한다.
     */
    private static BigDecimal percentOf(UpbitTicker ticker) {
        return ticker.signedChangeRate() == null
                ? null
                : ticker.signedChangeRate().multiply(HUNDRED);
    }

    /** @return 업비트 체결 시각. 응답에 없으면 {@code null} */
    private static Instant tradedAt(UpbitTicker ticker) {
        return ticker.tradeTimestamp() == null ? null : Instant.ofEpochMilli(ticker.tradeTimestamp());
    }
}
