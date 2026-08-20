package io.saiden.economyhelper.market.data;

import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 공공데이터포털 — 금융위원회 주식시세정보.
 *
 * <p>실제로 호출해 확인한 함정이 셋이다.
 *
 * <ol>
 *   <li><b>서비스키를 다시 인코딩하면 안 된다.</b> 발급된 키는 이미 URL 인코딩된 형태(`%` 포함)라
 *       그대로 붙이면 200이지만, {@code UriBuilder.queryParam()}처럼 한 번 더 인코딩하면
 *       <b>403 "등록되지 않은 서비스키"</b>가 난다. 그래서 URI를 <b>직접 문자열로 조립</b>한다
 *   <li><b>{@code srtnCd}는 무시된다.</b> {@code srtnCd=005930}으로 조회했더니 전혀 다른 종목이
 *       나왔다. 종목코드로 찾으려면 <b>{@code likeSrtnCd}</b>를 써야 한다
 *   <li><b>전일 종가다.</b> 오늘 날짜로 조회하면 {@code totalCount=0}이다.
 *       비영업일·연휴를 감안해 하루씩 물려 되짚는다
 * </ol>
 *
 * <p>키가 URL에 실리므로 {@code KeximFxClient}와 같은 규칙을 따른다 —
 * <b>예외를 그대로 흘리지 않고 URL 없는 자체 예외로 바꿔 던진다.</b>
 * <p>⚠️ <b>리미터는 HTTP 호출 자리에 걸어야 한다.</b> 예전에는 애너테이션이 바깥
 * {@code @Cacheable} 메서드에만 있어 <b>진입 한 번에 퍼밋 하나</b>였는데, 그 안의 되짚기
 * 루프가 최대 {@code MAX_LOOKBACK_DAYS}회 HTTP를 부른다 — {@code dataGo}가 초당 10건이므로
 * 캐시가 빈 조회 하나가 산수로 이미 한도를 채우는데 리미터는 그걸 1회로 셌다.
 * {@code KeximFxClient}가 같은 함정을 먼저 겪고 고쳤는데 이 둘에는 적용되지 않았다.
 * 이제 실제 호출 자리({@code request})에서 퍼밋을 얻는다 — 애너테이션은 프록시가 필요해
 * private 메서드에 못 걸리므로 레지스트리에서 직접 꺼내 쓴다.
 */
@Component
public class StockPriceApi {

    private static final Logger log = LoggerFactory.getLogger(StockPriceApi.class);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PATH = "/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo";

    /** 연휴가 길어도 이 안에서 잡힌다. 한 번에 하루씩 호출을 태우므로 상한을 둔다. */
    private static final int MAX_LOOKBACK_DAYS = 10;

    /** 한 번에 받아올 최대 건수. {@code 삼성}이 26건이었으니 100이면 넉넉하다. */
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final Clock clock;
    /** 되짚기 루프가 실제로 태우는 호출을 세는 자리. {@code null}이면 세지 않는다(테스트). */
    private final RateLimiter limiter;

    public StockPriceApi(RestClient.Builder builder,
                         @Value("${economy-helper.market.data-go.base-url}") String baseUrl,
                         @Value("${economy-helper.market.data-go.api-key:}") String serviceKey,
                         Clock clock,
                         RateLimiterRegistry limiters) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.clock = clock;
        this.limiter = limiters == null ? null : limiters.rateLimiter("dataGo");
    }

    /** 종목명 부분검색. {@code 하이닉스} → SK하이닉스가 걸린다. */
    @Cacheable(cacheNames = CacheNames.STOCK_PRICE, key = "'name:' + #name", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "dataGo")
    public List<StockPrice> searchByName(String name) {
        return searchRecent("likeItmsNm", name);
    }

    /** 종목코드 검색. {@code srtnCd}가 아니라 {@code likeSrtnCd}여야 한다. */
    @Cacheable(cacheNames = CacheNames.STOCK_PRICE, key = "'code:' + #code", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "dataGo")
    public List<StockPrice> searchByCode(String code) {
        return searchRecent("likeSrtnCd", code);
    }

    /**
     * 가장 최근 영업일의 결과를 찾는다.
     *
     * <p>오늘은 항상 0건이므로 어제부터 시작한다. 주말·공휴일이면 계속 0건이라 하루씩 물린다.
     */
    private List<StockPrice> searchRecent(String filterParam, String filterValue) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);

        for (int back = 1; back <= MAX_LOOKBACK_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            List<StockPrice> found = request(date, filterParam, filterValue);
            if (!found.isEmpty()) {
                return found;
            }
        }
        log.info("[stock] 최근 {}일 안에 '{}' 시세가 없습니다", MAX_LOOKBACK_DAYS, filterValue);
        return List.of();
    }

    private List<StockPrice> request(LocalDate date, String filterParam, String filterValue) {
        if (limiter != null) {
            // 거절은 RequestNotPermitted다 — dataGo 브레이커가 baseConfig에서 그걸
            // 무시하므로 우리 스로틀이 상대 장애로 세어지지 않는다
            limiter.acquirePermission();
        }
        // 서비스키는 이미 인코딩된 형태다 — 여기서 다시 인코딩하면 403이 난다.
        // 나머지 값만 우리가 인코딩해 붙인다.
        String uri = baseUrl + PATH
                + "?serviceKey=" + serviceKey
                + "&resultType=json"
                + "&numOfRows=" + PAGE_SIZE
                + "&pageNo=1"
                + "&basDt=" + date.format(BAS_DT)
                + "&" + filterParam + "=" + encode(filterValue);
        try {
            Response response = restClient.get().uri(URI.create(uri)).retrieve().body(Response.class);
            return extract(response);
        } catch (RuntimeException e) {
            // 원래 예외 메시지에는 serviceKey가 박힌 URL이 들어간다 — 그대로 흘리면 키가 유출된다
            log.warn("[stock] {} 조회 실패: {}", date.format(BAS_DT), e.getClass().getSimpleName());
            throw new IllegalStateException("주식시세 조회 실패 (basDt=" + date.format(BAS_DT) + ")");
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 결과가 1건이면 {@code item}이 배열이 아니라 객체로 오는 API가 흔한데, 여기는 배열로 온다. */
    private static List<StockPrice> extract(Response response) {
        if (response == null || response.response() == null || response.response().body() == null) {
            return List.of();
        }
        Body body = response.response().body();
        if (body.items() == null || body.items().item() == null) {
            return List.of();
        }
        return body.items().item();
    }

    // --- 응답 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(@com.fasterxml.jackson.annotation.JsonProperty("response") Envelope response) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Body body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Body(Items items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Items(List<StockPrice> item) {}

    /**
     * <p><b>종목코드와 시장 구분은 담지 않는다.</b> 종목코드는 <b>보낼 때</b>만 쓰고
     * ({@code likeSrtnCd} 쿼리) 되받아 읽을 일이 없으며, {@code KOSPI}·{@code KOSDAQ} 구분은
     * 화면에 안 나간다 — 무리는 지역으로 가른다({@code StockQuote.Market}).
     *
     * @param basDt      기준일자 {@code yyyyMMdd}
     * @param itmsNm     종목명 (한글)
     * @param clpr       종가 — 화면에 나가는 값
     * @param fltRt      등락률(%). {@code 4.89}·{@code -1.2} 꼴로 부호까지 실려 온다
     * @param mrktTotAmt 시가총액 — 동명 후보를 가르는 내부 신호
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockPrice(String basDt, String itmsNm, String clpr, String fltRt,
                             String mrktTotAmt) {}
}
