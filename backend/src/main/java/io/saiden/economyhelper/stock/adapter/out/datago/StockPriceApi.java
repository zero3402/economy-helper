package io.saiden.economyhelper.stock.adapter.out.datago;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.saiden.economyhelper.config.CacheNames;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 공공데이터포털 — 금융위원회 주식시세정보.
 *
 * <p>실제로 호출해 확인한 함정이 셋이다.
 *
 * <ol>
 *   <li><b>서비스키를 다시 인코딩하면 안 된다</b> — 403 "등록되지 않은 서비스키"({@link DataGoRequest})
 *   <li><b>{@code srtnCd}는 무시된다.</b> {@code srtnCd=005930}으로 조회했더니 전혀 다른 종목이
 *       나왔다. 종목코드로 찾으려면 <b>{@code likeSrtnCd}</b>를 써야 한다
 *   <li><b>전일 종가다.</b> 오늘 날짜로 조회하면 {@code totalCount=0}이다.
 *       비영업일·연휴를 감안해 하루씩 물려 되짚는다
 * </ol>
 *
 * <p>키가 URL에 실리므로 예외를 URL 없는 자체 예외로 바꿔 던지고, 리미터는 HTTP 호출 자리에서
 * 얻는다 — 둘 다 {@link DataGoRequest}가 한다.
 */
@Component
public class StockPriceApi {


    private static final String PATH = "/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo";

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
        this.limiter = DataGoRequest.limiterOf(limiters);
    }

    /** 종목명 부분검색. {@code 하이닉스} → SK하이닉스가 걸린다. */
    // ⚠️ 빈 결과도 담는다(다른 List 캐시와 반대다). 열흘을 되짚어 빈 것은 「없다」이고 한 시간은 안정된
    //    값이다 — 주식 API는 ETF 코드에 **구조적으로 늘 0건**이라, 안 담으면 조회마다 되짚기 열 번을 다시 쓴다
    @Cacheable(cacheNames = CacheNames.STOCK_PRICE, key = "'name:' + #name")
    @CircuitBreaker(name = "dataGo")
    public List<StockPrice> searchByName(String name) {
        return searchRecent("likeItmsNm", name, null);
    }

    /** 종목코드 검색. {@code srtnCd}가 아니라 {@code likeSrtnCd}여야 한다. */
    @Cacheable(cacheNames = CacheNames.STOCK_PRICE, key = "'code:' + #code")
    @CircuitBreaker(name = "dataGo")
    public List<StockPrice> searchByCode(String code) {
        return searchRecent("likeSrtnCd", code, code);
    }

    /** 가장 최근 영업일의 결과 — 되짚기와 URI 조립은 {@link DataGoRequest}가 맡는다. */
    private List<StockPrice> searchRecent(String filterParam, String filterValue, String exactCode) {
        return DataGoRequest.lookBack(clock, "stock", filterValue + " 시세",
                date -> request(date, filterParam, filterValue, exactCode),
                found -> !found.isEmpty(), List.of());
    }

    /**
     * @param exactCode 코드로 물었으면 그 코드. ⚠️ {@code likeSrtnCd}는 <b>부분일치</b>라 {@code 5930}이
     *                  {@code 005930}·{@code 059300}…을 다 준다 — 거르지 않으면 시총 1위인 <b>다른 종목</b>이
     *                  답이 된다(LLM이 앞자리를 빠뜨린 코드를 줄 때). {@code EtfPriceApi}와 같은 거름이다
     */
    private List<StockPrice> request(LocalDate date, String filterParam, String filterValue, String exactCode) {
        return DataGoRequest.items(restClient, limiter,
                        DataGoRequest.uri(baseUrl, PATH, serviceKey, date, filterParam, filterValue),
                        ROWS, date, "stock").stream()
                .filter(row -> exactCode == null || exactCode.equalsIgnoreCase(row.srtnCd()))
                .map(StockRow::toStockPrice)
                .toList();
    }

    private static final ParameterizedTypeReference<DataGoRequest.Response<StockRow>> ROWS =
            new ParameterizedTypeReference<>() {};

    /** 응답 한 행 — 코드는 거를 때만 읽고 담지 않는다({@link StockPrice}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StockRow(String basDt, String srtnCd, String itmsNm, String clpr, String fltRt, String mrktTotAmt) {

        StockPrice toStockPrice() {
            return new StockPrice(basDt, itmsNm, clpr, fltRt, mrktTotAmt);
        }
    }

    /**
     * <p><b>종목코드와 시장 구분은 담지 않는다.</b> 종목코드는 보내고 <b>거를 때</b>만 쓰고({@link StockRow}),
     * {@code KOSPI}·{@code KOSDAQ} 구분은 화면에 안 나간다 — 무리는 지역으로 가른다({@code StockQuote.Market}).
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
