package io.saiden.economyhelper.market.kis;

import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.chart.DailySeries;
import io.saiden.economyhelper.market.chart.DailyBar;
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
import io.saiden.economyhelper.support.FailureReason;
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
 * <p>경로 전부 모의 계정으로 실제 호출해 확정했다(2026-08-18·08-21). 응답 모양이 서로 달라
 * <b>스키마를 나눠 둔다</b> — 하나로 뭉치면 어느 필드가 어느 경로 것인지 사라진다.
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
    /**
     * 미국 <b>종목</b> 일봉 — 지수 경로와 다른 곳이다({@link #usStockSeries} 참조).
     */
    private static final String US_STOCK_SERIES_PATH =
            "/uapi/overseas-price/v1/quotations/dailyprice";
    /** 환율과 공유하는 경로다 — {@link KisChartPrice} 참조. */
    private static final String US_INDEX_PATH =
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";

    private static final String STOCK_TR = "FHKST03010100";
    private static final String INDEX_TR = "FHKUP03500100";
    private static final String US_STOCK_TR = "HHDFS76200200";
    private static final String US_STOCK_SERIES_TR = "HHDFS76240000";
    private static final String US_INDEX_TR = "FHKST03030100";

    /** 일봉을 달라는 뜻({@code GUBN}) — 1이 주봉, 2가 월봉이다. */
    private static final String DAILY = "0";

    /**
     * 수정주가로 받지 <b>않는다</b>({@code MODP=0}) — 국내 경로와 반대이고, 실측이 그렇게 시켰다.
     *
     * <p>국내는 {@code FID_ORG_ADJ_PRC=0}으로 수정주가를 받는다. 여기서 같은 판단을 하려다
     * 값을 맞춰 보고 뒤집었다 (2026-08-21, 같은 순간의 {@code price-detail}과 대조):
     *
     * <pre>
     *        MODP=1     MODP=0     price-detail last
     * PATH   15.9200    15.9200    15.9200
     * ORCL   143.3600   143.3600   143.3600
     * AAPL   311.9400   312.0600   312.0600   ← MODP=1만 어긋난다
     * </pre>
     *
     * <b>{@code MODP=1}은 가장 최근 행까지 조정 단위로 스케일한다.</b> 그러면 차트의 오른쪽
     * 끝이 «지금 얼마냐»가 아니게 되고, 바로 위 본문이 {@code 312.06 USD}인데 caption은
     * {@code 311.94}가 되어 <b>한 종목의 값이 한 통에 두 개 찍힌다.</b>
     *
     * <p>대가는 안다 — 창 안에 액면분할이 들어오면 원주가는 절벽을 그린다. 그래도 열나흘에
     * 한 번 있을까 한 일이고, 어긋난 끝값은 <b>매일</b> 틀린다. 드문 왜곡보다 상시 모순이 나쁘다.
     */
    private static final String RAW_PRICE = "0";

    /** 거래소 코드({@code price-detail}의 {@code EXCD}). 모르면 이 순서로 찾아본다. */
    private static final String NASDAQ = "NAS";
    private static final String NYSE = "NYS";
    private static final String AMEX = "AMS";

    /** 시장 구분. {@code J}가 국내 주식, {@code U}가 국내 업종, {@code N}이 해외지수다. */
    private static final String KRX_STOCK = "J";
    private static final String KRX_INDEX = "U";
    private static final String OVERSEAS_INDEX = "N";

    /**
     * 조회 기간. 오늘만 물으면 휴일·이른 아침에 빈 배열이 온다 — {@code output1}의 현재가는
     * 어차피 하나뿐이라 넉넉히 물어도 파싱은 그대로다({@link KisFxClient}와 같은 이유).
     */
    private static final int LOOKBACK_DAYS = 7;

    /**
     * 차트용 창. 거래일 열나흘이면 주말이 넷이고 연휴가 끼므로 달력 사흘 남짓을 더 얹는다.
     */
    private static final int SERIES_LOOKBACK_DAYS = 25;

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
     * @param properties <b>지수 조회 키 표만</b> 여기서 온다 — 국내는 업종코드
     *                   ({@code digest.indices}), 미국은 KIS 심볼({@code market.kis.us-indices}).
     *                   ⚠️ <b>미국 <i>종목</i>은 표를 타지 않는다.</b> 거래소를 스스로 찾는다
     *                   (NAS → NYS, 30일 기억). 예전에 {@code digest.us-symbols}가 이 표를
     *                   겸했고, 그래서 목록에 없는 심볼을 통째로 거절해
     *                   {@code /stock 유아이패스}가 빈손이었다 — 그것이 둘을 가른 이유다
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

    /**
     * 국내 종목 일봉 — <b>차트가 그리는 것.</b>
     *
     * <p>⚠️ <b>시세 응답에 이미 오는 값이다.</b> {@link #stock}이 부르는 그 엔드포인트가
     * {@code output2}에 일자별 배열을 함께 주는데 우리가 {@code output1}만 읽고 버려 왔다.
     * 그래도 <b>합치지 않는다</b>: 시세는 1분 캐시이고 일봉은 하루에 한 번 바뀌어서, 한 항목에
     * 담으면 하루치 값을 1분마다 다시 받는다(60배). 게다가 {@code kis-quote}는
     * {@code TypeReference<StockQuote>}로 타입이 못 박혀 있어 다른 모양을 담으면
     * 쓸 때는 넘어가고 읽을 때 깨진다.
     *
     * <p>대가는 조회당 KIS 호출 하나(간격 1초)이고, 12시간 캐시가 그것을 눌러 준다.
     *
     * <p>⚠️ <b>{@code 0.00}은 값이 아니다.</b> 없는 종목코드에 에러가 아니라 0이 오므로
     * 그대로 그리면 차트가 0으로 절벽을 그린다 — {@code DailySeries}가 걸러낸다.
     */
    @Cacheable(cacheNames = CacheNames.STOCK_SERIES, key = "'stock:' + #code")
    @CircuitBreaker(name = "kisStock")
    public java.util.List<DailyBar> dailyBars(String code) {
        DailyChart response = request(DailyChart.class, STOCK_TR, "국내 종목 일봉 " + code,
                uri -> chartWindow(uri, STOCK_PATH, KRX_STOCK, code)
                        .queryParam("FID_ORG_ADJ_PRC", "0")
                        .build());
        return barsOf(response);
    }

    /**
     * 국내 지수 일봉 — 코스피·코스닥.
     *
     * <p>{@link #index}가 부르는 그 엔드포인트가 {@code output2}에 일자별 배열을 함께 준다
     * (실측: {@code {"stck_bsop_date":"20260818","bstp_nmix_prpr":"6869.83"}}).
     * {@link #dailyBars}와 같은 이유로 시세 캐시에 합치지 않는다 — 그쪽은 1분이고 이쪽은 하루다.
     *
     * <p>⚠️ <b>업종코드가 있어야 한다.</b> KIS에는 지수명 검색이 없어 코드 없이는 만들 수 있는
     * 요청이 아예 없다. 그래서 <b>이름을 설정 표에서 찾아 코드로 바꾼다</b> — 부르는 쪽은
     * 이름만 알면 된다.
     *
     * <p><b>그래서 {@code /stock 코스피}도 차트가 붙는다.</b> 「검색은 이름으로 들어오므로
     * 코드가 손에 없다」고 적어 뒀던 자리인데 <b>틀린 진단이었다</b> — 코드는 사용자 입력이
     * 아니라 설정에 있고, 이름이 그것을 찾는 열쇠다. 남은 조건은 하나뿐이다:
     * <b>설정 표에 없는 지수</b>는 차트가 없다(그때만 아래에서 던진다).
     */
    @Cacheable(cacheNames = CacheNames.STOCK_SERIES, key = "'index:' + #name")
    @CircuitBreaker(name = "kisStock")
    public java.util.List<DailyBar> dailyBarsOfIndex(String name) {
        Index target = indices.get(name);
        if (target == null || !target.hasCode()) {
            // KIS에는 지수명 검색이 없다 — 코드가 없으면 만들 수 있는 요청이 아예 없다
            throw new Unsupported("KIS 지수 일봉에 업종코드가 없습니다: " + name);
        }
        DailyChart response = request(DailyChart.class, INDEX_TR,
                "국내 지수 일봉 " + name,
                uri -> chartWindow(uri, INDEX_PATH, KRX_INDEX, target.code()).build());
        return barsOf(response);
    }

    /**
     * 미국 일봉 — <b>지수와 종목이 갈린다.</b>
     *
     * <p>지수는 {@link #usIndexSeries}(해외지수 경로 · 표가 유일한 길), 종목은
     * {@link #usStockSeries}(종목 전용 경로 · 거래소를 안다). <b>한 경로가 둘을 덮던 때가
     * 있었고, 그것이 버그였다</b> — 그 이야기는 {@link #usStockSeries}에 적혀 있다.
     *
     * <p>⚠️ <b>지수만 표를 탄다.</b> {@code ^IXIC}는 KIS가 모르고 {@code COMP}여야 한다.
     * 규칙이 없어 표가 유일한 길이고, <b>표에 없는 지수는 차트가 없다</b>({@link Unsupported}).
     *
     * <p>어느 쪽이든 <b>못 구하면 던진다</b> — 부르는 쪽이 사진만 빼고 값을 내보낸다
     * ({@code DailySeries.drawable}). 그 자리에 로그를 한 줄 남기는 것이 「KIS가 그 심볼을
     * 모른다」와 「차트를 아예 안 물었다」를 가르는 유일한 단서다.
     */
    @Cacheable(cacheNames = CacheNames.STOCK_SERIES, key = "'us:' + #symbol")
    @CircuitBreaker(name = "kisStock")
    public java.util.List<DailyBar> dailyBarsOfUs(String symbol) {
        return UsSymbol.isIndex(symbol) ? usIndexSeries(symbol) : usStockSeries(symbol);
    }

    /** 미국 지수 일봉 — 표가 유일한 길이다. {@code ^IXIC}를 KIS는 {@code COMP}로 부른다. */
    private java.util.List<DailyBar> usIndexSeries(String symbol) {
        String kisSymbol = usIndices.get(symbol);
        if (kisSymbol == null) {
            throw new Unsupported("KIS 심볼을 모르는 미국 지수입니다: " + symbol);
        }
        return barsOf(request(DailyChart.class, US_INDEX_TR, "미국 지수 일봉 " + symbol,
                uri -> chartWindow(uri, US_INDEX_PATH, OVERSEAS_INDEX, kisSymbol).build()));
    }

    /**
     * 미국 <b>종목</b> 일봉 — 거래소를 물어야 하는 대신 <b>종목을 안다.</b>
     *
     * <p>⚠️ 예전에는 지수 경로({@code FHKST03030100})에 종목 심볼을 그냥 넣었다.
     * {@code AAPL}·{@code NVDA}·{@code ORCL}이 200이길래 「거래소 코드가 필요 없다」를 이득으로
     * 세었는데, <b>그 경로가 아는 종목은 일부뿐이었다</b> — 실측 2026-08-21 {@code PATH}는
     * {@code rt_cd=0}에 {@code output2}가 빈 배열이라 <b>주가는 나오는데 차트만 조용히 빠졌다.</b>
     *
     * <p>그래서 종목 전용 경로로 옮겼다. {@code EXCD}를 요구하는 것이 당시의 반대 이유였는데
     * <b>그 값은 이미 손에 있다</b> — {@link #usStock}이 찾아 {@link KisExchangeCache}에 30일
     * 담고, 차트는 시세 다음에 조회된다. 그래서 평상시 추가 호출이 <b>0</b>이다.
     *
     * <p>실측 2026-08-21(모의):
     *
     * <pre>
     * PATH  EXCD=NYS  rsym=DNYSPATH  nrec=100  최근 15.9200
     * ORCL  EXCD=NYS  rsym=DNYSORCL  nrec=100  최근 143.3600
     * AAPL  EXCD=NAS  rsym=DNASAAPL  nrec=100  최근 312.0600
     * </pre>
     *
     * <p><b>창을 우리가 정하지 않는다.</b> 이 경로는 {@code BYMD}(비우면 최신)에서 뒤로 100행을
     * 준다 — {@code DailySeries.recent}가 열나흘로 줄이므로 그대로 받는다. 응답이 무거워지는
     * 것이 대가이고, 그 대신 {@code FID_INPUT_DATE_1/2} 계산이 없어진다.
     *
     * <p><b>지수 경로보다 하루 신선하다.</b> 이쪽은 오늘 진행 중인 거래일도 한 행으로 주는데
     * (실측 {@code 20260821}), 지수 경로는 전일까지만 줬다. 그래서 브리핑에서 지수 차트와
     * 종목 차트의 caption 기간이 하루 어긋날 수 있다 — <b>버그가 아니라 각자 가진 것이다</b>.
     * 오른쪽 끝이 「지금」이어야 한다는 규칙에는 이쪽이 더 맞는다.
     *
     * <p>거래소를 못 찾으면 <b>던진다</b> — 부르는 쪽이 사진만 빼고 값을 내보낸다.
     */
    private java.util.List<DailyBar> usStockSeries(String symbol) {
        String what = "미국 종목 일봉 " + symbol;
        RuntimeException failure = null;
        // 시세와 같은 함수, 같은 순서다 — 기억해 둔 거래소가 있으면 그 하나뿐이고,
        // 없으면 NAS→NYS를 훑는다. 목록을 여기 다시 적지 않는다
        for (String exchange : exchangesToTry(symbol)) {
            DailyChart response;
            try {
                response = request(DailyChart.class, US_STOCK_SERIES_TR, what,
                        uri -> uri.path(US_STOCK_SERIES_PATH)
                                // AUTH는 빈 값으로 보낸다 — 없으면 안 되고 값도 안 받는다
                                .queryParam("AUTH", "")
                                .queryParam("EXCD", exchange)
                                .queryParam("SYMB", symbol)
                                .queryParam("GUBN", DAILY)
                                // BYMD를 비우면 최신부터다 — 창을 우리가 계산하지 않는다
                                .queryParam("BYMD", "")
                                .queryParam("MODP", RAW_PRICE)
                                .build());
            } catch (RuntimeException e) {
                // 시세와 같은 이유로 거래소마다 따로 실패한다 — 초당 한도에 걸린 첫 거래소가
                // 둘째를 못 물어보게 만들면 안 된다({@link #usStock}의 같은 자리 참조)
                log.warn("[stock] {} — {} 조회 실패, 다음 거래소로 넘어갑니다: {}",
                        what, exchange, FailureReason.of(e));
                failure = e;
                continue;
            }
            java.util.List<DailyBar> bars = barsOf(response);
            if (bars.isEmpty()) {
                // 거래소가 틀리면 에러가 아니라 빈 배열이 온다 — 시세가 빈 문자열을 주는 것과 같다
                continue;
            }
            exchanges.remember(symbol, exchange);
            return bars;
        }
        if (failure != null) {
            throw failure;
        }
        throw new Unsupported("KIS " + what + " 응답에 칸이 없습니다");
    }

    /** {@code output2}를 일봉으로 — 걸러내기와 정렬은 {@code DailySeries}가 한 곳에서 한다. */
    private static java.util.List<DailyBar> barsOf(DailyChart response) {
        if (response == null || response.bars() == null) {
            return java.util.List.of();
        }
        java.util.List<DailyBar> bars = new java.util.ArrayList<>();
        for (Bar bar : response.bars()) {
            if (bar == null || bar.on() == null || bar.close() == null) {
                continue;
            }
            bars.add(new DailyBar(
                    java.time.LocalDate.parse(bar.on(),
                            java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                    bar.close()));
        }
        return DailySeries.recent(bars, DailySeries.WINDOW);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.KIS_QUOTE, key = "'stock:' + #code")
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
    @Cacheable(cacheNames = CacheNames.KIS_QUOTE, key = "'index:' + #index.name()")
    @CircuitBreaker(name = "kisStock")
    public StockQuote index(Index index) {
        Index target = known(index);
        if (!target.hasCode()) {
            // KIS에는 지수명 검색이 없다. 코드가 없으면 만들 수 있는 요청이 아예 없다
            throw new Unsupported("KIS 지수 조회에 업종코드가 없습니다: " + index.name());
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
    @Cacheable(cacheNames = CacheNames.KIS_QUOTE, key = "'us:' + #symbol.symbol()")
    @CircuitBreaker(name = "kisStock")
    public StockQuote quote(UsSymbol symbol) {
        if (symbol.isIndex()) {
            String kisSymbol = usIndices.get(symbol.symbol());
            if (kisSymbol == null) {
                // ^DJI를 그대로 물으면 KIS는 모른다(.DJI여야 한다). 지수는 ^IXIC → COMP 같은
                // 규칙이 없어 표가 유일한 길이고, 표에 없으면 만들 요청이 아예 없다 —
                // 2순위가 맡는다(FMP 무료 티어는 미국 지수를 다 준다)
                throw new Unsupported("KIS 심볼을 모르는 미국 지수입니다: " + symbol.symbol());
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
     * <p>⚠️ <b>{@code AMS}를 뺐다가 넣었다 — 뺀 이유가 사실이 아니었다.</b> 예전 근거는
     * 「소형주 거래소라 빗나갈 때마다 1초씩 더 태우는 대가가 크고, <b>거기 있는 종목은 2순위가
     * 맡는다</b>」였다. 그런데 <b>그 2순위가 비어 있다</b> — FMP 무료 티어는 심볼별 허용목록이라
     * {@code ORCL}·{@code SNOW} 같은 초대형주도 402다({@code FmpStockClient}). 그리고
     * 「{@code AMS} = 소형주 거래소」도 절반만 맞다: KIS 분류에서 그 칸은 <b>NYSE Arca 상장
     * ETF 전체</b>를 함께 삼킨다. 그래서 {@code /stock JEPI}·{@code SCHD}·{@code SOXL}이
     * 통째로 빈손이었다.
     *
     * <p>실측(2026-08-26, 모의계정 12/12 {@code rt_cd=0})이 그것을 그대로 보여 준다 —
     * <b>ETF 셋은 {@code AMS}에서만 답하고 나머지 둘은 빈 문자열이다.</b>
     *
     * <pre>
     * 심볼    NAS      NYS    AMS      rsym        etyp_nm
     * AAPL    309.90   (빔)   (빔)     DNASAAPL
     * SCHD    (빔)     (빔)    35.11   DAMSSCHD    ETF
     * JEPI    (빔)     (빔)    58.14   DAMSJEPI    ETF
     * SOXL    (빔)     (빔)   115.67   DAMSSOXL    ETF
     * </pre>
     *
     * <p><b>순서는 NAS → NYS → AMS다.</b> 흔한 것이 앞이라 평상시 비용은 그대로이고, 늘어나는
     * 것은 <b>없는 심볼을 물었을 때의 2초 → 3초</b>뿐이다({@code min-interval} 1초). 찾은
     * 거래소는 {@link KisExchangeCache}가 30일 기억하므로 <b>반복 검색 비용은 0</b>이다.
     */
    private StockQuote usStock(UsSymbol symbol) {
        String what = "미국 종목 " + symbol.symbol();
        RuntimeException failure = null;
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
                        what, exchange, FailureReason.of(e));
                failure = e;
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
        //    실제로 그 문장 때문에 무효 토큰이 '상장 폐지된 종목'처럼 읽혔다. 그때는 그 예외를
        //    **그대로** 올린다 — 메시지만 베끼면 타입이 사라져 브레이커가 다시 못 가른다
        if (failure != null) {
            throw failure;
        }
        // ⚠️ 거래소를 다 훑어 전부 빈 문자열이었다 — KIS의 장애가 아니라 KIS가 모르는 심볼이다.
        //    일봉 경로(usStockSeries)가 같은 조건에서 이미 Unsupported를 던지는데 여기만
        //    빠져 있었다. 그 탓에 없는 심볼을 몇 번 검색하면 kisStock 브레이커가 열려
        //    **국내 시세가 전일 종가로 강등되고 미국 시세는 통째로 빈손**이 됐다
        throw new Unsupported("KIS " + what + " 응답에 현재가가 없습니다");
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
        return remembered == null ? List.of(NASDAQ, NYSE, AMEX) : List.of(remembered);
    }

    /** 일자별 차트 셋(국내 종목·국내 지수·해외지수)이 쓰는 공통 파라미터. */
    private UriBuilder chart(UriBuilder uri, String path, String market, String code) {
        return window(uri, path, market, code, LOOKBACK_DAYS);
    }

    /**
     * 차트용 — <b>창만 넓다.</b> 거래일 열나흘을 담으려면 주말 넷과 연휴를 넘겨야 한다.
     *
     * <p>시세 경로({@link #chart})의 창을 넓히지 않는다. 그쪽은 「지금 얼마냐」를 찾는 데
     * 이레면 넉넉하고, 넓히면 응답만 무거워진다.
     */
    private UriBuilder chartWindow(UriBuilder uri, String path, String market, String code) {
        return window(uri, path, market, code, SERIES_LOOKBACK_DAYS);
    }

    private UriBuilder window(UriBuilder uri, String path, String market, String code, int days) {
        return uri.path(path)
                .queryParam("FID_COND_MRKT_DIV_CODE", market)
                .queryParam("FID_INPUT_ISCD", code)
                .queryParam("FID_INPUT_DATE_1", KisHeaders.daysAgo(clock, days))
                .queryParam("FID_INPUT_DATE_2", KisHeaders.today(clock))
                .queryParam("FID_PERIOD_DIV_CODE", "D");
    }

    /**
     * 호출 한 번. 경로마다 응답 타입만 다르고 <b>헤더·에러 처리·비밀 취급이 같다.</b>
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
            // 무효 토큰은 다음 호출에서도 같은 이유로 실패한다. 알아차린 자리에서 버려야
            // 스스로 낫는다 — 안 버리면 기록된 만료까지(최대 24시간) 모든 KIS 호출이 죽는다
            if (KisHeaders.isInvalidToken(e)) {
                tokens.invalidate();
            }
            throw new IllegalStateException("KIS " + what + " 조회 실패: " + reason);
        }
        KisHeaders.verify(response == null ? null : response.resultCode(),
                response == null ? null : response.message(), what);
        return response;
    }

    /**
     * {@code rt_cd}가 0인데 값이 비어 오는 경우 — <b>없는 종목코드·없는 지수 심볼</b>이 그렇다.
     *
     * <p>판단은 {@link Price}가 한다. 이 가드는 여기서 실측으로 만들어졌지만 같은 함정이
     * 형제 셋에도 있어서 공용으로 뽑았다 — 넷으로 갈려 있으면 하나만 고쳐지는 날이 온다.
     *
     * <p>⚠️ <b>{@link Unsupported}로 던진다 — 절반만 고쳤다가 되돌아온 자리다.</b>
     * {@code rt_cd=0}에 값이 비어 온 것은 KIS의 장애가 아니라 <b>KIS가 모르는 것</b>이고,
     * 같은 입력이면 영원히 같은 실패다. 평범한 {@code IllegalStateException}이면
     * {@code kisStock} 브레이커가 그것을 상대 장애로 세는데, 그 목록에는 {@code Unsupported}만
     * 있다. 그래서 <b>없는 코드를 열 번 검색하면 브레이커가 열려 KIS 전체가 60초 죽었다</b> —
     * 국내는 전일 종가로 강등되고 미국은 2순위(FMP)가 대부분 402라 통째로 빈손이 된다.
     *
     * <p>{@code usStock}에서 그 사고를 한 번 고쳤는데(거래소를 다 훑어 빈손일 때) <b>이쪽을
     * 놓쳤다.</b> 실제로 닿는 길이 있다: LLM이 {@code market}을 빼면 {@code isUs()}가 거짓이라
     * <b>미국 티커가 국내 경로로</b> 들어와 여기서 터진다.
     */
    private static void require(BigDecimal price, String what) {
        if (!positive(price)) {
            throw new Unsupported("KIS " + what + " 응답에 값이 없습니다: " + price);
        }
    }

    private static boolean positive(BigDecimal price) {
        return Price.positive(price);
    }

    /**
     * {@code output2}의 한 칸 — <b>세 시장의 종가 필드 이름이 다르다.</b>
     *
     * <p>국내 종목은 {@code stck_clpr}, 국내 지수는 {@code bstp_nmix_prpr}, 해외(환율·미국
     * 지수)는 {@code ovrs_nmix_prpr}다(실측 픽스처 셋이 그것을 못 박고 있다). 셋을 다 선언해
     * 두고 <b>온 것을 쓴다</b> — {@code @JsonIgnoreProperties}라 없는 필드는 그냥 {@code null}이
     * 되므로 한 타입이 셋을 덮는다. 시장마다 레코드를 두면 세 벌이 생기고 한쪽만 고쳐지는
     * 날이 온다({@code KisChartPrice}가 환율과 미국 지수를 한 스키마로 두는 것과 같은 판단이다).
     *
     * @param date 그 거래일 {@code yyyyMMdd}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Bar(@JsonProperty("stck_bsop_date") String date,
               @JsonProperty("xymd") String overseasStockDate,
               @JsonProperty("stck_clpr") BigDecimal domesticStock,
               @JsonProperty("bstp_nmix_prpr") BigDecimal domesticIndex,
               @JsonProperty("ovrs_nmix_prpr") BigDecimal overseasIndex,
               @JsonProperty("clos") BigDecimal overseasStock) {

        /** 온 것 하나. 넷 다 없으면 {@code null}이고 그 칸은 걸러진다. */
        BigDecimal close() {
            if (domesticStock != null) {
                return domesticStock;
            }
            if (domesticIndex != null) {
                return domesticIndex;
            }
            return overseasIndex != null ? overseasIndex : overseasStock;
        }

        /**
         * 그 칸의 날짜. 경로마다 이름이 다르지만 <b>형식은 같다</b> —
         * 넷 다 {@code yyyyMMdd}다(실측 {@code xymd:"20260821"}).
         */
        String on() {
            return date != null ? date : overseasStockDate;
        }
    }

    /**
     * 일자별 배열만 필요한 응답 — 같은 응답의 {@code output1}(현재가)은 시세 경로가 제 캐시로
     * 든다. <b>수명이 달라 캐시를 나눴다</b>: 시세는 1분, 일봉은 12시간이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DailyChart(@JsonProperty("rt_cd") String resultCode,
                      @JsonProperty("msg1") String message,
                      @JsonProperty("output2") java.util.List<Bar> bars) implements KisResponse {
    }

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

    /**
     * <b>애초에 만들 수 없는 요청</b> — KIS의 장애가 아니다.
     *
     * <p>둘뿐이다. <b>업종코드가 없는 지수</b>(KIS에 지수명 검색이 없다)와 <b>KIS의 해외 표에
     * 없는 심볼</b>(지수는 표가 유일한 길이고, 종목 일봉은 거래소를 다 훑어도 {@code output2}가
     * 빈 배열로 온다). 어느 쪽도 다시 물어서 낫지 않고, <b>같은 입력이면 영원히 같은 실패</b>다.
     *
     * <p>⚠️ <b>타입을 따로 두는 이유는 브레이커다.</b> 이 실패가 {@code kisStock}에 쌓이면
     * 열리는 순간 <b>멀쩡한 KIS 호출 전부</b>가 함께 막힌다 — 국내 시세는 전일 종가로 강등되고,
     * 미국 시세는 2순위(FMP)가 대부분 402라 <b>통째로 빈손</b>이 된다. 설정이
     * {@code fmpOutlook}을 시세와 가른 이유와 같은 자리이고, 지오코딩·바이낸스가 「없는 지명」·
     * 「없는 심볼」의 4xx를 무시 목록에 넣은 것과 같은 판단이다.
     *
     * <p><b>여기가 더 나빴던 이유가 둘 있다.</b> 하나는 이 실패가 <b>HTTP 호출 없이</b> 난다는
     * 것이다(표를 못 찾으면 그 자리에서 던진다) — 상대를 건드리지도 않고 상대의 브레이커를
     * 태운다. 다른 하나는 캐시가 브레이커보다 <b>바깥</b>이라는 것이다
     * ({@code ResilienceConfigTest.cacheSitsOutsideTheResilienceAspects}) — 성공한 일봉은
     * 12시간 캐시에 들어가 브레이커에 다시 안 세어지는데 <b>실패는 캐시되지 않아 매번 세어진다.</b>
     * 그래서 비율이 실패 쪽으로 기울고, 설정 표에 없는 지수를 다섯 번 물으면
     * (시세 1 + 차트 1 = 조회당 실패 둘) 창 열 칸이 실패로 차 브레이커가 열린다.
     *
     * <p><b>던지는 것은 그대로다.</b> 빈 값을 돌려주면 {@code StockService}가 폴백하지 못하고
     * 그대로 빈손이 나간다 — 바꾼 것은 <b>세는 방식</b>뿐이다.
     */
    public static final class Unsupported extends IllegalStateException {

        public Unsupported(String message) {
            super(message);
        }
    }
}
