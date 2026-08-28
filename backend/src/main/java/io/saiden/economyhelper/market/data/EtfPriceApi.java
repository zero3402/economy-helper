package io.saiden.economyhelper.market.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 공공데이터포털 — 금융위원회 <b>증권상품시세정보</b>의 ETF 시세. 국내 ETF의 2순위다.
 *
 * <p><b>주식시세정보({@link StockPriceApi})는 ETF를 아예 주지 않는다</b>(실측 2026-08-28:
 * {@code likeItmsNm=KODEX}가 0건). 그래서 ETF는 이 API가 따로 맡는다 — 같은 키({@code DATA_API_KEY})지만
 * <b>활용신청이 따로</b>이고, 신청 전에는 매 호출이 403 {@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}다.
 *
 * <p>실측(2026-08-28, 승인 뒤)으로 확인한 것 셋:
 * <ol>
 *   <li><b>{@code srtnCd}는 무시된다</b> — {@code srtnCd=426030}에 ETF 1,164건이 통째로 온다.
 *       주식 API와 같은 함정이라 {@code likeSrtnCd}를 쓴다(1건). 그래도 응답의 {@code srtnCd}가
 *       물어본 코드와 같은지 <b>한 번 더 걸러낸다</b> — 필터가 무시되는 API에서 「시총 1위」를
 *       고르면 아무 코드에나 가장 큰 ETF가 답으로 나간다
 *   <li>필드 이름이 주식과 같다({@code basDt·itmsNm·clpr·fltRt·mrktTotAmt}) — 그래서 결과를
 *       {@link StockPrice}로 옮겨 같은 캐시({@code stock-price}, 접두사 {@code etf-})와 같은 선택
 *       규칙({@code DataGoStockClient.best})을 그대로 탄다
 *   <li>{@code fltRt}가 {@code -.14} 꼴(정수부 없음)로 온다 — {@code BigDecimal}이 그대로 읽는다
 * </ol>
 *
 * <p>⚠️ <b>브레이커는 주식과 따로다({@code dataGoEtf}).</b> 활용신청이 안 됐거나 만료되면 403이
 * 매 조회마다 나는데, 주식과 한 브레이커면 그 403이 쌓여 <b>주식 2순위까지 끊는다</b> —
 * {@code dataGo}/{@code weatherKma}를 가른 그 규칙이다. 리미터는 키가 하나라 {@code dataGo}를 함께 쓴다.
 */
@Component
public class EtfPriceApi {

    private static final String PATH = "/1160100/service/GetSecuritiesProductInfoService/getETFPriceInfo";

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final Clock clock;
    private final RateLimiter limiter;

    public EtfPriceApi(RestClient.Builder builder,
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

    /** ETF 이름 부분검색. {@code 나스닥100}에 22건이 걸린다(실측). */
    @Cacheable(cacheNames = CacheNames.STOCK_PRICE, key = "'etf-name:' + #name", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "dataGoEtf")
    public List<StockPrice> searchByName(String name) {
        return searchRecent("likeItmsNm", name, null);
    }

    /** ETF 코드 검색 — {@code likeSrtnCd}로 묻고 {@code srtnCd}로 한 번 더 거른다. */
    @Cacheable(cacheNames = CacheNames.STOCK_PRICE, key = "'etf-code:' + #code", unless = "#result.isEmpty()")
    @CircuitBreaker(name = "dataGoEtf")
    public List<StockPrice> searchByCode(String code) {
        return searchRecent("likeSrtnCd", code, code);
    }

    private List<StockPrice> searchRecent(String filterParam, String filterValue, String exactCode) {
        return DataGoRequest.lookBack(clock, "etf", filterValue + " 시세",
                date -> request(date, filterParam, filterValue, exactCode),
                found -> !found.isEmpty(), List.of());
    }

    private List<StockPrice> request(LocalDate date, String filterParam, String filterValue, String exactCode) {
        Response response = DataGoRequest.fetch(restClient, limiter,
                DataGoRequest.uri(baseUrl, PATH, serviceKey, date, filterParam, filterValue),
                Response.class, date, "etf");
        return extract(response).stream()
                .filter(row -> exactCode == null || exactCode.equalsIgnoreCase(row.srtnCd()))
                .map(EtfPrice::toStockPrice)
                .toList();
    }

    private static List<EtfPrice> extract(Response response) {
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
    record Items(List<EtfPrice> item) {}

    /**
     * 주식 응답과 다른 점은 {@code srtnCd}를 <b>읽는다</b>는 것 하나다 — 코드 필터가 무시되는지
     * 우리가 확인해야 해서다. 화면으로 나가는 것은 {@link #toStockPrice}가 옮긴 다섯 값뿐이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EtfPrice(String basDt, String srtnCd, String itmsNm, String clpr, String fltRt,
                    String mrktTotAmt) {

        StockPrice toStockPrice() {
            return new StockPrice(basDt, itmsNm, clpr, fltRt, mrktTotAmt);
        }
    }
}
