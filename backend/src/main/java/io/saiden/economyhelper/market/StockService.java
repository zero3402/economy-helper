package io.saiden.economyhelper.market;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.data.DataGoStockClient;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.support.Failover;
import io.saiden.economyhelper.support.FailureReason;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
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
 * <p><b>지수도 같은 명령으로 받는다.</b> {@code /stock 코스피}는 종목이 아니라 지수 조회로 간다 —
 * 별도 명령을 만들지 않은 건 사용자가 "주가"와 "지수"를 굳이 구분해 치지 않기 때문이다.
 *
 * <p><b>이중화는 국내와 미국이 따로다</b>({@link #DOMESTIC_ORDER}·{@link #US_ORDER}).
 * 어느 쪽인지는 {@link StockResolver}가 판단한다. {@code FxService}와 같은 규칙으로
 * <b>이 클래스가 순서를 정하고</b>, 클라이언트는 값을 주거나 던진다.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /**
     * 국내 시도 순서. 앞이 1순위다.
     *
     * <p><b>한국투자증권만 실시간을 준다.</b> 공공데이터포털은 전일 종가뿐이라, 폴백이
     * 일어나면 값의 성격 자체가 내려앉는다 — 그 사실은 화면의 출처와 기준 줄이 밝힌다.
     *
     * <p><b>제약이 적은 쪽이 뒤에 선다.</b> KIS는 앱키와 초당 한도가 있고, 공공데이터포털은
     * 하루 1만 회에 종목명 검색까지 된다.
     */
    private static final List<StockSource> DOMESTIC_ORDER =
            List.of(StockSource.KIS, StockSource.DATA_GO);

    /**
     * 미국 시도 순서. <b>2순위가 FMP인 이유는 무료 티어가 한국 종목을 못 주기 때문이다</b>
     * ({@code 005930.KS}가 402). 그래서 국내 폴백 자리에는 설 수 없고 여기에만 선다.
     */
    private static final List<StockSource> US_ORDER =
            List.of(StockSource.KIS, StockSource.FMP);

    /** 한국 종목코드. 이 형태면 해석할 것이 없으므로 LLM을 건너뛴다. */
    private static final Pattern KR_STOCK_CODE = Pattern.compile("\\d{6}");

    private final List<DomesticStockClient> domestic;
    private final List<UsStockClient> us;
    private final DataGoStockClient names;
    private final StockResolver resolver;
    private final DomesticOutlookClient outlooks;
    private final UsOutlookClient usOutlooks;

    /**
     * 일봉을 주는 출처 — <b>이중화되지 않는다.</b> 공공데이터포털은 날짜당 호출 하나라 열나흘이면
     * 열네 번이고, KIS는 한 호출로 {@code output2}를 통째로 준다. 그래서 SPI 목록이 아니라
     * 그 하나를 직접 든다 — 이름 검색에 공공데이터포털을 직접 드는 것과 같은 자리다.
     */
    private final io.saiden.economyhelper.market.kis.KisStockApi kisSeries;

    /**
     * @param names <b>이름으로 찾는 경로는 이중화되지 않는다</b> — 한국투자증권에 종목명 검색이
     *              없어서다(조회가 언제나 코드 → 이름 방향이다). 그래서 SPI 목록이 아니라
     *              공공데이터포털을 직접 든다
     */
    public StockService(List<DomesticStockClient> domestic, List<UsStockClient> us,
                        DataGoStockClient names, StockResolver resolver,
                        DomesticOutlookClient outlooks, UsOutlookClient usOutlooks,
                        io.saiden.economyhelper.market.kis.KisStockApi kisSeries) {
        // 순서는 여기서 정한다 — 주입 순서에 딸려 가면 클래스 이름을 바꾸다 뒤집힌다
        this.domestic = Failover.order(domestic, DOMESTIC_ORDER, StockClient::source);
        this.us = Failover.order(us, US_ORDER, StockClient::source);
        this.names = names;
        this.resolver = resolver;
        this.outlooks = outlooks;
        this.usOutlooks = usOutlooks;
        this.kisSeries = kisSeries;
    }


    /**
     * @return 시가총액 1위 후보의 시세. 걸리는 종목이 없거나 모든 출처가 실패하면
     *         {@link Optional#empty()}
     */
    public Optional<StockQuote> quote(String query) {
        return answer(query).map(Answer::quote);
    }

    /**
     * 시세와 <b>전망</b>을 함께 — {@code /stock} 검색이 쓴다.
     *
     * <p>전망을 여기서 붙이는 이유는 <b>종목코드가 여기까지만 있기 때문</b>이다.
     * {@link StockQuote}에는 코드가 없고(화면이 안 쓴다) 넣을 수도 없다 — 그쪽은 1분 캐시이고
     * 전망은 12시간이라, 한 항목으로 묶으면 하루에 한 번 바뀌는 값을 1분마다 다시 받는다.
     */
    public Optional<Answer> answer(String query) {
        String key = StockResolver.cacheKeyOf(query);
        if (key.isEmpty()) {
            return Optional.empty();
        }

        try {
            // 6자리 숫자는 종목코드 그 자체다 — LLM에게 물어볼 것이 없다.
            // 아침 브리핑이 quotesOf로 쓰는 경로와 같은 길이고, 결과도 같아야 한다
            Optional<String> code = directCode(query);
            if (code.isPresent()) {
                // 없는 코드라고 이름 검색으로 넘기지 않는다 — 6자리 숫자는 종목명일 수 없다
                return stockAnswer(code.get());
            }

            Optional<ResolvedStock> resolved = resolver.resolve(key);

            if (resolved.filter(ResolvedStock::isUs).isPresent()) {
                return usAnswer(resolved.get());
            }
            // 국내 지수는 조회가 통째로 다르다 — 종목코드가 없고 시가총액도 없다
            if (resolved.filter(ResolvedStock::isIndex).isPresent()) {
                // 업종코드는 비워 보낸다. 설정에 있는 지수면 KIS가 제 표에서 채우고,
                // 없으면 이름으로 찾는 2순위가 맡는다 — LLM에게 지수코드를 지어내게 두지 않는다
                // 지수에는 목표주가도 투자의견도 없다 — 낼 주체가 없다
                return index(new Index(resolved.get().name(), null)).map(Answer::of);
            }
            return search(resolved, key);
        } catch (RuntimeException e) {
            // 출처 호출의 실패는 first()가 이미 삼킨다. 여기 그물이 잡는 것은 그 밖,
            // 특히 resolver.resolve()에 걸린 @Cacheable 프록시다 — Redis가 죽으면 캐시 계층이
            // 던지는데 그건 StockResolver 안쪽 try가 못 잡는다(메서드 밖에서 나는 예외다).
            // 웹훅은 어떤 입력·어떤 장애에도 200이어야 한다
            log.error("[stock] '{}' 조회 실패: {}", query, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /**
     * 검색어가 곧 종목코드인 경우.
     *
     * <p>{@code 005930 주가}처럼 군더더기가 붙은 형태도 잡아야 하므로
     * {@link QueryNormalizer#forLookup}이 만든 두 형태를 다 본다.
     */
    private static Optional<String> directCode(String query) {
        return QueryNormalizer.forLookup(query).stream()
                .filter(form -> KR_STOCK_CODE.matcher(form).matches())
                .findFirst();
    }

    /**
     * LLM이 판단한 종목코드 → 정식명 → 원문 순으로 시도한다.
     *
     * <p><b>LLM의 답을 그대로 믿지 않는다.</b> 코드도 이름도 실제 시세에서 다시 찾고,
     * 걸리지 않으면 버린다 — 지어낸 종목코드는 조회 결과가 비어 자연히 걸러진다.
     *
     * <p>마지막에 원문으로 한 번 더 시도하는 것이 <b>LLM 장애에 대한 폴백</b>이다.
     * {@code 삼성전자}·{@code 하이닉스}처럼 이름을 그대로 친 경우는 LLM 없이도 걸린다 —
     * Gemini가 죽었다고 {@code /stock} 전체가 멈추면 안 된다.
     */
    private Optional<Answer> search(Optional<ResolvedStock> resolved, String cacheKey) {
        if (resolved.isPresent()) {
            ResolvedStock found = resolved.get();
            if (found.hasCode()) {
                Optional<Answer> byCode = stockAnswer(found.code());
                if (byCode.isPresent()) {
                    return byCode;
                }
                log.info("[stock] LLM이 준 코드 {}가 시세에 없습니다 — 이름으로 다시 찾습니다", found.code());
            }
            if (found.hasName()) {
                // 이름 검색은 공공데이터포털이고 코드를 돌려주지 않는다 — 전망을 붙일 열쇠가 없다
                Optional<Answer> byName = byName(found.name()).map(Answer::of);
                if (byName.isPresent()) {
                    return byName;
                }
            }
        }
        // LLM이 죽었거나 특정하지 못했다. 원문이 그대로 종목명일 수 있다
        return byName(cacheKey).map(Answer::of);
    }

    /** 미국 종목·지수 하나. <b>지수</b>는 설정에 KIS 심볼이 있으면 1순위가 맡고 없으면 FMP로 간다.
     * <b>종목</b>은 표를 타지 않는다 — KIS가 거래소를 스스로 찾는다. */
    private Optional<StockQuote> usQuote(ResolvedStock resolved) {
        if (!resolved.hasCode()) {
            // 미국은 이름으로 되짚을 경로가 없다 — search-name은 프랑크푸르트 상장이 먼저 걸린다
            log.info("[stock] '{}'의 티커를 특정하지 못했습니다", resolved.name());
            return Optional.empty();
        }
        // LLM이 준 한국어 이름을 쓴다. 국내 종목·코인은 한글로 나가는데 미국만 영문이면
        // 같은 화면에서 표기가 갈린다 — '애플'을 물었는데 'Apple Inc.'가 돌아온다
        return usQuote(new UsSymbol(resolved.code(), resolved.name()));
    }

    /** 종목코드를 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 종목들이 여기로 온다. */
    public List<StockQuote> quotesOf(List<String> codes) {
        return codes.stream().map(this::stock).flatMap(Optional::stream).toList();
    }

    /**
     * 브리핑의 국내 종목 — 시세와 전망을 함께. {@link #quotesOf}와 같은 모양으로
     * <b>종목마다 따로 실패한다.</b>
     */
    public List<Answer> answersOf(List<String> codes) {
        return codes.stream().map(this::stockAnswer).flatMap(Optional::stream).toList();
    }

    /**
     * 지수를 이미 아는 경우 — 아침 브리핑처럼 설정에 박힌 지수들이 여기로 온다.
     *
     * <p>{@link #quotesOf}와 같은 모양으로 <b>지수마다 따로 실패한다</b> —
     * 코스닥이 안 나온다고 코스피까지 빠질 이유가 없다.
     */
    public List<StockQuote> indicesOf(List<Index> indices) {
        return indices.stream().map(this::index).flatMap(Optional::stream).toList();
    }

    /** 미국 심볼을 이미 아는 경우 — 아침 브리핑의 나스닥·S&amp;P500·시총 상위가 여기로 온다. */
    public List<StockQuote> usQuotesOf(List<UsSymbol> symbols) {
        return symbols.stream().map(this::usQuote).flatMap(Optional::stream).toList();
    }

    /**
     * 시세 하나에 <b>전망을 붙인 것</b> — 전망은 {@code null}일 수 있다.
     *
     * <p>왜 {@link StockQuote}에 필드를 더하지 않았나. 그쪽은 <b>1분 캐시</b>이고 전망은
     * <b>12시간</b>이다. 한 항목으로 묶으면 하루에 한 번 바뀌는 값을 1분마다 다시 받게 되고
     * (60배), {@code kis-quote}는 {@code TypeReference<StockQuote>}로 타입이 못 박혀 있어
     * 다른 모양을 담으면 <b>쓸 때는 넘어가고 읽을 때 깨진다</b>.
     */
    public record Answer(StockQuote quote, StockOutlook outlook, String code) {

        /**
         * 전망도 코드도 없는 것 — 지수·미국 종목·이름 검색이 그렇다.
         *
         * <p><b>{@code code}가 왜 여기 있나.</b> {@link StockQuote}에는 종목코드가 없다(화면이
         * 안 쓴다). 그런데 일봉을 물으려면 코드가 필요하고, 그것을 아는 자리는 여기까지다 —
         * 이름 검색으로 찾은 종목은 공공데이터포털이 코드를 안 주므로 {@code null}이고
         * 그때는 차트가 빠진다.
         */
        public static Answer of(StockQuote quote) {
            return new Answer(quote, null, null);
        }
    }

    /**
     * 미국 종목 하나 — <b>지수에는 전망을 붙이지 않는다.</b>
     *
     * <p>목표주가와 투자의견은 증권사가 <b>기업</b>에 대해 내는 것이다. {@code ^IXIC}에
     * 목표가를 낼 주체가 없으므로 부르지 않는다 — 호출을 아끼는 것이 아니라 있을 수 없는
     * 값을 묻지 않는 것이다(FMP는 하루 250회이고 이쪽은 심볼당 2회를 쓴다).
     */
    private Optional<Answer> usAnswer(ResolvedStock resolved) {
        Optional<StockQuote> quote = usQuote(resolved);
        if (quote.isEmpty() || quote.get().currency() == StockQuote.Money.NONE) {
            // 통화가 없으면 지수다 — StockQuote가 지역·통화로 그것을 이미 가른다
            return quote.map(Answer::of);
        }
        // 미국 종목은 일봉 경로가 없어 코드를 담아도 쓸 곳이 없다 — null로 둔다
        return quote.map(found -> new Answer(found, usOutlookOf(resolved.code()), null));
    }

    /** {@link #outlookOf}와 같은 이유로 여기서 삼킨다 — 클라이언트가 삼키면 브레이커가 못 본다. */
    private StockOutlook usOutlookOf(String symbol) {
        try {
            return usOutlooks.outlook(symbol).filter(outlook -> !outlook.isEmpty()).orElse(null);
        } catch (RuntimeException e) {
            log.info("[stock] {} 전망 조회 실패 — 시세만 내보냅니다: {}", symbol, FailureReason.of(e));
            return null;
        }
    }

    /**
     * 차트용 일봉 — <b>실패를 삼키지 않는다.</b>
     *
     * <p>부르는 쪽이 「차트만 빼고 보낸다」를 판단해야 하므로 던진다.
     */
    public List<io.saiden.economyhelper.market.chart.DailyBar> dailyBars(String code) {
        return kisSeries.dailyBars(code);
    }

    /** 국내 종목 하나 — <b>여기가 종목코드가 있는 유일한 자리</b>라 전망을 여기서 붙인다. */
    private Optional<Answer> stockAnswer(String code) {
        return stock(code).map(quote -> new Answer(quote, outlookOf(code), code));
    }

    /**
     * 그 종목의 목표주가·투자의견 — <b>못 구하면 {@code null}이고 시세는 그대로 나간다.</b>
     *
     * <p><b>삼키는 일이 왜 클라이언트가 아니라 여기 있나.</b> 클라이언트가 삼키면 거기 걸린
     * {@code @CircuitBreaker}가 <b>정상 반환을 보고 성공을 센다</b> — 실패율이 영원히 0이라
     * 브레이커가 열리지 않고, KIS가 죽어 있는 동안 조회마다 간격 1초를 헛되이 지불한다.
     * {@code HackerNewsApi}가 실제로 그 상태였고 그 브레이커의 설정값이 전부 죽은 값이었다.
     * 그래서 클라이언트는 던지고 <b>강등은 한 칸 위인 여기서</b> 한다.
     *
     * <p>화면에서 「의견이 없는 종목」과 「조회 실패」가 같은 결과(그 줄이 없음)라는 것은
     * 여전히 맞다 — 그 판단을 브레이커가 실패를 본 <b>뒤에</b> 하는 것뿐이다.
     */
    private StockOutlook outlookOf(String code) {
        try {
            return outlooks.outlook(code).filter(outlook -> !outlook.isEmpty()).orElse(null);
        } catch (RuntimeException e) {
            log.info("[stock] {} 전망 조회 실패 — 시세만 내보냅니다: {}", code, FailureReason.of(e));
            return null;
        }
    }

    private Optional<StockQuote> stock(String code) {
        return first(domestic, client -> client.stock(code), "종목 " + code);
    }

    private Optional<StockQuote> index(Index index) {
        return first(domestic, client -> client.index(index), "지수 " + index.name());
    }

    private Optional<StockQuote> usQuote(UsSymbol symbol) {
        return first(us, client -> client.quote(symbol), "미국 " + symbol.symbol());
    }

    /**
     * 이름 검색 — <b>이중화 상대가 없다.</b> 실패는 "그런 종목이 없다"와 구분되지 않으므로
     * 여기서 삼키고 빈손으로 돌려준다. 부르는 쪽은 이미 그 다음 수를 갖고 있다.
     */
    private Optional<StockQuote> byName(String name) {
        try {
            return names.byName(name);
        } catch (RuntimeException e) {
            log.warn("[stock] '{}' 이름 검색 실패: {}", name, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /**
     * <b>순서대로 시도하고 처음 성공한 것을 쓴다</b> — {@code FxService.usdToKrw}와 같은 모양이다.
     *
     * <p>성공하면 즉시 돌아가므로 <b>1순위가 살아 있는 한 2순위는 호출조차 되지 않는다.</b>
     * FMP 하루 250회를 헛되이 태우지 않는 것이 이 한 줄이다.
     */
    private static <T extends StockClient> Optional<StockQuote> first(
            List<T> clients, Function<T, StockQuote> call, String what) {
        Optional<StockQuote> found = Failover.first(clients, call,
                // 다음 출처가 있으면 조용히 넘어간다. 이게 이중화가 하는 일이다
                (client, e) -> log.warn("[stock] {} — {} 조회 실패, 다음 출처로 넘어갑니다: {}",
                        what, client.source().displayName(), FailureReason.of(e)));
        if (found.isEmpty()) {
            log.info("[stock] {}를 어느 출처에서도 가져오지 못했습니다", what);
        }
        return found;
    }
}
