package io.saiden.economyhelper.market.upbit;

import java.util.ArrayList;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.chart.DailySeries;
import io.saiden.economyhelper.market.chart.DailyBar;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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
    @Cacheable(cacheNames = CacheNames.UPBIT_MARKETS, unless = "#result.isEmpty()")
    @RateLimiter(name = "upbit")
    @Retry(name = "upbit")
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
     * 일봉 — <b>차트가 그리는 것.</b>
     *
     * <p>⚠️ <b>세 도메인 중 여기만 진짜 새 호출이다.</b> 환율과 주식은 일봉이 이미 시세 응답에
     * 오는데(버리고 있었다) 코인은 캔들 엔드포인트가 따로다. 대신 업비트는 <b>키가 없고</b>
     * 이 호출도 같은 quotation 그룹이라 초당 10회(우리는 8회로 낮춰 잡음) 안에서 돈다 —
     * 조회당 하나 늘어도 한도에 닿지 않는다.
     *
     * <p><b>바이낸스 klines를 쓰지 않는다.</b> 원화 시세는 업비트가 주므로 얻을 것이 없고,
     * 그쪽은 밴 게이트 옆이라 호출을 늘리는 값이 다르다 — 밴 중의 호출이 밴을 연장한다.
     *
     * <p>⚠️ {@code count}로 개수를 받으므로 되짚기가 없다. 다만 <b>상장 직후 코인은 요청한
     * 개수보다 적게 온다</b> — 그건 실패가 아니라 「그만큼밖에 없다」이고,
     * {@code DailySeries.drawable}이 점 하나짜리를 걸러낸다.
     *
     * @param market 마켓 코드({@code KRW-BTC})
     */
    // ⚠️ **빈 목록은 캐시하지 않는다.** 새로 상장된 코인이나 일시적 빈 배열이 그대로 굳으면
    //    그 코인 차트가 TTL(1시간) 내내 빠진다 — 브리핑이 09~10시 창에서 10분마다 도는데
    //    그 창과 길이가 겹치므로, 09시의 빈손 하나가 그날 브리핑 전체에서 차트를 지운다.
    //    FeedFetcher가 「빈 결과는 캐시하지 않는다」를 세운 것과 같은 자리다. 다시 부르는
    //    대가는 업비트 호출 하나뿐이다(키도 일 한도도 없고 초당 8회 리미터 안이다)
    @Cacheable(cacheNames = CacheNames.CRYPTO_SERIES, key = "#market", unless = "#result.isEmpty()")
    @RateLimiter(name = "upbit")
    @Retry(name = "upbit")
    @CircuitBreaker(name = "upbit")
    public List<DailyBar> dailyBars(String market) {
        Candle[] response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/candles/days")
                        .queryParam("market", market)
                        .queryParam("count", DailySeries.WINDOW)
                        .build())
                .retrieve()
                .body(Candle[].class);

        if (response == null) {
            throw new IllegalStateException("업비트 일봉 응답이 비어 있습니다: " + market);
        }
        List<DailyBar> bars = new ArrayList<>();
        for (Candle candle : response) {
            if (candle != null && candle.date() != null && candle.close() != null) {
                bars.add(new DailyBar(java.time.LocalDate.parse(candle.date()), candle.close()));
            }
        }
        // 업비트는 최근 것이 먼저 온다 — 정렬과 걸러내기는 한 곳에서 한다
        return DailySeries.recent(bars, DailySeries.WINDOW);
    }

    /**
     * 여러 마켓의 시세를 <b>한 번에</b> 가져온다.
     *
     * <p>동명 후보를 거래대금으로 가릴 때도 이 호출 하나로 끝난다 — 후보마다 따로 부르면
     * 초당 10회 제한에 금방 닿는다.
     *
     * @param markets 마켓 코드들. 비어 있으면 호출하지 않는다
     */
    @Cacheable(cacheNames = CacheNames.CRYPTO_PRICE, key = "#markets", unless = "#result.isEmpty()")
    @RateLimiter(name = "upbit")
    @Retry(name = "upbit")
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
     *                          {@code PercentChange.fromRatio} 한 곳뿐이어야 한다
     *                          ({@code CryptoService}가 그것을 부른다)
     * @param tradeTimestamp    체결 시각(epoch millis)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpbitTicker(String market,
                              @JsonProperty("trade_price") BigDecimal tradePrice,
                              @JsonProperty("acc_trade_price_24h") BigDecimal accTradePrice24h,
                              @JsonProperty("signed_change_rate") BigDecimal signedChangeRate,
                              @JsonProperty("trade_timestamp") Long tradeTimestamp) {}

    /**
     * 일봉 한 칸.
     *
     * @param date  {@code candle_date_time_kst} 앞 열 자리 — <b>KST 기준</b>이라 우리 달력과 같다
     *              ({@code candle_date_time_utc}를 쓰면 하루가 어긋난다)
     * @param close {@code trade_price} — 그 날의 종가
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candle(@JsonProperty("candle_date_time_kst") String dateTime,
                  @JsonProperty("trade_price") java.math.BigDecimal close) {

        /** {@code 2026-08-21T00:00:00} → {@code 2026-08-21}. */
        String date() {
            return dateTime == null || dateTime.length() < 10 ? null : dateTime.substring(0, 10);
        }
    }
}
