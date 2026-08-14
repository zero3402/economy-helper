package io.saiden.economyhelper.market.upbit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 업비트 시세 API. 인증이 없어 공개 엔드포인트 두 개만 부른다.
 *
 * <p><b>초당 10회 / IP 제한이 있다</b>(quotation 그룹 공유). {@code @RateLimiter}로 8회로
 * 낮춰 잡고, 마켓 목록은 6시간 캐시해 아예 부르지 않는다.
 *
 * <p>{@link UpbitMarketIndex}와 분리한 이유는 {@code @Cacheable}·{@code @CircuitBreaker}가
 * 프록시 기반이라서다 — 같은 클래스 안에서 부르면 프록시를 타지 않아 캐시도 브레이커도
 * 조용히 무력화된다({@code HackerNewsApi}가 이미 같은 이유로 분리돼 있다).
 *
 * <p>실패는 삼키지 않고 던진다. "시세를 못 가져왔다"를 사용자에게 알릴지는 호출자가 정한다.
 */
@Component
public class UpbitApi {

    /** 원화 마켓만 쓴다. BTC 마켓(예: {@code BTC-ETH})은 가격 단위가 비트코인이라 섞이면 안 된다. */
    private static final String KRW_PREFIX = "KRW-";

    private final RestClient restClient;

    public UpbitApi(RestClient.Builder builder,
                    @Value("${economy-helper.market.upbit.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * 원화 마켓 전체 목록.
     *
     * <p>신규 상장·상장폐지는 드물어 6시간 캐시로 충분하다. 이게 없으면 {@code /crypto} 한 번에
     * 조회가 두 번 나간다.
     */
    @Cacheable(cacheNames = "upbit-markets", unless = "#result.isEmpty()")
    @RateLimiter(name = "upbit")
    @CircuitBreaker(name = "upbit")
    public List<UpbitMarket> krwMarkets() {
        MarketResponse[] response = restClient.get()
                .uri("/v1/market/all")
                .retrieve()
                .body(MarketResponse[].class);

        if (response == null) {
            throw new IllegalStateException("업비트 마켓 목록 응답이 비어 있습니다");
        }
        return List.of(response).stream()
                .filter(market -> market.market() != null && market.market().startsWith(KRW_PREFIX))
                .map(market -> UpbitMarket.of(market.market(), market.koreanName(), market.englishName()))
                .toList();
    }

    /**
     * 여러 마켓의 시세를 <b>한 번에</b> 가져온다.
     *
     * <p>동명 후보를 거래대금으로 가릴 때도 이 호출 하나로 끝난다 — 후보마다 따로 부르면
     * 초당 10회 제한에 금방 닿는다.
     *
     * @param markets 마켓 코드들. 비어 있으면 호출하지 않는다
     */
    @Cacheable(cacheNames = "crypto-price", key = "#markets", unless = "#result.isEmpty()")
    @RateLimiter(name = "upbit")
    @CircuitBreaker(name = "upbit")
    public List<UpbitTicker> tickers(List<String> markets) {
        if (markets.isEmpty()) {
            return List.of();
        }
        UpbitTicker[] response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/ticker")
                        .queryParam("markets", String.join(",", markets))
                        .build())
                .retrieve()
                .body(UpbitTicker[].class);

        if (response == null) {
            throw new IllegalStateException("업비트 시세 응답이 비어 있습니다: " + markets);
        }
        return List.of(response);
    }

    // --- 응답 스키마 (필요한 필드만) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketResponse(String market,
                          @JsonProperty("korean_name") String koreanName,
                          @JsonProperty("english_name") String englishName) {}

    /**
     * @param tradePrice        현재가 — 화면에 나가는 유일한 값
     * @param accTradePrice24h  24시간 누적 거래대금. <b>화면용이 아니라</b> 동명 후보를 가르는 신호다.
     *                          {@code 비트}는 비트코인·비트코인캐시·비트텐서에 모두 걸리는데,
     *                          거래대금이 47배 차이라 이걸로 갈리면 LLM을 부를 필요가 없다
     * @param signedChangeRate  전일 종가 대비 등락률. <b>%가 아니라 비율이다</b> —
     *                          {@code -0.0070571945}가 -0.71%다. 100을 곱하는 곳은
     *                          {@code CryptoService} 한 곳뿐이어야 한다
     * @param tradeTimestamp    체결 시각(epoch millis)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpbitTicker(String market,
                              @JsonProperty("trade_price") BigDecimal tradePrice,
                              @JsonProperty("acc_trade_price_24h") BigDecimal accTradePrice24h,
                              @JsonProperty("signed_change_rate") BigDecimal signedChangeRate,
                              @JsonProperty("trade_timestamp") Long tradeTimestamp) {}
}
