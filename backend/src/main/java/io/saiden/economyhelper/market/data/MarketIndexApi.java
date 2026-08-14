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
 * 공공데이터포털 — 금융위원회 지수시세정보.
 *
 * <p>{@link StockPriceApi}와 <b>같은 게이트웨이·같은 서비스키</b>를 쓰므로 함정도 같다:
 * 키를 다시 인코딩하면 403이고, 오늘 날짜는 항상 0건이라 어제부터 되짚어야 한다.
 * 그래서 URI도 직접 조립하고 예외도 URL 없이 다시 던진다.
 *
 * <p><b>종목과 결정적으로 다른 점: 순위 신호가 없다.</b> 종목은 시가총액으로 동명을 갈랐지만
 * 지수에는 그런 값이 없다. {@code 코스피}로 검색하면 32건(코스피 100·200·200 ESG…)이 나오는데,
 * 그중 <b>이름이 정확히 일치하는 것</b>이 사용자가 찾는 것이다 — 파생 지수는 이름이 더 길다.
 * 그래서 {@link #searchByName}은 완전일치를 먼저 보고, 없을 때만 가장 짧은 이름을 고른다.
 */
@Component
public class MarketIndexApi {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexApi.class);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PATH = "/1160100/service/GetMarketIndexInfoService/getStockMarketIndex";

    private static final int MAX_LOOKBACK_DAYS = 10;

    /** {@code 코스피}가 32건을 물어 온다. 넉넉히 받아 완전일치를 놓치지 않는다. */
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final Clock clock;

    public MarketIndexApi(RestClient.Builder builder,
                          @Value("${economy-helper.market.data-go.base-url}") String baseUrl,
                          @Value("${economy-helper.market.data-go.api-key:}") String serviceKey,
                          Clock clock) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.clock = clock;
    }

    /**
     * 지수명으로 찾는다.
     *
     * @return 이름이 정확히 일치하는 지수. 없으면 부분일치 중 <b>이름이 가장 짧은 것</b> —
     *         파생 지수({@code 코스피 200 ESG 지수})보다 본 지수({@code 코스피})가 짧다
     */
    // 캐시를 StockPriceApi와 나눠 쓸 수 없다. stock-price는 List<StockPrice>로 역직렬화하도록
    // 못 박혀 있어 MarketIndex를 넣으면 쓰기는 되고 읽기에서 터진다 — 캐시 히트에서만 나는 버그라
    // 실물에서야 드러났다(브리핑 지수 2개가 조용히 빠졌다). 캐시 이름 하나에 타입 하나다.
    @Cacheable(cacheNames = "market-index", key = "#name", unless = "#result == null")
    @RateLimiter(name = "dataGo")
    @CircuitBreaker(name = "dataGo")
    public MarketIndex searchByName(String name) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);

        for (int back = 1; back <= MAX_LOOKBACK_DAYS; back++) {
            List<MarketIndex> found = request(today.minusDays(back), name);
            if (!found.isEmpty()) {
                return pick(found, name);
            }
        }
        log.info("[index] 최근 {}일 안에 '{}' 지수가 없습니다", MAX_LOOKBACK_DAYS, name);
        return null;
    }

    /** 완전일치 우선, 없으면 가장 짧은 이름. 정규화는 공백만 지우면 충분하다. */
    private static MarketIndex pick(List<MarketIndex> candidates, String query) {
        String wanted = compact(query);
        return candidates.stream()
                .filter(index -> compact(index.idxNm()).equals(wanted))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .min((a, b) -> Integer.compare(a.idxNm().length(), b.idxNm().length()))
                        .orElse(null));
    }

    private static String compact(String value) {
        return value == null ? "" : value.replace(" ", "");
    }

    private List<MarketIndex> request(LocalDate date, String name) {
        // 서비스키는 이미 인코딩된 형태다 — 다시 인코딩하면 403이 난다 (StockPriceApi와 같다)
        String uri = baseUrl + PATH
                + "?serviceKey=" + serviceKey
                + "&resultType=json"
                + "&numOfRows=" + PAGE_SIZE
                + "&pageNo=1"
                + "&basDt=" + date.format(BAS_DT)
                + "&likeIdxNm=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        try {
            Response response = restClient.get().uri(URI.create(uri)).retrieve().body(Response.class);
            if (response == null || response.response() == null || response.response().body() == null) {
                return List.of();
            }
            Body body = response.response().body();
            return body.items() == null || body.items().item() == null ? List.of() : body.items().item();
        } catch (RuntimeException e) {
            log.warn("[index] {} 조회 실패: {}", date.format(BAS_DT), e.getClass().getSimpleName());
            throw new IllegalStateException("지수 조회 실패 (basDt=" + date.format(BAS_DT) + ")");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(@com.fasterxml.jackson.annotation.JsonProperty("response") Envelope response) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Body body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Body(int totalCount, Items items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Items(List<MarketIndex> item) {}

    /**
     * @param idxNm  지수명 {@code 코스피}
     * @param idxCsf 분류 {@code KOSPI시리즈} · {@code KOSDAQ시리즈} · {@code KRX시리즈}
     * @param clpr   종가 — 지수는 통화 단위가 없다
     * @param fltRt  등락률(%). 지수도 종목과 같은 필드명으로 온다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketIndex(String basDt, String idxNm, String idxCsf, String clpr,
                              String fltRt) {}
}
