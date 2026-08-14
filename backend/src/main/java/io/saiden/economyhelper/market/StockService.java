package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.fmp.FmpApi;
import io.saiden.economyhelper.market.fmp.FmpApi.FmpQuote;
import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import io.saiden.economyhelper.text.QueryNormalizer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code /stock {검색어}} — 검색어를 종목으로 옮기고 시세를 가져온다.
 *
 * <p><b>후보를 고르는 데는 LLM을 쓰지 않는다.</b> API가 시가총액을 함께 주기 때문이다.
 * 실측에서 6개 중 5개가 시총 정렬만으로 정답이었고, 우선주·자회사를 정확히 제쳤다:
 *
 * <table>
 *   <tr><th>검색어</th><th>후보</th><th>1위(시총)</th><th>제친 것</th></tr>
 *   <tr><td>삼성</td><td>26</td><td>삼성전자</td><td>삼성전자우</td></tr>
 *   <tr><td>현대차</td><td>5</td><td>현대차</td><td>현대차2우B</td></tr>
 *   <tr><td>카카오</td><td>4</td><td>카카오</td><td>카카오뱅크</td></tr>
 * </table>
 *
 * {@code CryptoService}에서 24시간 거래대금이 하는 일과 같다 — 후보를 좁힌 뒤
 * 인기도 신호로 하나를 고른다.
 *
 * <p>실패한 하나는 {@code 네이버}였다. <b>상장명이 {@code NAVER}(로마자)</b>라 한글로는
 * 안 걸린다. 그 자리를 {@link StockResolver}(LLM)가 메운다 — 약칭({@code 삼전})과
 * 자연어 군더더기({@code 오늘 삼성전자 주가})도 같은 장치가 처리한다.
 *
 * <p><b>지수도 같은 명령으로 받는다.</b> {@code /stock 코스피}는 종목이 아니라 지수 API로 간다 —
 * 별도 명령을 만들지 않은 건 사용자가 "주가"와 "지수"를 굳이 구분해 치지 않기 때문이다.
 *
 * <p><b>국내와 미국을 함께 다룬다.</b> 어느 쪽인지는 {@link StockResolver}가 판단하고 조회처가
 * 갈린다 — 국내 종목은 공공데이터포털(전일 종가), 국내 지수는 지수 API, 미국은 종목·지수가
 * 같은 FMP 엔드포인트다.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 함께 보여줄 다른 후보 수. 너무 많으면 메시지가 지저분해진다. */
    private static final int MAX_ALTERNATIVES = 3;

    /** 한국 종목코드. 이 형태면 해석할 것이 없으므로 LLM을 건너뛴다. */
    private static final java.util.regex.Pattern KR_STOCK_CODE =
            java.util.regex.Pattern.compile("\\d{6}");

    private final StockPriceApi api;
    private final MarketIndexApi indexApi;
    private final FmpApi fmpApi;
    private final StockResolver resolver;

    public StockService(StockPriceApi api, MarketIndexApi indexApi, FmpApi fmpApi,
                        StockResolver resolver) {
        this.api = api;
        this.indexApi = indexApi;
        this.fmpApi = fmpApi;
        this.resolver = resolver;
    }

    /**
     * @return 시가총액 1위 후보의 시세. 걸리는 종목이 없거나 조회에 실패하면 {@link Optional#empty()}
     */
    public Optional<StockMatch> quote(String query) {
        String key = StockResolver.cacheKeyOf(query);
        if (key.isEmpty()) {
            return Optional.empty();
        }

        try {
            // 6자리 숫자는 종목코드 그 자체다 — LLM에게 물어볼 것이 없다.
            // 아침 브리핑이 quotesOf로 쓰는 경로와 같은 길이고, 결과도 같아야 한다
            Optional<String> code = directCode(query);
            if (code.isPresent()) {
                List<StockPrice> found = api.searchByCode(code.get());
                if (!found.isEmpty()) {
                    return Optional.of(toMatch(found));
                }
                log.info("[stock] 종목코드 {}에 걸리는 종목이 없습니다", code.get());
                return Optional.empty();
            }

            Optional<ResolvedStock> resolved = resolver.resolve(key);

            // 미국은 종목과 지수가 같은 엔드포인트다 — AAPL도 ^IXIC도 /stable/quote로 간다
            if (resolved.filter(ResolvedStock::isUs).isPresent()) {
                return usQuote(resolved.get());
            }

            // 국내 지수는 조회 API가 통째로 다르다 — 종목코드가 없고 시가총액도 없다
            if (resolved.filter(ResolvedStock::isIndex).isPresent()) {
                return indexQuote(resolved.get().name());
            }

            List<StockPrice> found = lookup(resolved, key);
            if (found.isEmpty()) {
                log.info("[stock] '{}'에 걸리는 종목이 없습니다", query);
                return Optional.empty();
            }
            return Optional.of(toMatch(found));
        } catch (RuntimeException e) {
            log.error("[stock] '{}' 조회 실패: {}", query, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 검색어가 곧 종목코드인 경우.
     *
     * <p>{@code 005930 주가}처럼 군더더기가 붙은 형태도 잡아야 하므로
     * {@link QueryNormalizer#forLookup}이 만든 두 형태를 다 본다.
     *
     * @return 6자리 숫자 형태. 아니면 {@link Optional#empty()} — LLM으로 간다
     */
    private static Optional<String> directCode(String query) {
        return QueryNormalizer.forLookup(query).stream()
                .filter(form -> KR_STOCK_CODE.matcher(form).matches())
                .findFirst();
    }

    /** 종목코드를 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 종목들이 여기로 온다. */
    public List<StockQuote> quotesOf(List<String> codes) {
        return codes.stream()
                .map(code -> {
                    try {
                        return best(api.searchByCode(code)).map(StockService::toQuote);
                    } catch (RuntimeException e) {
                        log.error("[stock] {} 조회 실패: {}", code, e.toString());
                        return Optional.<StockQuote>empty();
                    }
                })
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 지수명을 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 지수들이 여기로 온다.
     *
     * <p>{@link #quotesOf}와 같은 모양으로 <b>이름마다 따로 실패한다</b> —
     * 코스닥이 안 나온다고 코스피까지 빠질 이유가 없다.
     */
    public List<StockQuote> indicesOf(List<String> names) {
        return names.stream()
                .map(name -> {
                    try {
                        return Optional.ofNullable(indexApi.searchByName(name)).map(StockService::toQuote);
                    } catch (RuntimeException e) {
                        log.error("[index] {} 조회 실패: {}", name, e.toString());
                        return Optional.<StockQuote>empty();
                    }
                })
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 미국 심볼을 이미 아는 경우 — 아침 브리핑의 나스닥·S&amp;P500·시총 상위가 여기로 온다.
     *
     * <p>{@link #quotesOf}·{@link #indicesOf}와 같은 모양으로 심볼마다 따로 실패한다.
     */
    public List<StockQuote> usQuotesOf(List<String> symbols) {
        return symbols.stream()
                .map(symbol -> {
                    try {
                        return Optional.ofNullable(fmpApi.quote(symbol)).map(StockService::toQuote);
                    } catch (RuntimeException e) {
                        log.error("[fmp] {} 조회 실패: {}", symbol, e.toString());
                        return Optional.<StockQuote>empty();
                    }
                })
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 미국 종목·지수 하나.
     *
     * <p>함께 보여줄 후보가 없다 — 심볼이 정확히 하나를 가리키기 때문이다.
     * <b>LLM이 지어낸 심볼은 FMP가 빈 배열을 줘서 자연히 걸러진다</b>(국내와 같은 방어).
     */
    private Optional<StockMatch> usQuote(ResolvedStock resolved) {
        if (!resolved.hasCode()) {
            // 미국은 이름으로 되짚을 경로가 없다 — search-name은 프랑크푸르트 상장이 먼저 걸린다
            log.info("[fmp] '{}'의 티커를 특정하지 못했습니다", resolved.name());
            return Optional.empty();
        }
        FmpQuote quote = fmpApi.quote(resolved.code());
        if (quote == null) {
            return Optional.empty();
        }
        return Optional.of(new StockMatch(toQuote(quote), List.of()));
    }

    /** 국내 지수 하나. 함께 보여줄 후보가 없다 — 완전일치로 하나만 고르기 때문이다. */
    private Optional<StockMatch> indexQuote(String name) {
        MarketIndex index = indexApi.searchByName(name);
        if (index == null) {
            log.info("[stock] '{}' 지수를 찾지 못했습니다", name);
            return Optional.empty();
        }
        return Optional.of(new StockMatch(toQuote(index), List.of()));
    }

    /**
     * LLM이 판단한 종목코드 → 정식명 → 원문 순으로 시도한다.
     *
     * <p><b>LLM의 답을 그대로 믿지 않는다.</b> 코드도 이름도 실제 시세 API에서 다시 찾고,
     * 걸리지 않으면 버린다 — 지어낸 종목코드는 조회 결과가 비어 자연히 걸러진다.
     *
     * <p>마지막에 원문으로 한 번 더 시도하는 것이 <b>LLM 장애에 대한 폴백</b>이다.
     * {@code 삼성전자}·{@code 하이닉스}처럼 이름을 그대로 친 경우는 LLM 없이도 걸린다 —
     * Gemini가 죽었다고 {@code /stock} 전체가 멈추면 안 된다.
     */
    private List<StockPrice> lookup(Optional<ResolvedStock> resolved, String cacheKey) {
        if (resolved.isPresent()) {
            ResolvedStock stock = resolved.get();
            if (stock.hasCode()) {
                List<StockPrice> byCode = api.searchByCode(stock.code());
                if (!byCode.isEmpty()) {
                    return byCode;
                }
                log.info("[stock] LLM이 준 코드 {}가 시세에 없습니다 — 이름으로 다시 찾습니다", stock.code());
            }
            if (stock.hasName()) {
                List<StockPrice> byName = api.searchByName(stock.name());
                if (!byName.isEmpty()) {
                    return byName;
                }
            }
        }

        // LLM이 죽었거나 특정하지 못했다. 원문이 그대로 종목명일 수 있다
        return api.searchByName(cacheKey);
    }

    /**
     * 시가총액 1위를 고르고 나머지를 후보로 남긴다.
     *
     * <p>같은 종목의 여러 날짜가 섞여 오므로 <b>가장 최근 기준일만</b> 남긴 뒤 비교한다 —
     * 안 그러면 어제 삼성전자와 그제 삼성전자가 서로 다른 후보로 보인다.
     */
    private static StockMatch toMatch(List<StockPrice> prices) {
        List<StockPrice> latest = onlyLatestDate(prices);
        List<StockPrice> ranked = latest.stream()
                .sorted(Comparator.comparing(StockService::marketCap).reversed())
                .toList();

        List<String> alternatives = ranked.stream()
                .skip(1)
                .map(StockPrice::itmsNm)
                .distinct()
                .limit(MAX_ALTERNATIVES)
                .toList();

        return new StockMatch(toQuote(ranked.get(0)), alternatives);
    }

    private static Optional<StockPrice> best(List<StockPrice> prices) {
        return onlyLatestDate(prices).stream().max(Comparator.comparing(StockService::marketCap));
    }

    private static List<StockPrice> onlyLatestDate(List<StockPrice> prices) {
        String latest = prices.stream().map(StockPrice::basDt).max(Comparator.naturalOrder()).orElse("");
        return prices.stream().filter(p -> latest.equals(p.basDt())).toList();
    }

    private static BigDecimal marketCap(StockPrice price) {
        return parse(price.mrktTotAmt());
    }

    /** 국내 종목 — <b>전일 종가</b>다. {@code realtime=false}가 메시지에서 "종가"로 드러난다. */
    private static StockQuote toQuote(StockPrice price) {
        return new StockQuote(price.srtnCd(), price.itmsNm(), price.mrktCtg(),
                parse(price.clpr()), StockQuote.Money.KRW,
                atSeoulMidnight(price.basDt()), false, false, parse(price.mrktTotAmt()));
    }

    /** 국내 지수 — 종목코드가 없고 통화도 없다. */
    private static StockQuote toQuote(MarketIndex index) {
        return new StockQuote(null, index.idxNm(), index.idxCsf(), parse(index.clpr()),
                StockQuote.Money.NONE,
                atSeoulMidnight(index.basDt()), false, true, BigDecimal.ZERO);
    }

    /**
     * 미국 종목·지수 — <b>현재가</b>다.
     *
     * <p>지수 판별을 {@code ^} 접두로 한다. FMP가 종목과 지수를 같은 엔드포인트로 주고
     * 응답에 구분 필드가 없어서, 심볼 관례가 유일한 단서다({@code ^IXIC}·{@code ^GSPC}·{@code ^DJI}).
     */
    private static StockQuote toQuote(FmpQuote quote) {
        boolean index = quote.symbol() != null && quote.symbol().startsWith("^");
        Instant at = quote.timestamp() == null ? Instant.now() : Instant.ofEpochSecond(quote.timestamp());
        return new StockQuote(quote.symbol(), quote.name(), quote.exchange(),
                quote.price(), index ? StockQuote.Money.NONE : StockQuote.Money.USD,
                at, true, index,
                quote.marketCap() == null ? BigDecimal.ZERO : quote.marketCap());
    }

    /** 종가일을 시각으로 옮긴다. 그날 장이 끝난 값이므로 KST 자정으로 두고 표기는 날짜만 쓴다. */
    private static Instant atSeoulMidnight(String basDt) {
        return LocalDate.parse(basDt, BAS_DT).atStartOfDay(SEOUL).toInstant();
    }

    /** 값이 비거나 깨져 있어도 조회 전체를 실패시키지 않는다 — 0으로 보면 순위에서 뒤로 밀릴 뿐이다. */
    private static BigDecimal parse(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 고른 종목과 <b>함께 걸렸던 다른 후보들</b>.
     *
     * <p>후보를 같이 보여주는 이유는 되묻기를 피하기 위해서다 — 텔레그램에서 "어느 것입니까"를
     * 물으면 대화가 두 번 오간다. 1위를 주고 다른 것들을 한 줄로 덧붙이면 한 번에 끝난다.
     */
    public record StockMatch(StockQuote quote, List<String> alternatives) {}
}
