package io.saiden.economyhelper.market.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
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

    public StockPriceApi(RestClient.Builder builder,
                         @Value("${economy-helper.market.data-go.base-url}") String baseUrl,
                         @Value("${economy-helper.market.data-go.api-key:}") String serviceKey,
                         Clock clock) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.clock = clock;
    }

    /** 종목명 부분검색. {@code 하이닉스} → SK하이닉스가 걸린다. */
    @Cacheable(cacheNames = "stock-price", key = "'name:' + #name", unless = "#result.isEmpty()")
    @RateLimiter(name = "dataGo")
    @CircuitBreaker(name = "dataGo")
    public List<StockPrice> searchByName(String name) {
        return searchRecent("likeItmsNm", name);
    }

    /** 종목코드 검색. {@code srtnCd}가 아니라 {@code likeSrtnCd}여야 한다. */
    @Cacheable(cacheNames = "stock-price", key = "'code:' + #code", unless = "#result.isEmpty()")
    @RateLimiter(name = "dataGo")
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
    record Body(int totalCount, Items items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Items(List<StockPrice> item) {}

    /**
     * @param basDt      기준일자 {@code yyyyMMdd}
     * @param srtnCd     단축코드 {@code 005930}
     * @param itmsNm     종목명 (한글)
     * @param mrktCtg    {@code KOSPI} · {@code KOSDAQ} · {@code KONEX}
     * @param clpr       종가 — 화면에 나가는 값
     * @param mrktTotAmt 시가총액 — 동명 후보를 가르는 내부 신호
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockPrice(String basDt, String srtnCd, String itmsNm, String mrktCtg,
                             String clpr, String mrktTotAmt) {}
}
