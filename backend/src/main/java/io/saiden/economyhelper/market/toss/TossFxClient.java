package io.saiden.economyhelper.market.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 토스증권 환율 — 이중화의 1순위.
 *
 * <p>1분 주기로 갱신되는 참고 고시 환율이라 사용자가 기대하는 "지금 환율"에 가장 가깝다.
 * {@code MARKET_INFO} 그룹(초당 3회)을 쓴다.
 *
 * <p><b>값이 전부 문자열로 온다</b>({@code "rate":"1414.7"}). {@code BigDecimal(String)}으로
 * 받아야 부동소수점 오차 없이 그대로 보여줄 수 있다.
 *
 * <p>401·429 처리가 이 클래스의 실질적인 내용이다 — 아래 {@link #fetch} 참조.
 */
@Component
public class TossFxClient implements FxRateClient {

    private static final Logger log = LoggerFactory.getLogger(TossFxClient.class);

    /** 서버가 우리보다 먼저 토큰을 무효화했을 때의 코드. 이때만 재발급 후 1회 재시도한다. */
    private static final String EXPIRED_TOKEN = "expired-token";
    private static final String INVALID_TOKEN = "invalid-token";

    /** {@code retry-after}가 없거나 이상할 때 쓸 대기 시간. */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 1;
    private static final long MAX_RETRY_AFTER_SECONDS = 5;

    private final RestClient restClient;
    private final TossTokenProvider tokens;
    private final Clock clock;

    public TossFxClient(RestClient.Builder builder,
                        @Value("${economy-helper.market.toss.base-url}") String baseUrl,
                        TossTokenProvider tokens,
                        Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public FxSource source() {
        return FxSource.TOSS;
    }

    /**
     * <p>캐시 TTL은 1분이다. 응답의 {@code validUntil}이 실측상 5분 뒤였지만 보수적으로 잡는다 —
     * 환율에서 낡은 값을 보여주는 쪽이 한 번 더 호출하는 것보다 나쁘다.
     */
    @Override
    @Cacheable(cacheNames = "fx", unless = "#result == null")
    @RateLimiter(name = "tossMarketInfo")
    @CircuitBreaker(name = "fxToss")
    public FxRate usdToKrw() {
        return toRate(fetch(true));
    }

    /**
     * 401·429를 각각 <b>한 번만</b> 되짚는다.
     *
     * <ul>
     *   <li><b>401 {@code expired-token}</b> — 서버가 우리보다 먼저 토큰을 버렸다.
     *       토큰을 폐기하고 새로 받아 한 번 더 시도한다. 다른 401(권한 없음 등)은 재시도해도
     *       같은 답이므로 그대로 실패시킨다
     *   <li><b>429</b> — {@code retry-after}만큼 기다렸다 한 번 더. 무한 재시도는 하지 않는다.
     *       반복되면 우리 리미터 설정이 틀린 것이고, 그건 브레이커가 열려 드러나야 한다
     * </ul>
     *
     * @param mayRetry 재시도로 들어온 호출이면 {@code false} — 여기서 재귀가 끝난다
     */
    private ExchangeRateResponse fetch(boolean mayRetry) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/exchange-rate")
                            .queryParam("baseCurrency", "USD")
                            .queryParam("quoteCurrency", "KRW")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .body(ExchangeRateResponse.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            if (mayRetry && isTokenExpired(e)) {
                log.info("[toss] 토큰이 만료돼 재발급 후 재시도합니다");
                tokens.invalidate();
                return fetch(false);
            }
            throw e;
        } catch (HttpClientErrorException.TooManyRequests e) {
            if (!mayRetry) {
                throw e;
            }
            long wait = retryAfterSeconds(e);
            log.warn("[toss] 레이트리밋에 걸려 {}초 뒤 한 번만 재시도합니다", wait);
            sleep(wait);
            return fetch(false);
        }
    }

    /** 응답 본문의 {@code error.code}로 판별한다 — 상태코드만으로는 만료와 권한 오류를 못 가른다. */
    private static boolean isTokenExpired(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return body.contains(EXPIRED_TOKEN) || body.contains(INVALID_TOKEN);
    }

    private static long retryAfterSeconds(HttpClientErrorException e) {
        String header = e.getResponseHeaders() == null
                ? null
                : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            long seconds = header == null ? DEFAULT_RETRY_AFTER_SECONDS : Long.parseLong(header.trim());
            // 상대가 말도 안 되는 값을 줘도 요청 스레드를 오래 붙잡지 않는다
            // (Math.clamp은 Java 21부터라 직접 자른다 — 이 프로젝트는 17이다)
            return Math.min(Math.max(seconds, DEFAULT_RETRY_AFTER_SECONDS), MAX_RETRY_AFTER_SECONDS);
        } catch (NumberFormatException ignored) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 중단됐습니다", e);
        }
    }

    private FxRate toRate(ExchangeRateResponse response) {
        if (response == null || response.result() == null || response.result().rate() == null) {
            throw new IllegalStateException("토스 환율 응답에 rate가 없습니다");
        }
        Result result = response.result();
        return new FxRate("USD", "KRW", new BigDecimal(result.rate()), FxSource.TOSS, validFrom(result));
    }

    /** {@code validFrom}이 없거나 깨졌으면 지금으로 본다 — 값 자체는 유효하다. */
    private Instant validFrom(Result result) {
        if (result.validFrom() == null) {
            return clock.instant();
        }
        try {
            return OffsetDateTime.parse(result.validFrom()).toInstant();
        } catch (RuntimeException ignored) {
            return clock.instant();
        }
    }

    // --- 응답 스키마 (필요한 필드만). 성공은 result, 실패는 error 봉투다 ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExchangeRateResponse(Result result) {}

    /** 값이 전부 문자열이다 — {@code "1414.7"}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(String baseCurrency, String quoteCurrency, String rate, String validFrom) {}
}
