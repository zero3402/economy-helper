package io.saiden.economyhelper.market.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.DomesticStockClient;
import io.saiden.economyhelper.market.PercentChange;
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
    private final Map<String, UsSymbol> usSymbols;

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
                       EconomyHelperProperties properties) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
        this.headers = headers;
        this.clock = clock;
        Digest digest = properties == null ? null : properties.digest();
        this.indices = byKey(digest == null ? null : digest.indices(), Index::name);
        this.usSymbols = byKey(digest == null ? null : digest.usSymbols(), UsSymbol::symbol);
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
    @RateLimiter(name = "kis")
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
    @RateLimiter(name = "kis")
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
    @RateLimiter(name = "kis")
    @CircuitBreaker(name = "kisStock")
    public StockQuote quote(UsSymbol symbol) {
        UsSymbol target = known(symbol);
        if (!target.hasKis()) {
            // ^IXIC를 그대로 물으면 KIS는 모른다(COMP여야 한다). 종목은 거래소 코드가 필요하다.
            // 설정에 대응이 없으면 어차피 만들 수 없는 요청이라 부르지 않는다
            throw new IllegalStateException("KIS 조회 키가 없는 미국 심볼입니다: " + symbol.symbol());
        }
        return target.isIndex() ? usIndex(target) : usStock(target);
    }

    /**
     * 설정 표에서 조회 키를 채운다 — 브리핑은 이미 채워 오고 <b>검색은 비워 온다.</b>
     *
     * <p>표에 있으면 <b>이름도 설정 것을 쓴다.</b> 그래야 같은 종목이 브리핑과 검색에서 같은
     * 이름으로 나간다 — 캐시 한 칸을 둘이 나눠 쓰기도 한다.
     */
    private UsSymbol known(UsSymbol symbol) {
        UsSymbol configured = usSymbols.get(symbol.symbol());
        return configured == null || symbol.hasKis() ? symbol : configured;
    }

    private Index known(Index index) {
        Index configured = indices.get(index.name());
        return configured == null || index.hasCode() ? index : configured;
    }

    /** 해외지수 — 환율과 같은 엔드포인트·같은 스키마다. 다른 것은 시장 코드와 심볼뿐이다. */
    private StockQuote usIndex(UsSymbol symbol) {
        KisChartPrice.Quote quote = request(KisChartPrice.class, US_INDEX_TR,
                "미국 지수 " + symbol.name(),
                uri -> chart(uri, US_INDEX_PATH, OVERSEAS_INDEX, symbol.kisIndex()).build())
                .output();

        require(quote == null ? null : quote.price(), "미국 지수 " + symbol.name());
        return new StockQuote(symbol.name(), quote.price(), quote.changePercent(),
                StockQuote.Money.NONE, StockQuote.Market.US, StockSource.KIS,
                clock.instant(), true);
    }

    private StockQuote usStock(UsSymbol symbol) {
        UsStock.Quote quote = request(UsStock.class, US_STOCK_TR, "미국 종목 " + symbol.symbol(),
                uri -> uri.path(US_STOCK_PATH)
                        // AUTH는 빈 값으로 보낸다. 없으면 안 되고 값도 안 받는다
                        .queryParam("AUTH", "")
                        .queryParam("EXCD", symbol.kisExchange())
                        .queryParam("SYMB", symbol.symbol())
                        .build()).output();

        require(quote == null ? null : quote.price(), "미국 종목 " + symbol.symbol());
        // 달러 등락률 필드가 없다. t_xrat은 원화 환산가 기준이라 여기 쓰면 틀린 값이 나간다
        return new StockQuote(symbol.name(), quote.price(),
                PercentChange.between(quote.price(), quote.previousClose()),
                StockQuote.Money.USD, StockQuote.Market.US, StockSource.KIS,
                clock.instant(), true);
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
     */
    private <T extends KisResponse> T request(Class<T> type, String trId, String what,
                                              Function<UriBuilder, java.net.URI> uri) {
        T response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .headers(headers.of(tokens.token(), trId))
                    .retrieve()
                    .body(type);
        } catch (RuntimeException e) {
            log.warn("[kis] {} 조회 실패: {}", what, e.getClass().getSimpleName());
            throw new IllegalStateException(
                    "KIS " + what + " 조회 실패: " + e.getClass().getSimpleName());
        }
        KisHeaders.verify(response == null ? null : response.resultCode(),
                response == null ? null : response.message(), what);
        return response;
    }

    /** {@code rt_cd}가 0인데 값이 비어 오는 경우 — 없는 종목코드가 그렇다. */
    private static void require(BigDecimal price, String what) {
        if (price == null) {
            throw new IllegalStateException("KIS " + what + " 응답에 현재가가 없습니다");
        }
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
