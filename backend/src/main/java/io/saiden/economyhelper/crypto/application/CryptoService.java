package io.saiden.economyhelper.crypto.application;

import io.saiden.economyhelper.crypto.application.port.out.BinanceClient;
import io.saiden.economyhelper.crypto.application.port.out.CoinResolver;
import io.saiden.economyhelper.crypto.application.port.out.UpbitClient;
import io.saiden.economyhelper.crypto.domain.BinancePrice;
import io.saiden.economyhelper.crypto.domain.BinanceSymbol;
import io.saiden.economyhelper.crypto.domain.CryptoQuote.Quote;
import io.saiden.economyhelper.crypto.domain.CryptoQuote;
import io.saiden.economyhelper.crypto.domain.ResolvedCoin;
import io.saiden.economyhelper.crypto.domain.UpbitMarket;
import io.saiden.economyhelper.crypto.domain.UpbitMarketIndex;
import io.saiden.economyhelper.crypto.domain.UpbitTicker;
import io.saiden.economyhelper.shared.domain.DailyBar;
import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.shared.support.QueryNormalizer;
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
 * <p><b>{@link CoinResolver}(LLM)가 메우는 자리는 업비트에 아예 없는 코인뿐이다.</b>
 * 실측: 원화 마켓 283개에 {@code KRW-BNB}가 없는데 바이낸스에는 {@code BNBUSDT}가 있다.
 * 이때는 후보 목록 자체가 없어 거래대금으로 가릴 대상이 없고, 바이낸스는 심볼만 주고 한글
 * 이름을 주지 않아 {@code 비앤비}를 받을 방법이 없다.
 *
 * <p><b>거래소마다 포트가 따로다</b>({@link UpbitClient}·{@link BinanceClient}). 두 칸은 폴백이
 * 아니라 나란히 서는 값이라 한 출처 목록으로 평탄화하면 김치 프리미엄이 성립하지 않는다.
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private final UpbitClient upbit;
    private final BinanceClient binance;
    private final CoinResolver resolver;

    /**
     * <b>업비트가 시각을 안 줄 때 쓸 시계.</b>
     *
     * <p>⚠️ <b>{@code Instant.now()}를 직접 부르지 않는다</b> — 테스트가 시각을 얼릴 수 없으면
     * 골든이 그 줄을 못 덮는다. 걸리는 자리는 <b>업비트에 없는 코인</b>(바이낸스 전용 {@code BNB})이다.
     */
    private final Clock clock;

    public CryptoService(UpbitClient upbit, BinanceClient binance, CoinResolver resolver,
                         Clock clock) {
        this.upbit = upbit;
        this.binance = binance;
        this.resolver = resolver;
        this.clock = clock;
    }

    /**
     * @return 두 거래소 시세. 어느 쪽에도 없으면 {@link Optional#empty()}
     */
    public Optional<CryptoQuote> quote(String query) {
        Optional<CryptoQuote> byName = byUpbitName(query).map(this::withBinance);
        if (byName.isPresent()) {
            return byName;
        }
        // 업비트에 걸리는 것이 없다. 여기서만 LLM에게 티커를 묻는다
        return resolve(query)
                .map(ResolvedCoin::symbol)
                .filter(Objects::nonNull)
                .flatMap(this::quoteOf);
    }

    /**
     * 티커 해석 — <b>실패는 빈손이다.</b> 해석기 안쪽은 제 실패를 삼키지만 거기 걸린 {@code @Cacheable}
     * 프록시(Redis)는 메서드 밖에서 던진다. 그 밖의 예외는 삼키지 않는다 — 웹훅이 「못 찾았다」가 아니라
     * 「잠시 후 다시」로 답하게 둔다.
     */
    private Optional<ResolvedCoin> resolve(String query) {
        try {
            return resolver.resolve(QueryNormalizer.normalize(query));
        } catch (RuntimeException e) {
            log.warn("[crypto] '{}' 해석 실패: {}", query, FailureReason.of(e));
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
        // 두 거래소를 겹쳐 묻는다 — 둘 다 실패를 값으로 삼키고 서로를 기다리지 않는다. 호출 수는 그대로다.
        // 심볼 조립을 여기서 하지 않는다 — 테더만 USDTUSD인 규칙이 두 군데로 갈리면
        // 이 경로(LLM이 티커를 준 코인)만 조용히 옛 규칙을 따르게 된다
        var sides = Concurrently.both(
                () -> upbitSide(market),
                () -> BinanceSymbol.of(market).map(this::binancePrice).orElse(Quote.NOT_LISTED));
        UpbitSide upbit = sides.first();
        Quote binance = sides.second();

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
                atOrNow(upbit.at()),
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
            markets = upbit.krwMarkets();
        } catch (RuntimeException e) {
            log.warn("[crypto] 업비트 마켓 목록 조회 실패: {}", FailureReason.of(e));
            return new UpbitSide(null, Quote.FAILED, null);
        }

        UpbitMarket listed = markets.stream()
                .filter(m -> m.market().equalsIgnoreCase(market)).findFirst().orElse(null);
        if (listed == null) {
            return new UpbitSide(null, Quote.NOT_LISTED, null);
        }
        try {
            return upbit.tickers(List.of(listed.market())).stream().findFirst()
                    .map(ticker -> new UpbitSide(listed,
                            Quote.of(ticker.tradePrice(), ticker.change()), ticker.tradedAt()))
                    .orElseGet(() -> new UpbitSide(listed, Quote.FAILED, null));
        } catch (RuntimeException e) {
            log.warn("[crypto] {} 업비트 시세 실패: {}", market, FailureReason.of(e));
            return new UpbitSide(listed, Quote.FAILED, null);
        }
    }

    /**
     * <p><b>400({@code Invalid symbol.})만</b> "그 거래소에 없다"로 읽는다. 451(지역 차단)·
     * 429·418(한도 초과)도 4xx지만 잠시 뒤 다시 치면 되는 것이라, 같이 묶으면 사용자가
     * 재시도할 수 있는 상황에서 영영 없다고 말하게 된다.
     */
    private Quote binancePrice(String symbol) {
        try {
            return binance.prices(List.of(symbol)).stream().findFirst()
                    .map(CryptoService::binanceQuote)
                    .orElse(Quote.NOT_LISTED);
        } catch (RuntimeException e) {
            // 바이낸스가 "그 심볼 없다"를 좁은 타입으로 준다 — 400을 여기서 다시 읽지 않는다.
            // 418(IP 밴)·451(지역 차단)도 4xx지만 그건 '없음'이 아니라 '지금 못 봄'이다
            if (e instanceof BinanceClient.UnknownSymbol) {
                return Quote.NOT_LISTED;
            }
            // 밴 중이라 아예 안 부른 것이다. '조회 실패'로 뭉치면 사용자가 다시 치고,
            // 그 재시도가 바이낸스에는 밴을 늘리는 호출이 된다 — 그래서 갈라 적는다
            if (e instanceof BinanceClient.Banned banned) {
                log.warn("[crypto] {} 바이낸스 밴 중이라 부르지 않았습니다 — {}까지", symbol, banned.until());
                return Quote.banned(banned.until());
            }
            // 이유를 갈라 남긴다 — 상대 장애·브레이커·리미터·451을 가르는 것은 FailureReason이다
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
        List<UpbitMarket> candidates;
        try {
            candidates = UpbitMarketIndex.candidates(forms, upbit.krwMarkets());
        } catch (RuntimeException e) {
            // 목록을 못 받았다 — 이름을 맞출 수 없을 뿐이다. LLM이 준 티커로 두 거래소를 각각 묻는다(quoteOf)
            log.warn("[crypto] '{}' 업비트 마켓 목록 조회 실패 — 티커 해석으로 넘어갑니다: {}", query, FailureReason.of(e));
            return Optional.empty();
        }
        if (candidates.isEmpty()) {
            log.info("[crypto] '{}'에 걸리는 업비트 마켓이 없습니다", query);
            return Optional.empty();
        }
        try {
            return pickAndQuote(candidates);
        } catch (RuntimeException e) {
            // ⚠️ 이름은 업비트에서 맞았다 — 여기서 빈손을 주면 LLM으로 넘어가 다른 코인이 답이 될 수 있다.
            //    후보가 하나면 그 코인을 업비트 「조회 실패」로 적고(바이낸스는 따로 붙는다), 여럿이면 어느 것인지
            //    거래대금 없이는 못 고르므로 던진다 — 웹훅이 「잠시 후 다시」로 답한다
            log.warn("[crypto] '{}' 업비트 시세 실패: {}", query, FailureReason.of(e));
            if (candidates.size() == 1) {
                UpbitMarket only = candidates.get(0);
                return Optional.of(new CryptoQuote(only.koreanName(), only.market(), clock.instant(),
                        Quote.FAILED, Quote.FAILED));
            }
            throw e;
        }
    }

    /**
     * 차트용 일봉 — <b>실패를 삼키지 않는다.</b>
     *
     * <p>부르는 쪽이 「차트만 빼고 보낸다」를 판단해야 하므로 던진다. 삼키면 클라이언트에 걸린
     * 브레이커가 정상 반환을 보고 성공을 센다.
     *
     * @param market 업비트 마켓 코드. 바이낸스 쪽은 쓰지 않는다 — 원화 시세는 업비트가 주고
     *               그쪽은 밴 게이트 옆이라 호출을 늘리는 값이 다르다
     */
    public List<DailyBar> dailyBars(String market) {
        return upbit.dailyBars(market);
    }

    /** 마켓 코드를 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 코인들이 여기로 온다. */
    public List<CryptoQuote> quotesOf(List<String> markets) {
        if (markets.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, UpbitMarket> byCode = byCode(upbit.krwMarkets());
            List<String> known = markets.stream().filter(byCode::containsKey).toList();
            if (known.isEmpty()) {
                log.warn("[crypto] 설정된 마켓이 업비트 목록에 없습니다: {}", markets);
                return List.of();
            }
            return withBinance(upbit.tickers(known).stream()
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

        List<String> symbols = symbolByMarket.values().stream().sorted().toList();
        Map<String, BinancePrice> priceBySymbol;
        try {
            priceBySymbol = binance.prices(symbols).stream()
                    .collect(Collectors.toMap(BinancePrice::symbol, Function.identity()));
        } catch (BinanceClient.Banned banned) {
            // 밴 중이라 호출 자체가 없었다. 배치 경로(브리핑)도 화면에 그렇게 적어야
            // 사용자가 「왜 코인 표만 반쪽인가」를 묻지 않는다
            log.warn("[crypto] 바이낸스 밴 중이라 부르지 않았습니다 — {}까지, 업비트 시세만 내보냅니다",
                    banned.until());
            return allBinance(quotes, symbolByMarket, Quote.banned(banned.until()));
        } catch (BinanceClient.UnknownSymbol unknown) {
            // ⚠️ 미상장 심볼이 하나라도 끼면 바이낸스가 배치 전체를 400으로 거절한다 — 「조회 실패」로 적으면
            //    상장된 코인까지 실패로 찍히고 김치 프리미엄이 사라진다. 단건이면 그 코인이 미상장이고,
            //    여럿이면 하나씩 다시 물어 없는 것만 미상장으로 적는다(이 갈래에서만 호출이 늘어난다)
            if (symbols.size() == 1) {
                return allBinance(quotes, symbolByMarket, Quote.NOT_LISTED);
            }
            Map<String, Quote> bySymbol = symbols.stream()
                    .collect(Collectors.toMap(Function.identity(), this::binancePrice));
            return quotes.stream().map(quote -> {
                String symbol = symbolByMarket.get(quote.market());
                return withBinanceState(quote, symbol == null ? Quote.NOT_LISTED : bySymbol.get(symbol));
            }).toList();
        } catch (RuntimeException e) {
            // 어느 심볼을 물었는지 함께 남긴다 — 배치라서 한 줄이 여럿을 대표하고,
            // 심볼이 없으면 "무엇이 빠졌는지"를 로그만 보고는 알 수 없다
            log.warn("[crypto] 바이낸스 조회 실패 {} — 업비트 시세만 내보냅니다: {}",
                    symbols, FailureReason.of(e));
            return allBinance(quotes, symbolByMarket, Quote.FAILED);
        }

        return quotes.stream().map(quote -> {
            String symbol = symbolByMarket.get(quote.market());
            BinancePrice price = symbol == null ? null : priceBySymbol.get(symbol);
            return withBinanceState(quote, price == null
                    ? Quote.NOT_LISTED
                    : binanceQuote(price));
        }).toList();
    }

    /** 하나짜리 — 목록 판을 그대로 쓴다. 한 건이 들어가면 반드시 한 건이 나온다. */
    private CryptoQuote withBinance(CryptoQuote quote) {
        return withBinance(List.of(quote)).getFirst();
    }

    private static CryptoQuote withBinanceState(CryptoQuote quote, Quote binance) {
        return new CryptoQuote(quote.name(), quote.market(), quote.at(), quote.upbit(), binance);
    }

    /** 배치 조회가 통째로 안 됐을 때 — 바이낸스 심볼이 있는 코인은 {@code ifListed}, 없는 코인은 미상장. */
    private static List<CryptoQuote> allBinance(List<CryptoQuote> quotes, Map<String, String> symbolByMarket,
                                                Quote ifListed) {
        return quotes.stream()
                .map(quote -> withBinanceState(quote,
                        symbolByMarket.containsKey(quote.market()) ? ifListed : Quote.NOT_LISTED))
                .toList();
    }

    private static Map<String, UpbitMarket> byCode(List<UpbitMarket> markets) {
        return markets.stream().collect(Collectors.toMap(UpbitMarket::market, Function.identity()));
    }

    /** 체결 시각을 모르면 조회 시각으로 대신한다. */
    private Instant atOrNow(Instant at) {
        return at == null ? clock.instant() : at;
    }

    /**
     * 후보가 여럿이면 거래대금 1위를 고른다.
     *
     * <p>후보 전부의 시세를 한 번에 받으므로, 고르는 것과 값을 얻는 것이 같은 호출로 끝난다.
     */
    private Optional<CryptoQuote> pickAndQuote(List<UpbitMarket> candidates) {
        List<String> codes = candidates.stream().map(UpbitMarket::market).toList();
        List<UpbitTicker> tickers = upbit.tickers(codes);
        if (tickers.isEmpty()) {
            return Optional.empty();
        }

        Map<String, UpbitMarket> byCode = byCode(candidates);

        return tickers.stream()
                .filter(ticker -> ticker.accTradePrice24h() != null && byCode.containsKey(ticker.market()))
                .max(Comparator.comparing(UpbitTicker::accTradePrice24h))
                .map(ticker -> toQuote(byCode.get(ticker.market()), ticker));
    }

    /** {@code static}이 아닌 이유는 {@link #clock}이다 — 벽시계를 직접 읽지 않는다. */
    private CryptoQuote toQuote(UpbitMarket market, UpbitTicker ticker) {
        // 바이낸스 쪽은 withBinance가 나중에 채운다 — 업비트 조회와 별개 호출이다
        return new CryptoQuote(market.koreanName(), market.market(),
                atOrNow(ticker.tradedAt()),
                Quote.of(ticker.tradePrice(), ticker.change()), Quote.FAILED);
    }


    /** 값이 없으면({@code null}·0) {@link Quote#FAILED}다 — 0원은 값이 아니다. */
    private static Quote binanceQuote(BinancePrice price) {
        return Quote.of(price.lastPrice(), price.change());
    }
}
