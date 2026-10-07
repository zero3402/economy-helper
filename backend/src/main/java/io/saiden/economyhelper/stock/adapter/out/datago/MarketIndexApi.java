package io.saiden.economyhelper.stock.adapter.out.datago;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.config.EconomyHelperProperties.DataGo;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 공공데이터포털 — 금융위원회 지수시세정보.
 *
 * <p>{@link StockPriceApi}와 <b>같은 게이트웨이·같은 서비스키</b>를 쓰므로 함정도 같다 —
 * 묻는 법·되짚기·리미터는 {@link DataGoRequest}에 있다.
 *
 * <p><b>종목과 결정적으로 다른 점: 순위 신호가 없다.</b> 종목은 시가총액으로 동명을 갈랐지만
 * 지수에는 그런 값이 없다. {@code 코스피}로 검색하면 32건(코스피 100·200·200 ESG…)이 나오는데,
 * 그중 <b>이름이 정확히 일치하는 것</b>이 사용자가 찾는 것이다 — 파생 지수는 이름이 더 길다.
 * 그래서 {@link #searchByName}은 완전일치를 먼저 보고, 없을 때만 가장 짧은 이름을 고른다.
 */
@Component
public class MarketIndexApi {


    private static final String PATH = "/1160100/service/GetMarketIndexInfoService/getStockMarketIndex";

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final Clock clock;
    /** 되짚기 루프가 실제로 태우는 호출을 세는 자리. {@code null}이면 세지 않는다(테스트). */
    private final RateLimiter limiter;

    public MarketIndexApi(RestClient.Builder builder, EconomyHelperProperties properties,
                          Clock clock, RateLimiterRegistry limiters) {
        DataGo dataGo = properties.market().dataGo();
        this.restClient = builder.build();
        this.baseUrl = dataGo.baseUrl();
        this.serviceKey = dataGo.apiKey();
        this.clock = clock;
        this.limiter = DataGoRequest.limiterOf(limiters);
    }

    /**
     * 지수명으로 찾는다.
     *
     * @return 이름이 정확히 일치하는 지수. 없으면 부분일치 중 <b>이름이 가장 짧은 것</b> —
     *         파생 지수({@code 코스피 200 ESG 지수})보다 본 지수({@code 코스피})가 짧다
     */
    // 캐시를 StockPriceApi와 나눠 쓸 수 없다. stock-price는 List<StockPrice>로 역직렬화하도록
    // 못 박혀 있어 MarketIndex를 넣으면 쓰기는 되고 읽기에서 터진다(캐시 히트에서만 — 실물에서
    // 브리핑 지수 2개가 조용히 빠졌다). 캐시 이름 하나에 타입 하나다.
    @Cacheable(cacheNames = CacheNames.MARKET_INDEX, key = "#name", unless = "#result == null")
    @CircuitBreaker(name = "dataGo")
    public MarketIndex searchByName(String name) {
        // ⚠️ 응답이 비지 않았다고 답이 있는 것은 아니다 — pick()이 null이면 lookBack이 어제로
        //    넘어간다(DataGoRequest.lookBack의 usable 참고)
        return DataGoRequest.lookBack(clock, "index", name + " 지수",
                date -> {
                    List<MarketIndex> found = request(date, name);
                    return found.isEmpty() ? null : pick(found, name);
                },
                Objects::nonNull, null);
    }

    /** 완전일치 우선, 없으면 가장 짧은 이름. 정규화는 공백만 지우면 충분하다. */
    private static MarketIndex pick(List<MarketIndex> candidates, String query) {
        String wanted = compact(query);
        // ⚠️ 이름 없는 후보를 먼저 걸러낸다. 완전일치 쪽은 compact()가 널을 받아 주는데
        //    폴백 비교자는 idxNm().length()를 무방비로 부른다 — 이름 하나가 비어 오면
        //    조회 전체가 NPE로 죽고, 그건 "그런 지수가 없다"와 구분되지 않는다
        List<MarketIndex> named = candidates.stream()
                .filter(index -> index.idxNm() != null)
                .toList();
        return named.stream()
                .filter(index -> compact(index.idxNm()).equals(wanted))
                .findFirst()
                .orElseGet(() -> named.stream()
                        .min((a, b) -> Integer.compare(a.idxNm().length(), b.idxNm().length()))
                        .orElse(null));
    }

    private static String compact(String value) {
        return value == null ? "" : value.replace(" ", "");
    }

    private List<MarketIndex> request(LocalDate date, String name) {
        return DataGoRequest.items(restClient, limiter,
                DataGoRequest.uri(baseUrl, PATH, serviceKey, date, "likeIdxNm", name),
                ROWS, date, "index");
    }

    private static final ParameterizedTypeReference<DataGoRequest.Response<MarketIndex>> ROWS =
            new ParameterizedTypeReference<>() {};

    /**
     * <p>분류({@code idxCsf} — {@code KOSPI시리즈} 등)도 함께 오지만 담지 않는다. 화면에
     * 안 나가고 고를 때도 쓰지 않는다 — 동명은 완전일치와 이름 길이로 가른다({@link #pick}).
     *
     * @param idxNm 지수명 {@code 코스피}
     * @param clpr  종가 — 지수는 통화 단위가 없다
     * @param fltRt 등락률(%). 지수도 종목과 같은 필드명으로 온다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketIndex(String basDt, String idxNm, String clpr, String fltRt) {}
}
