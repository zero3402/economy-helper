package io.saiden.economyhelper.market.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.KisIndex;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.DomesticStockClient;
import io.saiden.economyhelper.market.PercentChange;
import io.saiden.economyhelper.market.Price;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.UsStockClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * 한국투자증권 시세 — <b>국내와 미국 이중화의 1순위</b>({@code StockService.DOMESTIC}·{@code US}).
 *
 * <p>이 봇에서 유일하게 <b>국내를 실시간으로</b> 주는 출처다. 2순위인 공공데이터포털은 전일
 * 종가뿐이라 오전 9시 브리핑이 장 시작 시각인데 어제 값을 보여 주고 있었다.
 *
 * <p>네 경로 전부 모의 계정으로 실제 호출해 확정했다(2026-08-18). 응답 모양이 서로 달라
 * <b>스키마를 넷으로 나눠 둔다</b> — 하나로 뭉치면 어느 필드가 어느 경로 것인지 사라진다.
 *
 * <table>
 *   <tr><th>무엇</th><th>tr_id</th><th>현재가</th><th>등락률</th></tr>
 *   <tr><td>국내 종목</td><td>{@code FHKST03010100}</td><td>{@code stck_prpr}</td><td>{@code prdy_ctrt}</td></tr>
 *   <tr><td>국내 지수</td><td>{@code FHKUP03500100}</td><td>{@code bstp_nmix_prpr}</td><td>{@code bstp_nmix_prdy_ctrt}</td></tr>
 *   <tr><td>미국 종목</td><td>{@code HHDFS76200200}</td><td>{@code last}</td><td><b>없다</b> — 계산한다</td></tr>
 *   <tr><td>미국 지수</td><td>{@code FHKST03030100}</td><td>{@code ovrs_nmix_prpr}</td><td>{@code prdy_ctrt}</td></tr>
 * </table>
 *
 * <p><b>실측으로 확인한 함정 넷.</b>
 *
 * <ol>
 *   <li><b>지수 등락률 필드 이름이 종목과 다르다.</b> 국내 지수만 {@code bstp_nmix_prdy_ctrt}다 —
 *       종목 이름({@code prdy_ctrt})으로 읽으면 조용히 {@code null}이 되어 등락률이 사라진다
 *   <li><b>국내 지수 이름을 화면에 쓸 수 없다.</b> {@code hts_kor_isnm}이 코스피는
 *       {@code "종합"}, 코스닥은 {@code "KOSDAQ"}으로 온다(실측) — 하나는 무엇의 종합인지
 *       모르고 하나는 로마자다. 버리고 설정 이름을 쓴다
 *   <li><b>미국 종목에 달러 등락률 필드가 없다.</b> {@code t_xrat}은 <b>원화 환산가</b> 기준이라
 *       달러 등락률이 아니다. {@code last}와 {@code base}(전일 종가)로 직접 낸다
 *   <li><b>에러가 HTTP 200 본문에 온다</b>({@code rt_cd=1}). {@link KisHeaders#verify}가 막는다
 * </ol>
 *
 * <p><b>시각 필드를 주지 않는다</b>(미국 종목엔 날짜조차 없다). 그래서 {@code at}은 <b>읽은
 * 시각</b>이고 {@code realtime=true}다 — {@link KisFxClient}가 이미 세운 규칙이다. 캐시가
 * 1분이라 표시 오차도 그 안이다.
 *
 * <p><b>못 주는 것은 던진다.</b> 설정에 KIS 대응이 없는 미국 심볼, 업종코드가 없는 지수가
 * 그렇다 — 빈 값을 돌려주면 {@code StockService}가 폴백하지 못하고 그대로 빈손이 나간다.
 * 이 둘은 <b>호출도 하지 않는다</b>: 어차피 만들 수 없는 요청이라 리미터와 한도만 축낸다.
 */
@Component
public class KisStockApi implements DomesticStockClient, UsStockClient {

    private static final Logger log = LoggerFactory.getLogger(KisStockApi.class);

    private static final String STOCK_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String INDEX_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
    private static final String US_STOCK_PATH = "/uapi/overseas-price/v1/quotations/price-detail";
    /** 환율과 공유하는 경로다 — {@link KisChartPrice} 참조. */
    private static final String US_INDEX_PATH =
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";

    private static final String STOCK_TR = "FHKST03010100";
    private static final String INDEX_TR = "FHKUP03500100";
    private static final String US_STOCK_TR = "HHDFS76200200";
    private static final String US_INDEX_TR = "FHKST03030100";

    /** 거래소 코드({@code price-detail}의 {@code EXCD}). 모르면 이 순서로 찾아본다. */
    private static final String NASDAQ = "NAS";
    private static final String NYSE = "NYS";

    /** FMP·LLM이 미국 지수에 붙이는 접두. KIS는 이 표기를 모른다({@code ^IXIC}가 아니라 {@code COMP}). */
    private static final String INDEX_PREFIX = "^";

    /** 시장 구분. {@code J}가 국내 주식, {@code U}가 국내 업종, {@code N}이 해외지수다. */
    private static final String KRX_STOCK = "J";
    private static final String KRX_INDEX = "U";
    private static final String OVERSEAS_INDEX = "N";

    /**
     * 조회 기간. 오늘만 물으면 휴일·이른 아침에 빈 배열이 온다 — {@code output1}의 현재가는
     * 어차피 하나뿐이라 넉넉히 물어도 파싱은 그대로다({@link KisFxClient}와 같은 이유).
     */
    private static final int LOOKBACK_DAYS = 7;

    private final RestClient restClient;
    private final KisTokenStore tokens;
    private final KisHeaders headers;
    private final Clock clock;
    private final Map<String, Index> indices;
    private final Map<String, String> usIndices;
    private final KisExchangeCache exchanges;
    /**
     * 실제 HTTP 호출 직전에 간격을 지키는 문({@link KisThrottle}).
     *
     * <p>⚠️ <b>애너테이션으로는 부족했다.</b> {@code @RateLimiter}가 {@code @Cacheable} 메서드에
     * 붙어 있으면 <b>메서드 하나에 퍼밋 하나</b>인데, 미국 종목은 그 안에서 거래소를 두 번
     * 물어본다(NAS → NYS). 두 번째 호출이 퍼밋 없이 나갔다.
     *
     * <p>⚠️ <b>퍼밋을 호출마다 얻어도 부족했다.</b> 고정 윈도 리미터는 "1초에 1건"만 보장하고
     * "호출 사이 1초"를 보장하지 않아, 윈도 경계에서 두 호출이 <b>120ms 간격</b>으로 나갔다
     * (실측). KIS는 그걸 {@code 초당 거래건수를 초과하였습니다}로 거절하고, 그러면 그 종목이
     * 빈손이 된다 — 왜 간격이어야 하는지는 {@link KisThrottle}에 실측 표로 적어 뒀다.
     */
    private final KisThrottle throttle;

    /**
     * @param properties 브리핑 설정의 지수·미국 심볼 목록이 <b>KIS 조회 키 표를 겸한다.</b>
     *                   {@code /stock 코스피}는 LLM이 업종코드를 주지 않고(지어내기 쉬운 값이다)
     *                   {@code /stock 애플}도 거래소 코드를 주지 않는다 — 그걸 여기서 채운다.
     *                   표에 없는 것은 조회 키를 만들 수 없어 2순위가 맡는다. 덕분에 설정에 든
     *                   종목은 브리핑과 검색이 <b>같은 출처·같은 이름</b>으로 나간다
     */
    public KisStockApi(RestClient.Builder builder,
                       @Value("${economy-helper.market.kis.base-url}") String baseUrl,
                       KisTokenStore tokens, KisHeaders headers, Clock clock,
                       EconomyHelperProperties properties, KisExchangeCache exchanges,
                       KisThrottle throttle) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.exchanges = exchanges;
        this.throttle = throttle;
        this.tokens = tokens;
        this.headers = headers;
        this.clock = clock;
        Digest digest = properties == null ? null : properties.digest();
        this.indices = byKey(digest == null ? null : digest.indices(), Index::name);
        List<KisIndex> configured = properties == null || properties.market() == null
                || properties.market().kis() == null ? null : properties.market().kis().usIndices();
        this.usIndices = configured == null ? Map.of() : configured.stream()
                .collect(Collectors.toUnmodifiableMap(KisIndex::symbol, KisIndex::kisSymbol));
    }

    /** 설정이 비어 있어도(테스트·최소 구성) 돌아야 한다 — 표가 없으면 KIS가 덜 맡을 뿐이다. */
    private static <T> Map<String, T> byKey(List<T> values, Function<T, String> key) {
        return values == null ? Map.of()
                : values.stream().collect(Collectors.toUnmodifiableMap(key, value -> value));
    }

    @Override
    public StockSource source() {
        return StockSource.KIS;
    }

    @Override
    @Cacheable(cacheNames = "kis-quote", key = "'stock:' + #code")
    @CircuitBreaker(name = "kisStock")
    public StockQuote stock(String code) {
        DomesticStock.Quote quote = request(DomesticStock.class, STOCK_TR, "국내 종목 " + code,
                uri -> chart(uri, STOCK_PATH, KRX_STOCK, code)
                        // 수정주가(액면분할·유무상증자 반영). 0이 원주가인데 화면에 쓰는 것은
                        // '지금 얼마냐'라 분할 전 가격이 섞이면 안 된다
                        .queryParam("FID_ORG_ADJ_PRC", "0")
                        .build()).output();

        require(quote == null ? null : quote.price(), "국내 종목 " + code);
        // 이름은 응답이 준다 — 국내 종목은 KIS가 한글명을 제대로 준다(실측: '삼성전자')
        return new StockQuote(quote.name(), quote.price(), quote.changePercent(),
                StockQuote.Money.KRW, StockQuote.Market.DOMESTIC, StockSource.KIS,
                clock.instant(), true);
    }

    @Override
    // 캐시 키는 코드가 아니라 이름이다. 검색 경로는 코드를 비워 보내므로(설정에서 채운다)
    // 코드로 잡으면 코드 없는 지수가 전부 한 칸을 나눠 쓰게 된다
    @Cacheable(cacheNames = "kis-quote", key = "'index:' + #index.name()")
    @CircuitBreaker(name = "kisStock")
    public StockQuote index(Index index) {
        Index target = known(index);
        if (!target.hasCode()) {
            // KIS에는 지수명 검색이 없다. 코드가 없으면 만들 수 있는 요청이 아예 없다
            throw new IllegalStateException("KIS 지수 조회에 업종코드가 없습니다: " + index.name());
        }
        DomesticIndex.Quote quote = request(DomesticIndex.class, INDEX_TR,
                "국내 지수 " + target.name(),
                uri -> chart(uri, INDEX_PATH, KRX_INDEX, target.code()).build()).output();

        require(quote == null ? null : quote.price(), "국내 지수 " + target.name());
        // 응답의 hts_kor_isnm은 코스피가 '종합', 코스닥이 'KOSDAQ'이다(실측) — 설정 이름을 쓴다
        return new StockQuote(target.name(), quote.price(), quote.changePercent(),
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.KIS,
                clock.instant(), true);
    }

    @Override
    @Cacheable(cacheNames = "kis-quote", key = "'us:' + #symbol.symbol()")
    @CircuitBreaker(name = "kisStock")
    public StockQuote quote(UsSymbol symbol) {
        if (symbol.symbol().startsWith(INDEX_PREFIX)) {
            String kisSymbol = usIndices.get(symbol.symbol());
            if (kisSymbol == null) {
                // ^DJI를 그대로 물으면 KIS는 모른다(.DJI여야 한다). 지수는 ^IXIC → COMP 같은
                // 규칙이 없어 표가 유일한 길이고, 표에 없으면 만들 요청이 아예 없다 —
                // 2순위가 맡는다(FMP 무료 티어는 미국 지수를 다 준다)
                throw new IllegalStateException("KIS 심볼을 모르는 미국 지수입니다: " + symbol.symbol());
            }
            return usIndex(symbol, kisSymbol);
        }
        return usStock(symbol);
    }

    private Index known(Index index) {
        Index configured = indices.get(index.name());
        return configured == null || index.hasCode() ? index : configured;
    }

    /** 해외지수 — 환율과 같은 엔드포인트·같은 스키마다. 다른 것은 시장 코드와 심볼뿐이다. */
    private StockQuote usIndex(UsSymbol symbol, String kisSymbol) {
        KisChartPrice.Quote quote = request(KisChartPrice.class, US_INDEX_TR,
                "미국 지수 " + symbol.name(),
                uri -> chart(uri, US_INDEX_PATH, OVERSEAS_INDEX, kisSymbol).build())
                .output();

        require(quote == null ? null : quote.price(), "미국 지수 " + symbol.name());
        return new StockQuote(symbol.name(), quote.price(), quote.changePercent(),
                StockQuote.Money.NONE, StockQuote.Market.US, StockSource.KIS,
                clock.instant(), true);
    }

    /**
     * 미국 종목 — <b>거래소를 스스로 찾는다.</b>
     *
     * <p><b>왜 탐색하는가.</b> {@code price-detail}은 {@code EXCD}를 요구하는데 사용자도 LLM도
     * 그걸 주지 않는다. 예전에는 설정 표에 있는 심볼만 맡고 나머지는 2순위로 넘겼는데,
     * <b>그 2순위가 비어 있었다</b> — FMP 무료 티어가 심볼별 허용목록으로 막아
     * {@code PATH}·{@code ORCL}·{@code SNOW}가 전부 402였다(실측). 그래서
     * {@code /stock 유아이패스}가 통째로 빈손이었다.
     *
     * <p><b>빗나간 거래소가 에러로 오지 않는다.</b> {@code rt_cd=0}에 41개 필드가 다 오고
     * 값만 빈 문자열이다(실측). 없는 티커도 똑같다 — 그래서 "비었으면 다음 거래소"가 성립한다.
     *
     * <p><b>{@code AMS}(NYSE American)는 넣지 않는다.</b> 소형주 거래소라 얻는 것보다,
     * 빗나갈 때마다 초당 1건 한도를 1초씩 더 태우는 대가가 크다. 거기 있는 종목은 2순위가
     * 맡고, 그래도 없으면 못 찾았다고 답한다.
     */
    private StockQuote usStock(UsSymbol symbol) {
        String what = "미국 종목 " + symbol.symbol();
        String failure = null;
        for (String exchange : exchangesToTry(symbol.symbol())) {
            UsStock.Quote quote;
            try {
                quote = request(UsStock.class, US_STOCK_TR, what,
                        uri -> uri.path(US_STOCK_PATH)
                                // AUTH는 빈 값으로 보낸다. 없으면 안 되고 값도 안 받는다
                                .queryParam("AUTH", "")
                                .queryParam("EXCD", exchange)
                                .queryParam("SYMB", symbol.symbol())
                                .build()).output();
            } catch (RuntimeException e) {
                // ⚠️ 빈 응답만 넘어가면 부족하다. request()는 rt_cd=1(초당 거래건수 초과)에
                //    던지는데, 이 앱키는 초당 1건이라 그게 예외가 아니라 흔한 경로다 —
                //    NAS를 물을 때 스로틀에 걸리면 NYS는 시도조차 못 하고 종목이 빈손이 됐다.
                //    거래소마다 따로 실패하고 전부 실패했을 때만 던진다(StockService.first와 같다)
                log.warn("[stock] {} — {} 조회 실패, 다음 거래소로 넘어갑니다: {}",
                        what, exchange, e.toString());
                failure = e.getMessage();
                continue;
            }
            if (quote == null || !positive(quote.price())) {
                continue;
            }
            exchanges.remember(symbol.symbol(), exchange);
            // 달러 등락률 필드가 없다. t_xrat은 원화 환산가 기준이라 여기 쓰면 틀린 값이 나간다
            return new StockQuote(symbol.name(), quote.price(),
                    PercentChange.between(quote.price(), quote.previousClose()),
                    StockQuote.Money.USD, StockQuote.Market.US, StockSource.KIS,
                    clock.instant(), true);
        }
        // ⚠️ 빈손의 원인이 둘인데 문장은 하나였다 — 값이 정말 없는 것과, 물어보지도 못한 것.
        //    거래소가 전부 예외로 죽었으면 "현재가가 없습니다"는 확인한 적 없는 결론이고,
        //    실제로 그 문장 때문에 무효 토큰이 '상장 폐지된 종목'처럼 읽혔다. 그때는 마지막
        //    실패 이유를 그대로 올린다 — 이미 what이 앞에 붙어 있어 다시 감쌀 것이 없다
        throw new IllegalStateException(
                failure == null ? "KIS " + what + " 응답에 현재가가 없습니다" : failure);
    }

    /**
     * 물어볼 거래소 순서.
     *
     * <p><b>지난번에 찾아 기억해 둔 것이 있으면 그것 하나뿐이다</b> — 그때는 탐색 비용이 없다.
     * 거래소는 바뀌지 않으므로 그 기억은 30일 간다({@link KisExchangeCache}).
     *
     * <p>모르면 나스닥부터 본다. 사용자가 물을 법한 미국 종목이 그쪽에 더 많다.
     */
    private List<String> exchangesToTry(String symbol) {
        String remembered = exchanges.of(symbol);
        return remembered == null ? List.of(NASDAQ, NYSE) : List.of(remembered);
    }

    /** 일자별 차트 셋(국내 종목·국내 지수·해외지수)이 쓰는 공통 파라미터. */
    private UriBuilder chart(UriBuilder uri, String path, String market, String code) {
        return uri.path(path)
                .queryParam("FID_COND_MRKT_DIV_CODE", market)
                .queryParam("FID_INPUT_ISCD", code)
                .queryParam("FID_INPUT_DATE_1", KisHeaders.daysAgo(clock, LOOKBACK_DAYS))
                .queryParam("FID_INPUT_DATE_2", KisHeaders.today(clock))
                .queryParam("FID_PERIOD_DIV_CODE", "D");
    }

    /**
     * 호출 한 번. 네 경로가 응답 타입만 다르고 <b>헤더·에러 처리·비밀 취급이 같다.</b>
     *
     * <p>예외를 그대로 흘리지 않는다 — 헤더에 접근토큰이 실려 있어 로그·모니터링에 남으면
     * 그대로 유출된다({@code FmpApi}·{@code KeximFxClient}가 URL에 실린 키를 가리는 것과 같다).
     * <b>다만 이유는 남긴다</b> — {@link KisHeaders#reasonOf}가 본문에서 두 필드만 꺼낸다.
     */
    private <T extends KisResponse> T request(Class<T> type, String trId, String what,
                                              Function<UriBuilder, java.net.URI> uri) {
        // 호출 하나에 간격 하나 — 거래소를 두 번 물어보면 그 사이도 벌어진다
        throttle.pace();
        T response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .headers(headers.of(tokens.token(), trId))
                    .retrieve()
                    .body(type);
        } catch (RuntimeException e) {
            // 예외 이름만으로는 부족하다 — 무효 토큰이 500으로 오고 이유가 본문에만 있다
            String reason = KisHeaders.reasonOf(e);
            log.warn("[kis] {} 조회 실패: {}", what, reason);
            throw new IllegalStateException("KIS " + what + " 조회 실패: " + reason);
        }
        KisHeaders.verify(response == null ? null : response.resultCode(),
                response == null ? null : response.message(), what);
        return response;
    }

    /**
     * {@code rt_cd}가 0인데 값이 비어 오는 경우 — 없는 종목코드가 그렇다.
     *
     * <p>판단은 {@link Price}가 한다. 이 가드는 여기서 실측으로 만들어졌지만 같은 함정이
     * 형제 셋에도 있어서 공용으로 뽑았다 — 넷으로 갈려 있으면 하나만 고쳐지는 날이 온다.
     */
    private static void require(BigDecimal price, String what) {
        Price.require(price, "KIS " + what);
    }

    private static boolean positive(BigDecimal price) {
        return Price.positive(price);
    }

    /**
     * @param output {@code output1}. {@code output2}는 일자별 배열인데 우리는 현재가만 쓴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DomesticStock(@JsonProperty("rt_cd") String resultCode,
                         @JsonProperty("msg1") String message,
                         @JsonProperty("output1") Quote output) implements KisResponse {

        /**
         * @param name          {@code hts_kor_isnm} — 한글 종목명. <b>지수와 달리 제대로 온다</b>
         * @param price         {@code stck_prpr} — 현재가
         * @param changePercent {@code prdy_ctrt} — 전일 대비율(%)
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Quote(@JsonProperty("hts_kor_isnm") String name,
                     @JsonProperty("stck_prpr") BigDecimal price,
                     @JsonProperty("prdy_ctrt") BigDecimal changePercent) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DomesticIndex(@JsonProperty("rt_cd") String resultCode,
                         @JsonProperty("msg1") String message,
                         @JsonProperty("output1") Quote output) implements KisResponse {

        /**
         * <b>필드 이름이 종목과 다르다.</b> 지수만 {@code bstp_nmix_} 접두가 붙는데, 특히
         * 등락률을 종목 이름({@code prdy_ctrt})으로 읽으면 조용히 {@code null}이 된다 —
         * 화면에서 등락률만 사라지고 값은 멀쩡해 알아채기 어렵다.
         *
         * <p>{@code hts_kor_isnm}은 담지 않는다. 코스피가 {@code "종합"}, 코스닥이
         * {@code "KOSDAQ"}으로 와서(실측) 어느 쪽도 화면에 쓸 수 없다 — 이름은 설정에서 온다.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Quote(@JsonProperty("bstp_nmix_prpr") BigDecimal price,
                     @JsonProperty("bstp_nmix_prdy_ctrt") BigDecimal changePercent) {}
    }

    /**
     * 미국 종목 — <b>여기만 {@code output1}이 아니라 {@code output}이다.</b>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsStock(@JsonProperty("rt_cd") String resultCode,
                   @JsonProperty("msg1") String message,
                   @JsonProperty("output") Quote output) implements KisResponse {

        /**
         * @param price         {@code last} — 현재가(달러)
         * @param previousClose {@code base} — 전일 종가. <b>등락률을 이 둘로 낸다</b>
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Quote(@JsonProperty("last") BigDecimal price,
                     @JsonProperty("base") BigDecimal previousClose) {}
    }
}
