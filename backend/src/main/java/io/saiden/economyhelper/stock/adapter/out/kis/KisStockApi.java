package io.saiden.economyhelper.stock.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.KisIndex;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.fx.adapter.out.kis.KisFxClient;
import io.saiden.economyhelper.infrastructure.kis.KisCall;
import io.saiden.economyhelper.infrastructure.kis.KisChartPrice;
import io.saiden.economyhelper.infrastructure.kis.KisHeaders;
import io.saiden.economyhelper.infrastructure.kis.KisResponse;
import io.saiden.economyhelper.shared.domain.DailyBar;
import io.saiden.economyhelper.shared.domain.DailySeries;
import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.stock.application.port.out.DomesticStockClient;
import io.saiden.economyhelper.stock.application.port.out.StockDailyBarClient;
import io.saiden.economyhelper.stock.application.port.out.UsStockClient;
import io.saiden.economyhelper.stock.domain.ClassShare;
import io.saiden.economyhelper.stock.domain.StockQuote;
import io.saiden.economyhelper.stock.domain.StockSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * 한국투자증권 시세 — <b>국내와 미국 이중화의 1순위</b>({@code StockService.DOMESTIC_ORDER}·{@code US_ORDER}).
 *
 * <p>이 봇에서 유일하게 <b>국내를 실시간으로</b> 주는 출처다. 2순위인 공공데이터포털은 전일
 * 종가뿐이라 오전 9시 브리핑에 어제 값이 나간다.
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
 *   <li><b>에러가 HTTP 200 본문에 온다</b>({@code rt_cd=1}). {@link KisCall}이 막는다
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
public class KisStockApi implements DomesticStockClient, UsStockClient, StockDailyBarClient {

    private static final Logger log = LoggerFactory.getLogger(KisStockApi.class);

    /** 시세 응답의 일별 행({@code output2})을 차트 칸으로 옮기는 데만 쓴다 — {@link #seedSeries}. */
    private static final ObjectReader BAR_ROWS = new ObjectMapper().readerForListOf(Bar.class);

    /** 국내 시장 달력 — 일봉 날짜가 KST다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 미국 시장 달력 — 해외 일봉 날짜가 미국 날짜다. */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

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
     * 수정주가로 받지 <b>않는다</b>({@code MODP=0}) — 국내 경로와 반대다.
     *
     * <p>{@code MODP=1}은 가장 최근 행까지 스케일해서 차트 끝값이 본문 시세와 어긋난다 —
     * 한 종목 값이 한 통에 두 개 찍힌다. 실측과 그 대가(액면분할)는 → ADR-0007.
     */
    private static final String RAW_PRICE = "0";

    /**
     * 국내 {@code FID_ORG_ADJ_PRC} — <b>{@code 0}이 수정주가</b>(액면분할·유무상증자 반영, {@code 1}이 원주가).
     * 미국 {@code MODP}와 뜻이 반대라 상수를 나눈다(ADR-0007). 화면은 「지금 얼마냐」라 분할 전 가격이 섞이면 안 된다.
     */
    private static final String DOMESTIC_ADJUSTED = "0";

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

    private final KisCall kis;
    private final Clock clock;
    private final Map<String, Index> indices;
    private final Map<String, String> usIndices;
    private final KisExchangeCache exchanges;
    /** 시세 응답의 일별 행으로 차트 캐시를 미리 채운다({@link #seedSeries}). {@code null}이면 안 채운다. */
    private final CacheManager caches;

    /**
     * @param properties <b>지수 조회 키 표만</b> 여기서 온다 — 국내는 업종코드
     *                   ({@code digest.indices}), 미국은 KIS 심볼({@code market.kis.us-indices}).
     *                   ⚠️ <b>미국 <i>종목</i>은 표를 타지 않는다.</b> 거래소를 스스로 찾는다
     *                   (NAS → NYS → AMS, 30일 기억). 브리핑 목록({@code digest.us-symbols})이 이 표를
     *                   겸하면 목록에 없는 심볼을 통째로 거절한다
     */
    public KisStockApi(KisCall kis, Clock clock,
                       EconomyHelperProperties properties, KisExchangeCache exchanges) {
        this(kis, clock, properties, exchanges, null);
    }

