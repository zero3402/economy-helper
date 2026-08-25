package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import io.saiden.economyhelper.market.binance.BinanceApi;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.binance.BinanceSymbol;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.support.FailureReason;
import io.saiden.economyhelper.market.upbit.UpbitApi.UpbitTicker;
import io.saiden.economyhelper.market.upbit.UpbitMarket;
import io.saiden.economyhelper.market.upbit.UpbitMarketIndex;
import io.saiden.economyhelper.text.QueryNormalizer;
import java.math.BigDecimal;
import java.time.Clock;
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

    private final UpbitApi upbitApi;
    private final BinanceApi binanceApi;
    private final CryptoResolver resolver;

    /**
     * <b>업비트가 시각을 안 줄 때 쓸 시계.</b>
     *
     * <p>⚠️ <b>{@code Instant.now()}를 직접 부르지 않는다.</b> 이 저장소는 시각을 읽는 곳마다
     * {@code Clock}을 주입받는다({@code StockPriceApi}·{@code KisStockApi}·
     * {@code WeatherService}·{@code NewsService}…) — 여기만 벽시계를 직접 읽고 있었고,
     * 그러면 <b>테스트가 그 시각을 얼릴 수 없어</b> 골든이 그 줄을 못 덮는다.
     * 걸리는 자리는 <b>업비트에 없는 코인</b>(바이낸스 전용 {@code BNB} 같은 것)이다.
     */
    private final Clock clock;

    public CryptoService(UpbitApi upbitApi, BinanceApi binanceApi, CryptoResolver resolver,
                         Clock clock) {
        this.upbitApi = upbitApi;
        this.binanceApi = binanceApi;
        this.resolver = resolver;
        this.clock = clock;
    }

    /**
     * @return 두 거래소 시세. 어느 쪽에도 없으면 {@link Optional#empty()}
     */
    public Optional<CryptoQuote> quote(String query) {
        try {
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
        } catch (RuntimeException e) {
            // 출처 호출의 실패는 아래 메서드들이 이미 삼킨다. 이 그물이 잡는 것은 그 밖,
            // 특히 resolver.resolve()에 걸린 @Cacheable 프록시다 — Redis가 죽으면 캐시 계층이
            // 던지는데 그건 CryptoResolver 안쪽 try가 못 잡는다(메서드 밖에서 나는 예외다).
            // StockService가 같은 이유로 같은 그물을 쳐 두었는데 여기와 WeatherFacade에는
            // 없었다. 그래서 같은 장애에서 /stock은 "찾지 못했습니다"가 나가고 /crypto는
            // 아무 답도 안 갔다 — 판단이 셋으로 갈려 있던 자리다
            log.error("[crypto] '{}' 조회 실패: {}", query, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 티커 하나로 <b>두 거래소를 각각</b> 조회한다.
     *
     * <p>한쪽이 없어도 다른 쪽을 내보낸다 — 그게 이 명령의 요지다. 다만 없는 쪽을 빼지 않고
     * {@code NOT_LISTED}로 적는다. 둘 다 없을 때만 "찾지 못했다"가 된다.
     */
    private Optional<CryptoQuote> quoteOf(String symbol) {
        String market = "KRW-" + symbol;
        UpbitSide upbit = upbitSide(market);
        // 심볼 조립을 여기서 하지 않는다 — 테더만 USDTUSD인 규칙이 두 군데로 갈리면
        // 이 경로(LLM이 티커를 준 코인)만 조용히 옛 규칙을 따르게 된다
        Quote binance = BinanceSymbol.of(market).map(this::binancePrice).orElse(Quote.NOT_LISTED);

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
                upbit.at() == null ? clock.instant() : upbit.at(),
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
            log.warn("[crypto] 업비트 마켓 목록 조회 실패: {}", FailureReason.of(e));
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
            log.warn("[crypto] {} 업비트 시세 실패: {}", market, FailureReason.of(e));
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
            // 바이낸스가 "그 심볼 없다"를 좁은 타입으로 준다 — 400을 여기서 다시 읽지 않는다.
            // 418(IP 밴)·451(지역 차단)도 4xx지만 그건 '없음'이 아니라 '지금 못 봄'이다
            if (e instanceof BinanceApi.UnknownSymbol) {
                return Quote.NOT_LISTED;
            }
            // 밴 중이라 아예 안 부른 것이다. '조회 실패'로 뭉치면 사용자가 다시 치고,
            // 그 재시도가 바이낸스에는 밴을 늘리는 호출이 된다 — 그래서 갈라 적는다
            if (e instanceof BinanceApi.Banned banned) {
                log.warn("[crypto] {} 바이낸스 밴 중이라 부르지 않았습니다 — {}까지", symbol, banned.until());
                return Quote.banned(banned.until());
            }
            // ⚠️ 이유를 갈라 남긴다. 예전에는 e.toString() 한 줄이라 상대 장애·브레이커 열림·
            //    리미터 거절·지역 차단(451)이 화면에서도 로그에서도 '조회 실패' 하나로 뭉쳤다.
            //    앞의 셋은 잠시 뒤 낫고 451은 영영 안 낫는데, 그 넷을 못 가르면 다음 사람이
            //    코드와 API를 파헤치게 된다 — 실제로 그랬다
            log.warn("[crypto] {} 바이낸스 시세 실패: {}", symbol, FailureReason.of(e));
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
            log.error("[crypto] '{}' 업비트 조회 실패: {}", query, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /** 마켓 코드를 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 코인들이 여기로 온다. */
    /**
     * 차트용 일봉 — <b>실패를 삼키지 않는다.</b>
     *
     * <p>부르는 쪽이 「차트만 빼고 보낸다」를 판단해야 하므로 던진다. 삼키면 클라이언트에 걸린
     * 브레이커가 정상 반환을 보고 성공을 센다.
     *
     * @param market 업비트 마켓 코드. 바이낸스 쪽은 쓰지 않는다 — 원화 시세는 업비트가 주고
     *               그쪽은 밴 게이트 옆이라 호출을 늘리는 값이 다르다
     */
    public List<io.saiden.economyhelper.market.chart.DailyBar> dailyBars(String market) {
        return upbitApi.dailyBars(market);
    }

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
            log.error("[crypto] 시세 조회 실패 {}: {}", markets, FailureReason.of(e));
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
            // 원화 마켓이 아니어서 심볼을 유도할 수 없는 것들뿐이다 — 부를 것이 없다
            return quotes.stream().map(quote -> withBinanceState(quote, Quote.NOT_LISTED)).toList();
        }

        Map<String, BinancePrice> priceBySymbol;
        try {
            priceBySymbol = binanceApi.prices(symbolByMarket.values().stream().sorted().toList()).stream()
                    .collect(Collectors.toMap(BinancePrice::symbol, Function.identity()));
        } catch (BinanceApi.Banned banned) {
            // 밴 중이라 호출 자체가 없었다. 배치 경로(브리핑)도 화면에 그렇게 적어야
            // 사용자가 「왜 코인 표만 반쪽인가」를 묻지 않는다
            log.warn("[crypto] 바이낸스 밴 중이라 부르지 않았습니다 — {}까지, 업비트 시세만 내보냅니다",
                    banned.until());
            return quotes.stream()
                    .map(quote -> withBinanceState(quote, symbolByMarket.containsKey(quote.market())
                            ? Quote.banned(banned.until()) : Quote.NOT_LISTED))
                    .toList();
        } catch (RuntimeException e) {
            // 어느 심볼을 물었는지 함께 남긴다 — 배치라서 한 줄이 여럿을 대표하고,
            // 심볼이 없으면 "무엇이 빠졌는지"를 로그만 보고는 알 수 없다
            log.warn("[crypto] 바이낸스 조회 실패 {} — 업비트 시세만 내보냅니다: {}",
                    symbolByMarket.values().stream().sorted().toList(), FailureReason.of(e));
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

    /** {@code static}이 아닌 이유는 {@link #clock}이다 — 벽시계를 직접 읽지 않는다. */
    private CryptoQuote toQuote(UpbitMarket market, UpbitTicker ticker) {
        Instant at = tradedAt(ticker);
        // 바이낸스 쪽은 withBinance가 나중에 채운다 — 업비트 조회와 별개 호출이다
        return new CryptoQuote(market.koreanName(), market.market(),
                at == null ? clock.instant() : at,
                Quote.of(ticker.tradePrice(), percentOf(ticker)), Quote.FAILED);
    }

    /** 업비트만 등락률을 비율로 준다 — 화면이 단위를 알 필요가 없도록 여기서 %로 옮긴다. */
    private static BigDecimal percentOf(UpbitTicker ticker) {
        return PercentChange.fromRatio(ticker.signedChangeRate());
    }

    /** @return 업비트 체결 시각. 응답에 없으면 {@code null} */
    private static Instant tradedAt(UpbitTicker ticker) {
        return ticker.tradeTimestamp() == null ? null : Instant.ofEpochMilli(ticker.tradeTimestamp());
    }
}
