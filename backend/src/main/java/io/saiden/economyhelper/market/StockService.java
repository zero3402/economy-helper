package io.saiden.economyhelper.market;

import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * <p><b>국내만 다룬다.</b> 미국 주식은 지원하지 않는다 — {@code /stock AAPL}은 "찾지 못했습니다"가 된다.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 함께 보여줄 다른 후보 수. 너무 많으면 메시지가 지저분해진다. */
    private static final int MAX_ALTERNATIVES = 3;

    private final StockPriceApi api;
    private final MarketIndexApi indexApi;
    private final StockResolver resolver;

    public StockService(StockPriceApi api, MarketIndexApi indexApi, StockResolver resolver) {
        this.api = api;
        this.indexApi = indexApi;
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
            Optional<ResolvedStock> resolved = resolver.resolve(key);

            // 지수는 조회 API가 통째로 다르다 — 종목코드가 없고 시가총액도 없다
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

    /** 지수 하나. 함께 보여줄 후보가 없다 — 완전일치로 하나만 고르기 때문이다. */
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

    private static StockQuote toQuote(StockPrice price) {
        return new StockQuote(price.srtnCd(), price.itmsNm(), price.mrktCtg(),
                parse(price.clpr()), LocalDate.parse(price.basDt(), BAS_DT), parse(price.mrktTotAmt()));
    }

    /** 지수는 종목코드가 없다 — {@code null}이 곧 {@link StockQuote#isIndex()}의 근거다. */
    private static StockQuote toQuote(MarketIndex index) {
        return new StockQuote(null, index.idxNm(), index.idxCsf(), parse(index.clpr()),
                LocalDate.parse(index.basDt(), BAS_DT), BigDecimal.ZERO);
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
