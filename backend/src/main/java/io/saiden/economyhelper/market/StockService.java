package io.saiden.economyhelper.market;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.data.DataGoStockClient;
import io.saiden.economyhelper.market.kis.KisMasterClient;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.support.Failover;
import io.saiden.economyhelper.support.FailureReason;
import java.util.List;
import java.util.Locale;
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

    /**
     * 한국 종목코드. 이 형태면 해석할 것이 없으므로 LLM을 건너뛴다.
     *
     * <p>숫자 여섯이 아니라 <b>「첫 자가 숫자인 영숫자 여섯」</b>이다 — 2025년부터 KRX 단축코드에
     * 영숫자가 있다(마스터 실측 {@code 0019K0 TIME 미국나스닥100채권혼합50액티브}, KIS 시세도
     * 받는다). 정규화가 소문자로 내리므로 소문자를 받고 보낼 때 올린다. 첫 자가 숫자라
     * 미국 티커(영문 1~5자)와 겹치지 않는다.
     */
    private static final Pattern KR_STOCK_CODE = Pattern.compile("[0-9][0-9a-z]{5}");

    /**
     * 미국 티커 <b>모양</b>. 영문 1~5자면 사용자가 티커를 직접 쳤을 수 있다.
     *
     * <p>⚠️ <b>이것은 「티커다」가 아니라 「티커일 수 있다」다.</b> 실재는 KIS가 확정한다 —
     * {@link #KR_STOCK_CODE}가 6자리 숫자를 그렇게 쓰는 것과 같은 자리다. 다만 그쪽은 확실해서
     * 바로 조회하고, 이쪽은 <b>다른 길이 다 막힌 뒤 마지막에</b> 쓴다: {@code KO}·{@code SO}처럼
     * 국내 종목명일 수도 있는 짧은 영문이 있어서, 앞세우면 이름 검색을 가로챈다.
     */
    private static final Pattern US_TICKER = Pattern.compile("[A-Za-z]{1,5}");

    /** 한글이 한 자라도 있으면 화면에 그대로 쓴다 — {@link #displayName}. */
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    private final List<DomesticStockClient> domestic;
    private final List<UsStockClient> us;
    private final DataGoStockClient names;
    private final StockListings listings;
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
     * @param names    이름으로 찾는 <b>둘째</b> 길 — 공공데이터포털(주식·ETF, 전일 종가). 한국투자증권에
     *                 종목명 검색이 없어서(조회가 언제나 코드 → 이름 방향이다) SPI가 아니라 직접 든다
     * @param listings 이름으로 찾는 <b>첫째</b> 길 — KIS 종목 마스터의 「이름 → 코드」 색인. 코드를
     *                 주므로 1순위 시세(실시간)·전망·차트가 전부 붙는다. 이것도 이중화 상대가 없다
     */
    public StockService(List<DomesticStockClient> domestic, List<UsStockClient> us,
                        DataGoStockClient names, StockListings listings, StockResolver resolver,
                        DomesticOutlookClient outlooks, UsOutlookClient usOutlooks,
                        io.saiden.economyhelper.market.kis.KisStockApi kisSeries) {
        // 순서는 여기서 정한다 — 주입 순서에 딸려 가면 클래스 이름을 바꾸다 뒤집힌다
        this.domestic = Failover.order(domestic, DOMESTIC_ORDER, StockClient::source);
        this.us = Failover.order(us, US_ORDER, StockClient::source);
        // ⚠️ 순서 목록 **둘을 함께** 본다. 하나만 보면 거짓 경보가 난다 — FMP는 국내 순서에서
        //    일부러 빠져 있다(무료 티어가 한국 종목을 402로 막는다). 어느 목록에도 없는 것만
        //    구성 실수다. 기동을 막지 않고 로그로 남긴다.
        // ⚠️ **클라이언트 목록도 둘을 다 넘긴다.** 한동안 domestic만 넘겼는데, 주입되는 목록이
        //    타입으로 갈려 있어(DomesticStockClient / UsStockClient) 미국 전용 구현은 감사
        //    대상에 아예 들어오지 않았다 — 새 UsStockClient를 US_ORDER에 안 적으면 조용히
        //    떨어지고 그것이 이 장치가 막으려던 바로 그 사고다
        java.util.stream.Stream.of(
                        Failover.unordered(domestic, StockClient::source, DOMESTIC_ORDER, US_ORDER),
                        Failover.unordered(us, StockClient::source, DOMESTIC_ORDER, US_ORDER))
                .flatMap(java.util.List::stream)
                .forEach(dropped -> log.error(
                        "[stock] {} 클라이언트가 어느 순서에도 없어 영영 안 불립니다", dropped.source()));
        this.names = names;
        this.listings = listings;
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
                Optional<Answer> found = usAnswer(resolved.get());
                if (found.isPresent()) {
                    return found;
                }
                // LLM이 US라고는 했는데 티커를 못 냈거나 그 티커가 시세에 없다.
                // 국내가 코드 → 이름 → 원문으로 세 번 시도하는 것과 같은 자리다
                return usByTicker(query, resolved.get().code());
            }
            // 국내 지수는 조회가 통째로 다르다 — 종목코드가 없고 시가총액도 없다
            if (resolved.filter(ResolvedStock::isIndex).isPresent()) {
                // 업종코드는 비워 보낸다. 설정에 있는 지수면 KIS가 제 표에서 채우고,
                // 없으면 이름으로 찾는 2순위가 맡는다 — LLM에게 지수코드를 지어내게 두지 않는다.
                // 목표주가는 없다(낼 주체가 없다). 차트는 붙는다 — 열쇠가 그 이름이다
                // ⚠️ 이름 없는 지수 해석은 조회할 열쇠가 없다. 그대로 넘기면 KIS 표 조회가 null 키로
                //    NPE를 kisStock 브레이커 안에서 던져 HTTP 없이 실패가 쌓인다(Unsupported가 막으려던 모양)
                String name = resolved.get().name();
                Optional<Answer> byIndex = resolved.get().hasName()
                        ? index(new Index(name, null))
                                .map(quote -> new Answer(quote, null, Series.domesticIndex(name)))
                        : Optional.empty();
                // ⚠️ 여기서 무조건 돌려주면 한 방향 문이 된다 — LLM이 ETF를 INDEX로 잘못
                //    읽으면 국내 지수 조회가 빈손인 채로 끝나고 아래 그물에 닿지 못한다
                return byIndex.isPresent() ? byIndex : usByTicker(query, null);
            }
            Optional<Answer> found = search(resolved, query, key);
            // ⚠️ 국내 이름 검색까지 다 빈손이다. 원문이 미국 티커 모양이면 한 번 더 —
            //    LLM이 죽거나 거절해도 사용자가 친 글자로 찾을 수 있어야 한다
            return found.isPresent() ? found : usByTicker(query, null);
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
                .map(form -> form.toUpperCase(Locale.ROOT))
                .findFirst();
    }

    /**
     * LLM이 판단한 종목코드 → 정식명 → 원문 순으로 시도한다.
     *
     * <p><b>LLM의 답을 그대로 믿지 않는다.</b> 코드도 이름도 실제 시세에서 다시 찾고,
     * 걸리지 않으면 버린다 — 지어낸 종목코드는 조회 결과가 비어 자연히 걸러진다.
     *
     * <p>⚠️ <b>존재하는 틀린 코드</b>는 그 그물에 안 걸린다. ETF는 이름이 비슷한 코드가 수십 개라
     * LLM이 {@code KODEX 미국나스닥100}의 코드에 {@code TIME 미국나스닥100액티브}라는 이름을 붙여
     * 올 수 있고, 그러면 KIS가 멀쩡히 답해 <b>다른 ETF가 나간다</b>. 그래서 코드와 이름이 색인에서
     * <b>서로 다른 종목을 가리키면 이름을 먼저 믿는다.</b> 다만 이름으로도 못 찾으면 그때 코드를
     * 쓴다 — {@code 네이버}는 상장명이 {@code NAVER}라 이름 경로가 전부 빈손이고, 그 자리를 메우는
     * 것이 LLM의 코드다.
     *
     * <p>이름 경로는 둘이다. 색인({@link StockListings})이 먼저 — 코드를 주므로 1순위 시세와 전망·
     * 차트가 붙는다. 없으면 공공데이터포털(전일 종가, 코드 없음).
     *
     * <p>마지막에 원문으로 한 번 더 시도하는 것이 <b>LLM 장애에 대한 폴백</b>이다.
     * {@code 삼성전자}·{@code 하이닉스}처럼 이름을 그대로 친 경우는 LLM 없이도 걸린다 —
     * Gemini가 죽었다고 {@code /stock} 전체가 멈추면 안 된다. {@code 타임나스닥100}은 LLM 없이는
     * 빈손이다({@code 타임} ≠ {@code TIME}) — 그 소리를 맞추는 것이 LLM의 몫이다.
     */
    private Optional<Answer> search(Optional<ResolvedStock> resolved, String query, String cacheKey) {
        if (resolved.isPresent()) {
            ResolvedStock found = resolved.get();
            boolean codeFirst = codeAgreesWithName(found);
            if (codeFirst && found.hasCode()) {
                Optional<Answer> byCode = stockAnswer(found.code());
                if (byCode.isPresent()) {
                    return byCode;
                }
                log.info("[stock] LLM이 준 코드 {}가 시세에 없습니다 — 이름으로 다시 찾습니다", found.code());
            }
            if (found.hasName()) {
                Optional<Answer> byName = byListing(found.name())
                        .or(() -> byName(found.name()).map(Answer::of));
                if (byName.isPresent()) {
                    return byName;
                }
            }
            if (!codeFirst) {
                // 이름으로는 못 찾았다 — 상장명이 영문인 종목(NAVER)이 여기다. 코드를 믿는다
                Optional<Answer> byCode = stockAnswer(found.code());
                if (byCode.isPresent()) {
                    return byCode;
                }
            }
        }
        // LLM이 죽었거나 특정하지 못했다. 원문이 그대로 종목명일 수 있다 — 군더더기를 뗀 형태까지
        // 색인에 대 본다(「타임나스닥100 etf」의 etf)
        for (String form : QueryNormalizer.forLookup(query)) {
            Optional<Answer> byListing = byListing(form);
            if (byListing.isPresent()) {
                return byListing;
            }
        }
        return byName(cacheKey).map(Answer::of);
    }

    /**
     * LLM이 준 코드와 이름이 <b>같은 종목</b>인가. 둘 중 하나가 없거나, 색인이 그 코드를 모르거나,
     * 색인을 못 받았으면 참 — 그때는 지금까지처럼 코드가 먼저다(실재는 KIS가 확정한다).
     */
    private boolean codeAgreesWithName(ResolvedStock found) {
        if (!found.hasCode() || !found.hasName()) {
            return true;
        }
        try {
            Optional<KisMasterClient.Listing> listed = listings.byCode(found.code());
            if (listed.isEmpty() || StockListings.agrees(listed.get(), found.name())) {
                return true;
            }
            log.info("[stock] LLM이 준 코드 {}({})와 이름 '{}'이 다른 종목을 가리켜 이름을 먼저 믿습니다",
                    found.code(), listed.get().name(), found.name());
            return false;
        } catch (RuntimeException e) {
            log.warn("[stock] 종목 색인을 못 읽어 코드·이름 대조를 건너뜁니다: {}", FailureReason.of(e));
            return true;
        }
    }

    /**
     * 색인에서 이름으로 코드를 찾아 <b>1순위 경로</b>로 조회한다 — 실시간 시세에 전망·차트까지 붙는다.
     * 색인 실패는 {@link #byName}과 같은 이유로 삼킨다: 다음 수(공공데이터포털)가 있다.
     */
    private Optional<Answer> byListing(String name) {
        try {
            return listings.find(name).flatMap(listing -> stockAnswer(listing.code()));
        } catch (RuntimeException e) {
            log.warn("[stock] '{}' 색인 검색 실패 — 공공데이터포털로 넘어갑니다: {}", name, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /** 미국 종목·지수 하나. <b>지수</b>는 설정에 KIS 심볼이 있으면 1순위가 맡고 없으면 FMP로 간다.
     * <b>종목</b>은 표를 타지 않는다 — KIS가 거래소를 스스로 찾는다. */
    private Optional<StockQuote> usQuote(ResolvedStock resolved) {
        if (!resolved.hasCode()) {
            // 미국은 이름으로 되짚을 경로가 없다 — search-name은 프랑크푸르트 상장이 먼저 걸린다
            log.info("[stock] '{}'의 티커를 특정하지 못했습니다", resolved.name());
            return Optional.empty();
        }
        return usQuote(new UsSymbol(resolved.code(), displayName(resolved)));
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

    /**
     * 미국 심볼을 이미 아는 경우 — 아침 브리핑의 나스닥·S&amp;P500·시총 상위가 여기로 온다.
     *
     * <p><b>시세만 받던 자리였다.</b> 그래서 목표주가·실적발표일이 {@code /stock} 검색에만
     * 나오고 브리핑에는 없었는데, 요청은 「검색 <b>및 알림</b> 때 보여 준다」였다. 지수는
     * {@link #withUsOutlook}이 알아서 걸러내므로 심볼 목록을 나눠 둘 필요가 없다.
     *
     * <p>대가는 FMP 호출이다 — 브리핑의 미국 <b>종목</b> 수 × <b>2회</b>
     * ({@code price-target-consensus}·{@code earnings}). 실측 설정은 둘(엔비디아·애플)이라
     * 하루 4회이고 한도가 250회다. 12시간 캐시라 그 사이 검색은 호출을 나눠 쓴다.
     *
     * <p>⚠️ <b>셋이었다가 둘로 줄었다</b> — 투자의견을 화면에서 걷어내면서
     * {@code grades-consensus} 호출도 함께 지웠다({@code FmpUsOutlookClient} 참고).
     * 이 문단이 한동안 「× 3회 · 하루 6회」로 남아 있었다.
     */
    public List<Answer> usAnswersOf(List<UsSymbol> symbols) {
        return symbols.stream().map(this::usAnswer).flatMap(Optional::stream).toList();
    }

    /**
     * 시세 하나에 <b>전망을 붙인 것</b> — 전망은 {@code null}일 수 있다.
     *
     * <p>왜 {@link StockQuote}에 필드를 더하지 않았나. 그쪽은 <b>1분 캐시</b>이고 전망은
     * <b>12시간</b>이다. 한 항목으로 묶으면 하루에 한 번 바뀌는 값을 1분마다 다시 받게 되고
     * (60배), {@code kis-quote}는 {@code TypeReference<StockQuote>}로 타입이 못 박혀 있어
     * 다른 모양을 담으면 <b>쓸 때는 넘어가고 읽을 때 깨진다</b>.
     */
    public record Answer(StockQuote quote, StockOutlook outlook, Series series) {

        /**
         * 전망도 일봉 열쇠도 없는 것 — <b>이름 검색</b>이 그렇다.
         *
         * <p>공공데이터포털 이름 검색 결과에서 코드를 쓰지 않는다(주식 API는 코드를 안 주고, ETF API는
         * 주지만 그 경로는 KIS 색인이 실패한 뒤에만 오므로 코드가 있어도 1순위로 되짚지 않는다) — 그래서 전망도 차트도 붙일 열쇠가
         * 없다. 그때는 값만 나가고 그 둘이 빠진다 — 보충이지 폴백이 아니다.
         */
        public static Answer of(StockQuote quote) {
            return new Answer(quote, null, null);
        }
    }

    /**
     * 일봉을 <b>어느 경로로 물을지</b> — 화면에 쓰는 값이 아니라 조회 열쇠다.
     *
     * <p>{@link StockQuote}에는 이것이 없다(화면이 안 쓴다). 그런데 차트를 그리려면 필요하고,
     * 아는 자리는 조회한 곳까지다. 셋을 한 타입으로 묶는 이유는 <b>부르는 쪽이 갈리지 않게</b>
     * 하려는 것이다 — 예전에는 검색이 국내 종목만 차트를 냈고 브리핑이 지수를 따로 처리했다.
     *
     * @param kind 어느 시장·어느 엔드포인트인가
     * @param key  그 경로가 요구하는 열쇠. 국내 종목은 종목코드({@code 005930}), 국내 지수는
     *             <b>이름</b>({@code 코스피} — 업종코드는 설정 표가 안다), 미국은 심볼
     *             ({@code AAPL}·{@code ^IXIC})
     */
    public record Series(Kind kind, String key) {

        public enum Kind { DOMESTIC_STOCK, DOMESTIC_INDEX, US }

        public static Series domesticStock(String code) {
            return new Series(Kind.DOMESTIC_STOCK, code);
        }

        public static Series domesticIndex(String name) {
            return new Series(Kind.DOMESTIC_INDEX, name);
        }

        public static Series us(String symbol) {
            return new Series(Kind.US, symbol);
        }
    }

    /**
     * <b>원문을 미국 티커로 한 번 더</b> — LLM 장애·거절에 대한 폴백이다.
     *
     * <p>국내는 기회가 셋인데({@code search}: 코드 → 이름 → 원문) <b>미국은 하나뿐이었다.</b>
     * {@code usQuote}가 {@code code}가 없으면 그 자리에서 포기하므로, LLM이 티커를 안 내놓으면
     * {@code /stock JEPI}가 통째로 빈손이었다 — 사용자가 <b>티커를 정확히 쳤는데도</b> 그랬다.
     *
     * <p>지어내는 것이 아니다. 조회하는 것은 <b>사용자가 친 글자 그대로</b>이고 실재는 KIS가
     * 확정한다 — 없는 티커는 빈 문자열로 와서 자연히 걸러진다({@code KisStockApi.usStock}).
     * 이름은 티커를 그대로 쓴다: 티커를 친 사람에게 돌려줄 이름이 그것뿐이다.
     *
     * <p>{@code QueryNormalizer}가 검색어를 소문자로 내리므로 <b>여기서 대문자로 올린다.</b>
     */
    private Optional<Answer> usByTicker(String query, String already) {
        Optional<String> ticker = tickerShaped(query);
        if (ticker.isEmpty()) {
            return Optional.empty();
        }
        String symbol = ticker.get();
        // ⚠️ 해석기가 이미 이 심볼을 줬으면 그 조회가 방금 실패한 것이다 — 똑같은 것을 다시
        //    물어도 결과가 같고, 거래소 셋을 한 번 더 훑어 간격 문에서 3초를 더 쓴다
        if (symbol.equals(already)) {
            return Optional.empty();
        }
        log.info("[stock] '{}'를 미국 티커로 한 번 더 찾습니다", symbol);
        return usAnswer(new UsSymbol(symbol, symbol));
    }

    /**
     * 검색어에서 <b>미국 티커 모양</b>을 꺼낸다.
     *
     * <p>⚠️ <b>{@link QueryNormalizer#forLookup}을 탄다 — 원문을 그대로 보면 안 된다.</b>
     * 예전에는 {@code query.strip()}에 정규식을 걸었는데, 그러면 {@code /stock JEPI 주가}·
     * {@code /stock JEPI?}에서 <b>폴백이 통째로 꺼진다.</b> 같은 클래스의 {@link #directCode}는
     * 처음부터 {@code forLookup}을 쓰고 있었다 — 한 클래스 안에서 두 조회가 다른 규칙을 쓰던 셈이다.
     *
     * <p>{@code forLookup}은 소문자로 내리고 군더더기를 뗀 후보를 준다. 티커는 대문자여야
     * 하므로 여기서 올린다.
     */
    private static Optional<String> tickerShaped(String query) {
        return QueryNormalizer.forLookup(query).stream()
                .filter(candidate -> US_TICKER.matcher(candidate).matches())
                .findFirst()
                .map(candidate -> candidate.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 화면에 적을 이름 — <b>한글이 아니면 티커로 적는다.</b>
     *
     * <p>국내 종목·코인은 한글로 나가는데 미국만 영문 장문이면 같은 통에서 표기가 갈린다 —
     * {@code 애플}을 물었는데 {@code Apple Inc.}가 돌아오는 그 자리다. LLM이 한국어 이름을
     * 주면 그것을 쓰고, 영문을 주면 <b>티커</b>가 짧고 일관된다({@link #usByTicker}가 이미
     * 티커를 이름으로 쓴다).
     *
     * <p>⚠️ <b>이 판단을 프롬프트에 맡기지 않는다 — 맡겼다가 회귀를 만들었다.</b>
     * 「name은 사용자가 부른 이름으로」를 규칙으로 넣었더니 실측에서 <b>{@code 삼전}과
     * {@code 나스닥}이 빈손</b>이 됐다(문단 하나가 모델의 다른 판단을 흔들었다). 프롬프트는
     * 이렇게 부서지므로, 코드로 정할 수 있는 것은 코드로 정한다.
     */
    private static String displayName(ResolvedStock resolved) {
        String name = resolved.name();
        if (name == null || name.isBlank()) {
            return resolved.code();
        }
        return HANGUL.matcher(name).find() ? name : resolved.code();
    }

    /** 검색으로 찾은 미국 종목. 브리핑은 {@link #usAnswersOf}로 들어와 같은 자리에서 만난다. */
    private Optional<Answer> usAnswer(ResolvedStock resolved) {
        return withUsOutlook(usQuote(resolved), resolved.code());
    }

    private Optional<Answer> usAnswer(UsSymbol symbol) {
        return withUsOutlook(usQuote(symbol), symbol.symbol());
    }

    /**
     * 시세에 전망을 붙인다 — <b>지수에는 붙이지 않는다.</b>
     *
     * <p>목표주가와 실적발표일은 증권사·기업이 <b>기업</b>에 대해 내는 것이다. {@code ^IXIC}에
     * 목표가를 낼 주체가 없으므로 부르지 않는다 — 호출을 아끼는 것이 아니라 있을 수 없는
     * 값을 묻지 않는 것이다(FMP는 하루 250회이고 이쪽은 심볼당 2회를 쓴다).
     *
     * <p>검색과 브리핑이 <b>이 한 자리를 나눠 쓴다.</b> 두 벌로 두면 「지수를 걸러낸다」가
     * 두 곳에 적히고 한쪽만 고쳐지는 날이 온다.
     */
    private Optional<Answer> withUsOutlook(Optional<StockQuote> quote, String symbol) {
        if (quote.isEmpty()) {
            return Optional.empty();
        }
        // 통화가 없으면 지수다 — StockQuote가 지역·통화로 그것을 이미 가른다.
        // 지수에는 전망을 안 붙이지만 차트는 붙는다(둘의 조건이 다르다)
        boolean index = quote.get().currency() == StockQuote.Money.NONE;
        return quote.map(found -> new Answer(found,
                index ? null : usOutlookOf(symbol), Series.us(symbol)));
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
    public List<io.saiden.economyhelper.market.chart.DailyBar> dailyBarsOf(Series series) {
        return switch (series.kind()) {
            case DOMESTIC_STOCK -> kisSeries.dailyBars(series.key());
            case DOMESTIC_INDEX -> kisSeries.dailyBarsOfIndex(series.key());
            // 지수와 종목이 다른 엔드포인트다 — 한 경로로 덮었다가 PATH에서 물렸다
            // (KisStockApi.usStockSeries에 실측이 적혀 있다). 갈라내는 것은 그쪽이 한다
            case US -> kisSeries.dailyBarsOfUs(series.key());
        };
    }

    /** 국내 종목 하나 — <b>여기가 종목코드가 있는 유일한 자리</b>라 전망을 여기서 붙인다. */
    private Optional<Answer> stockAnswer(String code) {
        return stock(code).map(quote ->
                new Answer(quote, outlookOf(code), Series.domesticStock(code)));
    }

    /**
     * 그 종목의 목표주가 — <b>못 구하면 {@code null}이고 시세는 그대로 나간다.</b>
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
