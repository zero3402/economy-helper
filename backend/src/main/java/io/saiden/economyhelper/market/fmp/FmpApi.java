package io.saiden.economyhelper.market.fmp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Financial Modeling Prep — 미국 주식·지수 현재가.
 *
 * <p>실제로 호출해 확인한 것들이다.
 *
 * <ul>
 *   <li><b>{@code /api/v3}는 죽었다.</b> "Legacy Endpoint ... only available for legacy users
 *       who have valid subscriptions prior August 31, 2025" 403. {@code /stable}만 산다
 *   <li><b>무료는 미국 거래소 전용이다.</b> {@code 005930.KS}·{@code ^KS11}·{@code USDKRW}는
 *       전부 402다 — 그래서 국내는 공공데이터포털을 그대로 쓴다
 *   <li><b>배치가 안 된다.</b> {@code symbol=A,B}도 {@code batch-quote}도 막혀 심볼당 1회다
 *   <li>{@code quote} 하나에 {@code marketCap}·{@code exchange}·{@code timestamp}가 다 있다 —
 *       시가총액을 따로 부를 필요가 없다
 * </ul>
 *
 * <p><b>한도가 하루 250회이고 응답에 레이트리밋 헤더가 없다.</b> 그래서 우리가 센다
 * ({@link FmpQuotaGuard}). 캐시는 1분이 상한이다 — 그보다 길게 잡으면 "현재가"가 아니게 된다.
 *
 * <p>키가 쿼리 파라미터에 실리므로 {@code KeximFxClient}·{@code StockPriceApi}와 같은 규칙을 따른다:
 * <b>예외를 그대로 흘리지 않고 URL 없는 자체 예외로 바꿔 던진다.</b>
 */
@Component
public class FmpApi {

    private static final Logger log = LoggerFactory.getLogger(FmpApi.class);

    private static final String PATH = "/stable/quote";

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final FmpQuotaGuard quota;

    public FmpApi(RestClient.Builder builder,
                  @Value("${economy-helper.market.fmp.base-url}") String baseUrl,
                  @Value("${economy-helper.market.fmp.api-key:}") String apiKey,
                  FmpQuotaGuard quota) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.quota = quota;
    }

    /**
     * 미국 심볼 현재가. {@code AAPL} · {@code ^IXIC} 모두 같은 엔드포인트다.
     *
     * @return 없는 심볼이면 {@code null} — LLM이 지어낸 심볼이 여기서 걸러진다
     */
    @Cacheable(cacheNames = "us-quote", key = "#symbol", unless = "#result == null")
    @RateLimiter(name = "fmp")
    @CircuitBreaker(name = "fmp")
    public FmpQuote quote(String symbol) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FMP API 키가 없습니다");
        }
        if (!quota.tryAcquire()) {
            // 어차피 거절당한다. 부르지 않는 편이 빠르고 로그도 깨끗하다
            throw new IllegalStateException("FMP 일일 호출 한도를 소진했습니다");
        }

        // 심볼에 ^(지수)가 들어가므로 반드시 인코딩한다. 키는 그대로 붙인다(재인코딩 대상이 아니다)
        String uri = baseUrl + PATH + "?symbol=" + encode(symbol) + "&apikey=" + apiKey;
        try {
            List<FmpQuote> found = restClient.get().uri(URI.create(uri)).retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<FmpQuote>>() {});
            if (found == null || found.isEmpty()) {
                log.info("[fmp] '{}' 심볼이 없습니다", symbol);
                return null;
            }
            return found.get(0);
        } catch (RuntimeException e) {
            // 원래 예외 메시지에는 apikey가 박힌 URL이 들어간다 — 그대로 흘리면 키가 유출된다.
            // 402/403은 요금제 문제라 재시도해도 소용없다는 것을 메시지로 구분해 둔다.
            String reason = e.toString().contains("402") || e.toString().contains("403")
                    ? "요금제로 막힌 심볼이거나 키가 잘못됐습니다"
                    : e.getClass().getSimpleName();
            log.warn("[fmp] '{}' 조회 실패: {}", symbol, reason);
            throw new IllegalStateException("FMP 조회 실패 (" + symbol + "): " + reason);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * @param price            현재가 — 우리가 화면에 쓰는 값
     * @param changePercentage 전일 대비 등락률(%). {@code 0.99586}이면 +0.99586%다 —
     *                         비율이 아니라 이미 %다(업비트는 비율로 준다)
     * @param exchange         {@code NASDAQ} · {@code NYSE}. 지수는 비어 오기도 한다
     * @param timestamp        epoch 초. 값의 신선도를 밝히는 데 쓴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FmpQuote(String symbol, String name, java.math.BigDecimal price,
                           java.math.BigDecimal changePercentage,
                           String exchange, java.math.BigDecimal marketCap, Long timestamp) {}
}