    @Autowired
    public KisStockApi(KisCall kis, Clock clock, EconomyHelperProperties properties,
                       KisExchangeCache exchanges, CacheManager caches) {
        this.caches = caches;
        this.kis = kis;
        this.exchanges = exchanges;
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

    /** 설정 표({@code digest.indices})의 이름이면 안다 — 업종코드가 거기 있다. */
    @Override
    public boolean knowsIndex(String name) {
        return name != null && indices.containsKey(name);
    }

    /**
     * 국내 종목 일봉 — <b>차트가 그리는 것.</b>
     *
     * <p><b>평상시에는 KIS를 안 부른다</b> — {@link #stock}이 같은 엔드포인트를 차트 창으로 불러 받은
     * {@code output2}를 이 캐시 칸에 미리 넣는다({@link #seedSeries}). 캐시 <b>항목</b>은 합치지 않는다 —
     * 시세(1분)와 일봉은 수명이 다르다(ADR-0007). 여기까지 오는 것은 시세 없이 차트만 물을 때다.
     *
     * <p>⚠️ <b>{@code 0.00}은 값이 아니다.</b> 없는 종목코드에 에러가 아니라 0이 오므로
     * 그대로 그리면 차트가 0으로 절벽을 그린다 — {@code DailySeries}가 걸러낸다.
     */
    @Cacheable(cacheNames = CacheNames.STOCK_SERIES, key = "'stock:' + #code", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "kisStock")
    @Override
    public List<DailyBar> dailyBars(String code) {
        DailyChart response = kis.get(DailyChart.class, STOCK_TR, "국내 종목 일봉 " + code,
                uri -> chartWindow(uri, STOCK_PATH, KRX_STOCK, code)
                        .queryParam("FID_ORG_ADJ_PRC", DOMESTIC_ADJUSTED)
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
     * <p>그래서 {@code /stock 코스피}도 차트가 붙는다. <b>설정 표에 없는 지수</b>만 차트가 없다
     * (그때만 아래에서 던진다).
     */
    @Cacheable(cacheNames = CacheNames.STOCK_SERIES, key = "'index:' + #name", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "kisStock")
    @Override
    public List<DailyBar> dailyBarsOfIndex(String name) {
        Index target = indices.get(name);
        if (target == null || !target.hasCode()) {
            // KIS에는 지수명 검색이 없다 — 코드가 없으면 만들 수 있는 요청이 아예 없다
            throw new Unsupported("KIS 지수 일봉에 업종코드가 없습니다: " + name);
        }
        DailyChart response = kis.get(DailyChart.class, INDEX_TR,
                "국내 지수 일봉 " + name,
                uri -> chartWindow(uri, INDEX_PATH, KRX_INDEX, target.code()).build());
        return barsOf(response);
    }

    /**
     * 미국 일봉 — <b>지수와 종목이 갈린다.</b>
     *
     * <p>지수는 {@link #usIndexSeries}(해외지수 경로 · 표가 유일한 길), 종목은
     * {@link #usStockSeries}(종목 전용 경로 · 거래소를 안다).
     *
     * <p>⚠️ <b>지수만 표를 탄다.</b> {@code ^IXIC}는 KIS가 모르고 {@code COMP}여야 한다.
     * 규칙이 없어 표가 유일한 길이고, <b>표에 없는 지수는 차트가 없다</b>({@link Unsupported}).
     *
     * <p>어느 쪽이든 <b>못 구하면 던진다</b> — 부르는 쪽이 사진만 빼고 값을 내보낸다
     * ({@code DailySeries.drawable}). 그 자리에 로그를 한 줄 남기는 것이 「KIS가 그 심볼을
     * 모른다」와 「차트를 아예 안 물었다」를 가르는 유일한 단서다.
     */
    @Cacheable(cacheNames = CacheNames.STOCK_SERIES, key = "'us:' + #symbol", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "kisStock")
    @Override
    public List<DailyBar> dailyBarsOfUs(String symbol) {
        return UsSymbol.isIndex(symbol) ? usIndexSeries(symbol) : usStockSeries(symbol);
    }

    /** 미국 지수 일봉 — 표가 유일한 길이다. {@code ^IXIC}를 KIS는 {@code COMP}로 부른다. */
    private List<DailyBar> usIndexSeries(String symbol) {
        String kisSymbol = usIndices.get(symbol);
        if (kisSymbol == null) {
            throw new Unsupported("KIS 심볼을 모르는 미국 지수입니다: " + symbol);
        }
        return barsOf(kis.get(DailyChart.class, US_INDEX_TR, "미국 지수 일봉 " + symbol,
                uri -> chartWindow(uri, US_INDEX_PATH, OVERSEAS_INDEX, kisSymbol).build()));
    }

    /**
     * 미국 <b>종목</b> 일봉 — 거래소를 물어야 하는 대신 <b>종목을 안다.</b>
     *
     * <p>⚠️ <b>지수 경로({@code FHKST03030100})에 종목 심볼을 넣지 않는다</b> — 그 경로가 아는 종목은
     * 일부뿐이고, 모르는 종목은 에러가 아니라 {@code output2} 빈 배열로 온다(주가는 나오는데
     * 차트만 조용히 빠진다) → ADR-0007.
     *
     * <p>이 경로가 요구하는 {@code EXCD}는 이미 손에 있다 — {@link #usStock}이 찾아
     * {@link KisExchangeCache}에 30일 담고, 차트는 시세 다음에 조회된다. 평상시 추가 호출이 <b>0</b>이다.
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
    private List<DailyBar> usStockSeries(String symbol) {
        return overExchanges(symbol, "미국 종목 일봉 " + symbol, "응답에 칸이 없습니다",
                (exchange, what) -> kis.get(DailyChart.class, US_STOCK_SERIES_TR, what,
                        uri -> uri.path(US_STOCK_SERIES_PATH)
                                // AUTH는 빈 값으로 보낸다 — 없으면 안 되고 값도 안 받는다
                                .queryParam("AUTH", "")
                                .queryParam("EXCD", exchange)
                                .queryParam("SYMB", kisSymbol(symbol))
                                .queryParam("GUBN", DAILY)
                                // BYMD를 비우면 최신부터다 — 창을 우리가 계산하지 않는다
                                .queryParam("BYMD", "")
                                .queryParam("MODP", RAW_PRICE)
                                .build()),
                response -> {
                    // 거래소가 틀리면 에러가 아니라 빈 배열이 온다 — 시세가 빈 문자열을 주는 것과 같다
                    List<DailyBar> bars = barsOf(response);
                    return bars.isEmpty() ? Optional.empty() : Optional.of(bars);
                });
    }

    /**
     * 시세 응답의 일별 행을 차트 캐시({@link CacheNames#STOCK_SERIES})에 넣는다 — 키는 차트 조회
     * ({@link #dailyBars}·{@link #dailyBarsOfIndex})의 {@code @Cacheable} 키와 <b>글자까지 같아야</b> 한다.
     * 빈 행은 넣지 않고(그 캐시의 {@code unless}와 같다), 캐시 실패는 삼킨다 — 차트는 보충이다.
     */
    private void seedSeries(String key, JsonNode rows) {
        if (caches == null || rows == null || !rows.isArray()) {
            return;
        }
        // ⚠️ 변환부터 전부 여기 안에서 한다 — 행 하나(잘못된 날짜·숫자 아닌 종가)가 시세까지 던지면
        //    폴백이 돌고 kisStock 브레이커에 실패가 쌓인다. 시세는 output2를 몰라도 나가야 한다
        try {
            List<Bar> parsed = BAR_ROWS.readValue(rows);
            List<DailyBar> bars = barsOf(parsed);
            Cache cache = caches.getCache(CacheNames.STOCK_SERIES);
            if (!bars.isEmpty() && cache != null) {
                cache.put(key, bars);
            }
        } catch (RuntimeException e) {
            log.warn("[kis] {} 차트 캐시를 못 채웠습니다 — 차트 조회가 따로 부릅니다: {}", key, FailureReason.of(e));
        }
    }

    /** {@code output2}를 일봉으로 — 걸러내기와 정렬은 {@code DailySeries}가 한 곳에서 한다. */
    private static List<DailyBar> barsOf(DailyChart response) {
        return response == null ? List.of() : barsOf(response.bars());
    }

    private static List<DailyBar> barsOf(List<Bar> rows) {
        if (rows == null) {
            return List.of();
        }
        List<DailyBar> bars = new ArrayList<>();
        for (Bar bar : rows) {
            // ⚠️ 날짜를 못 읽는 행은 그 행만 뺀다 — 빈 날짜 하나가 던지면 차트가 통째로 빠진다
            LocalDate on = bar == null ? null : KisHeaders.dateOf(bar.on());
            if (on == null || bar.close() == null) {
                continue;
            }
            bars.add(new DailyBar(on, bar.close()));
        }
        return DailySeries.recent(bars, DailySeries.WINDOW);
    }

    /**
     * {@code output2}에서 가장 최근 거래일 — 현재가가 <b>어느 날의 값인지</b>는 여기서만 안다.
     *
     * @return 못 읽으면 {@code null}(모름). 시세는 이것 때문에 던지지 않는다
     */
    private static LocalDate latestDateOf(JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            return null;
        }
        try {
            List<Bar> parsed = BAR_ROWS.readValue(rows);
            return parsed.stream().filter(Objects::nonNull)
                    .map(bar -> KisHeaders.dateOf(bar.on()))
                    .filter(Objects::nonNull)
                    .max(LocalDate::compareTo).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 값의 기준 — <b>마지막 거래일이 그 시장의 오늘이 아니면 종가다.</b> 주말·휴일·개장 전에 KIS는 마지막 거래일
     * 종가를 현재가 자리에 준다. 그 값에 읽은 시각을 찍고 실시간이라 하면 낡은 값을 신선하게 꾸미는 셈이다.
     * 날짜를 모르면 지금까지처럼 실시간으로 둔다 — 모른다고 낡았다고 단정하지 않는다.
     */
    private Basis basisOf(LocalDate latest, ZoneId market) {
        return KisHeaders.closedBefore(latest, clock, market)
                ? new Basis(KisHeaders.startOfDay(latest), false)
                : new Basis(clock.instant(), true);
    }

    private record Basis(Instant at, boolean live) {}

    @Override
    @Cacheable(cacheNames = CacheNames.KIS_QUOTE, key = "'stock:' + #code")
    @CircuitBreaker(name = "kisStock")
    public StockQuote stock(String code) {
        // 차트 창으로 부른다 — output1(현재가)은 창과 무관하고, output2가 그대로 차트가 된다
        DomesticStock response = kis.get(DomesticStock.class, STOCK_TR, "국내 종목 " + code,
                uri -> chartWindow(uri, STOCK_PATH, KRX_STOCK, code)
                        .queryParam("FID_ORG_ADJ_PRC", DOMESTIC_ADJUSTED)
                        .build());
        DomesticStock.Quote quote = response.output();

        Price price = require(quote == null ? null : quote.price(), "국내 종목 " + code);
        seedSeries("stock:" + code, response.bars());
        Basis basis = basisOf(latestDateOf(response.bars()), SEOUL);
        // 이름은 응답이 준다 — 국내 종목은 KIS가 한글명을 제대로 준다(실측: '삼성전자')
        return new StockQuote(quote.name(), price, PercentChange.ofNullable(quote.changePercent()),
                StockQuote.Money.KRW, StockQuote.Market.DOMESTIC, StockSource.KIS,
                basis.at(), basis.live());
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
        DomesticIndex response = kis.get(DomesticIndex.class, INDEX_TR,
                "국내 지수 " + target.name(),
                uri -> chartWindow(uri, INDEX_PATH, KRX_INDEX, target.code()).build());
        DomesticIndex.Quote quote = response.output();

        Price price = require(quote == null ? null : quote.price(), "국내 지수 " + target.name());
        seedSeries("index:" + target.name(), response.bars());
        Basis basis = basisOf(latestDateOf(response.bars()), SEOUL);
        // 응답의 hts_kor_isnm은 코스피가 '종합', 코스닥이 'KOSDAQ'이다(실측) — 설정 이름을 쓴다
        return new StockQuote(target.name(), price, PercentChange.ofNullable(quote.changePercent()),
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.KIS,
                basis.at(), basis.live());
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
        if (index.name() == null) {
            // 불변 맵은 null 키에 NPE를 던진다 — 그건 브레이커에 「우리 잘못」이 아닌 실패로 쌓인다
            throw new Unsupported("KIS 지수 조회에 이름이 없습니다");
        }
        Index configured = indices.get(index.name());
        return configured == null || index.hasCode() ? index : configured;
    }

    /** 해외지수 — 환율과 같은 엔드포인트·같은 스키마다. 다른 것은 시장 코드와 심볼뿐이다. */
    private StockQuote usIndex(UsSymbol symbol, String kisSymbol) {
        KisChartPrice response = kis.get(KisChartPrice.class, US_INDEX_TR,
                "미국 지수 " + symbol.name(),
                uri -> chart(uri, US_INDEX_PATH, OVERSEAS_INDEX, kisSymbol).build());
        KisChartPrice.Quote quote = response.output();

        Price price = require(quote == null ? null : quote.price(), "미국 지수 " + symbol.name());
        // 일봉 날짜는 미국 날짜다 — 미국 달력의 오늘과 견준다
        Basis basis = basisOf(response.latestDate(), NEW_YORK);
        return new StockQuote(symbol.name(), price, PercentChange.ofNullable(quote.changePercent()),
                StockQuote.Money.NONE, StockQuote.Market.US, StockSource.KIS,
                basis.at(), basis.live());
    }

    /**
     * 미국 종목 — <b>거래소를 스스로 찾는다.</b>
     *
     * <p><b>왜 탐색하는가.</b> {@code price-detail}은 {@code EXCD}를 요구하는데 사용자도 LLM도
     * 그걸 주지 않고, 2순위(FMP 무료 티어)는 심볼별 허용목록이라 {@code PATH}·{@code ORCL}·
     * {@code SNOW}가 전부 402다(실측) — KIS가 임의 심볼을 다 맡아야 한다.
     *
     * <p><b>빗나간 거래소가 에러로 오지 않는다.</b> {@code rt_cd=0}에 41개 필드가 다 오고
     * 값만 빈 문자열이다(실측). 없는 티커도 똑같다 — 그래서 "비었으면 다음 거래소"가 성립한다.
     *
     * <p>⚠️ <b>{@code AMS}를 빼지 않는다.</b> 「소형주 거래소」로만 보면 뺄 만하지만 KIS 분류에서
     * 그 칸은 <b>NYSE Arca 상장 ETF 전체</b>를 삼킨다 — 빼면 {@code /stock JEPI}·{@code SCHD}·
     * {@code SOXL}이 통째로 빈손이다. 거래소별 실측표는 → ADR-0001.
     *
     * <p><b>순서는 NAS → NYS → AMS다.</b> 흔한 것이 앞이라 평상시 비용은 그대로이고, 늘어나는
     * 것은 없는 심볼을 물었을 때의 1초뿐이다({@code min-interval} 1초). 찾은 거래소는
     * {@link KisExchangeCache}가 30일 기억하므로 <b>반복 검색 비용은 0</b>이다.
     */
    private StockQuote usStock(UsSymbol symbol) {
        return overExchanges(symbol.symbol(), "미국 종목 " + symbol.symbol(),
                "응답에 현재가가 없습니다",
                (exchange, what) -> kis.get(UsStock.class, US_STOCK_TR, what,
                        uri -> uri.path(US_STOCK_PATH)
                                // AUTH는 빈 값으로 보낸다. 없으면 안 되고 값도 안 받는다
                                .queryParam("AUTH", "")
                                .queryParam("EXCD", exchange)
                                .queryParam("SYMB", kisSymbol(symbol.symbol()))
                                .build()).output(),
                quote -> {
                    if (quote == null) {
                        return Optional.empty();
                    }
                    // 달러 등락률 필드가 없다. t_xrat은 원화 환산가 기준이라 쓰면 틀린 값이 나간다
                    return Price.of(quote.price()).map(price -> new StockQuote(symbol.name(), price,
                            PercentChange.between(quote.price(), quote.previousClose()).orElse(null),
                            StockQuote.Money.USD, StockQuote.Market.US, StockSource.KIS,
                            clock.instant(), true));
                });
    }

    /**
     * <b>거래소를 순서대로 물어보고 처음 답한 곳을 기억한다</b> — 시세와 일봉이 함께 쓴다.
     *
     * <p>⚠️ <b>실패의 두 갈래를 섞지 않는 것이 이 메서드의 계약이다.</b>
     *
     * <ul>
     *   <li><b>빈 결과 → 다음 거래소.</b> 거래소가 틀리면 KIS는 에러가 아니라 빈 문자열·빈
     *       배열을 준다. 그건 실패가 아니라 「여기 없다」다.
     *   <li><b>예외 → 다음 거래소, 그리고 <u>마지막에 되던진다</u>.</b> {@code rt_cd=1}(초당
     *       거래건수 초과)이 이 앱키에서는 흔한 경로다 — NAS에서 스로틀에 걸렸다고 NYS를
     *       시도조차 못 하면 종목이 빈손이 된다. 전부 실패했으면 <b>원래 예외를 그대로</b>
     *       올린다: 메시지만 베끼면 타입이 사라져 브레이커가 다시 못 가른다.
     * </ul>
     *
     * <p>둘이 다 「없다」로 끝나면 그것은 KIS의 장애가 아니라 <b>KIS가 모르는 심볼</b>이므로
     * {@link Unsupported}다 — 같은 입력이면 영원히 같은 실패이고, HTTP 호출조차 없이 나는
     * 실패를 상대 장애로 세면 안 된다({@code application.yml}의 {@code kisStock}이 그것을
     * {@code ignoreExceptions}에 두는 이유다).
     *
     * <p>⚠️ <b>호출과 해석을 갈라 받는다.</b> {@code call}만 {@code try} 안이고 {@code read}는 밖이다.
     * 한 덩이로 받으면 <b>응답을 읽다 난 오류가 「이 거래소가 실패했다」로 잡혀</b> — KIS가 일봉 날짜를
     * {@code 20260231}로 주면 — 다음 거래소를 한 번 더 부르고(1초) 원인이 뒤엣것의 실패에 묻힌다.
     *
     * <p>{@code remember}는 값을 만든 <b>뒤에</b> 한다 — 쓸 값을 못 만든 거래소를 기억하지 않는다.
     *
     * @param what   로그·예외에 실리는 이름. 거래소마다 같은 이름으로 찍혀야 어느 조회인지 읽힌다
     * @param absent 다 훑어도 빈손일 때의 꼬리말({@code "응답에 현재가가 없습니다"})
     * @param call   {@code (거래소, what)} → 받은 응답. <b>이것만 실패가 「다음 거래소」다</b>
     * @param read   응답 → 값, 또는 <b>「여기 없다」면 빈 {@link Optional}</b>.
     *               여기서 난 예외는 <b>그대로 전파된다</b> — 거래소를 바꿔도 같을 일이다
     */
    private <T, R> R overExchanges(String symbol, String what, String absent,
                                   BiFunction<String, String, T> call,
                                   Function<T, Optional<R>> read) {
        RuntimeException failure = null;
        // 기억해 둔 거래소가 있으면 그것부터 — 목록을 두 곳에 적지 않는다
        for (String exchange : exchangesToTry(symbol)) {
            T response;
            try {
                response = call.apply(exchange, what);
            } catch (RuntimeException e) {
                log.warn("[stock] {} — {} 조회 실패, 다음 거래소로 넘어갑니다: {}",
                        what, exchange, FailureReason.of(e));
                failure = e;
                continue;
            }
            Optional<R> found = read.apply(response);
            if (found.isEmpty()) {
                continue;
            }
            exchanges.remember(symbol, exchange);
            return found.get();
        }
        if (failure != null) {
            throw failure;
        }
        throw new Unsupported("KIS " + what + " " + absent);
    }

    /**
     * KIS가 받는 미국 심볼 — <b>클래스 주식의 구분자는 {@code /}다</b>({@code BRK.B}·{@code BRK-B} → {@code BRK/B}).
     *
     * <p>실측(2026-10-03, KIS 해외 마스터 {@code nysmst.cod}): {@code BRK/A}·{@code BRK/B}·{@code ABR/D}처럼 적는다.
     * 사용자·LLM이 쓰는 점·하이픈 표기로 물으면 어느 거래소에도 없다. 그 밖의 심볼은 그대로다.
     */
    static String kisSymbol(String symbol) {
        return ClassShare.of(symbol).map(ClassShare::kis).orElse(symbol);
    }

    /**
     * 물어볼 거래소 순서.
     *
     * <p><b>지난번에 찾아 기억해 둔 것이 있으면 그것부터다</b> — 거기서 찾으면 탐색 비용이 없다.
     * 그 기억은 30일 간다({@link KisExchangeCache}). ⚠️ 나머지를 뒤에 남겨 두는 것은 상장을 옮긴
     * 종목 때문이다 — 기억한 곳만 물으면 빈손이 30일 굳는다. 다른 곳에서 찾으면 그쪽으로 다시 기억한다.
     *
     * <p>모르면 나스닥부터 본다. 사용자가 물을 법한 미국 종목이 그쪽에 더 많다.
     */
    private List<String> exchangesToTry(String symbol) {
        List<String> all = List.of(NASDAQ, NYSE, AMEX);
        String remembered = exchanges.of(symbol);
        if (remembered == null) {
            return all;
        }
        return Stream.concat(Stream.of(remembered), all.stream().filter(e -> !e.equals(remembered))).toList();
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
     * {@code rt_cd}가 0인데 값이 비어 오는 경우 — <b>없는 종목코드·없는 지수 심볼</b>이 그렇다.
     *
     * <p>판단은 {@link Price}가 한다.
     *
     * <p>⚠️ <b>{@link Unsupported}로 던진다.</b> {@code rt_cd=0}에 값이 비어 온 것은 KIS가 모르는
     * 것이다. 평범한 {@code IllegalStateException}이면 없는 코드를 열 번 검색하는 것으로
     * {@code kisStock} 브레이커가 열려 KIS 전체가 60초 죽는다. 닿는 길이 실제로 있다: LLM이
     * {@code market}을 빼면 <b>미국 티커가 국내 경로로</b> 들어와 여기서 터진다.
     */
    private static Price require(BigDecimal price, String what) {
        return Price.of(price).orElseThrow(
                () -> new Unsupported("KIS " + what + " 응답에 값이 없습니다: " + price));
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
                      @JsonProperty("msg_cd") String messageCode,
                      @JsonProperty("output2") List<Bar> bars) implements KisResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DomesticStock(@JsonProperty("rt_cd") String resultCode,
                         @JsonProperty("msg1") String message,
                         @JsonProperty("msg_cd") String messageCode,
                         @JsonProperty("output1") Quote output,
                         @JsonProperty("output2") JsonNode bars) implements KisResponse {

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
                         @JsonProperty("msg_cd") String messageCode,
                         @JsonProperty("output1") Quote output,
                         @JsonProperty("output2") JsonNode bars) implements KisResponse {

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
                   @JsonProperty("msg_cd") String messageCode,
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
     * <p>⚠️ <b>타입을 따로 두는 이유는 브레이커다.</b> 이 실패가 {@code kisStock}에 쌓여 열리면
     * 멀쩡한 KIS 호출 전부가 함께 막힌다. 게다가 이 실패는 <b>HTTP 호출 없이</b> 나고(표를 못
     * 찾으면 그 자리에서 던진다) <b>캐시되지 않아 매번 세어지므로</b>, 비율이 금세 실패 쪽으로
     * 기운다. 브레이커 산수는 → ADR-0001.
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
