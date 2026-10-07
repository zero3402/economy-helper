package io.saiden.economyhelper.crypto.adapter.out.binance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.config.EconomyHelperProperties.Binance;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.crypto.application.port.out.BinanceClient;
import io.saiden.economyhelper.crypto.domain.BinancePrice;
import io.saiden.economyhelper.crypto.domain.BinanceSymbol;
import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import io.saiden.economyhelper.shared.support.FailureReason;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 바이낸스 공개 시세 API — USDT 마켓 현재가.
 *
 * <p>인증이 없고 한도가 넉넉하다. 실측한 응답 헤더가 {@code x-mbx-used-weight-1m: 8}이고
 * 한도는 분당 1200(IP 기준)이라 우리 사용량으로는 닿을 일이 없다. 그래도 {@code @RateLimiter}를
 * 거는 것은 연타나 버그로 폭주할 때의 안전장치다.
 *
 * <p><b>호스트가 둘이다 — 지역 차단 때문이다.</b> {@code api.binance.com}이 451을 주면
 * 공개 데이터 미러({@code data-api.binance.vision})로 한 번 더 묻는다. 같은 스키마를 주므로
 * 파싱이 갈리지 않는다(실측 2026-08-20: 네 호스트가 모두 같은 본문을 준다).
 * <b>이중화가 아니라 우회다</b> — 상대가 죽어서가 아니라 우리 IP가 막혀서 가는 길이라,
 * 1순위가 살아 있으면 2순위는 영영 안 불린다.
 *
 * <p><b>지역 차단이 있다.</b> 미국 IP에서는 451이 떨어진다 — 이 서비스가 Singapore 리전에
 * 떠 있어서 쓸 수 있는 것이고, 리전을 옮기면 가장 먼저 깨질 연동이다.
 *
 * <p><b>418·429는 문을 닫는다</b>({@link BinanceBanGate}). 브레이커에 맡기면 열릴 때까지
 * 다섯 번을 더 부르는데, 바이낸스는 <b>밴 중의 호출로 밴을 연장한다</b> — 물러서는 판단을
 * 통계에 맡길 수 없는 자리다.
 *
 * <p>업비트와 마찬가지로 실패를 삼키지 않고 던진다. 다만 <b>바이낸스가 죽어도 업비트 시세는
 * 나가야 하므로</b> 그 판단은 {@code CryptoService}가 한다.
 */
@Component
public class BinanceApi implements BinanceClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceApi.class);

    private final RestClient restClient;
    private final BinanceBanGate banGate;

    /** 1순위와 2순위. 순서가 곧 우선순위다 — 앞의 것이 답하면 뒤는 부르지 않는다. */
    private final List<String> baseUrls;

    public BinanceApi(RestClient.Builder builder, BinanceBanGate banGate,
                      EconomyHelperProperties properties) {
        Binance binance = properties.market().binance();
        String fallbackUrl = binance.fallbackBaseUrl();
        this.restClient = builder.build();
        this.banGate = banGate;
        this.baseUrls = fallbackUrl == null || fallbackUrl.isBlank()
                ? List.of(binance.baseUrl())
                : List.of(binance.baseUrl(), fallbackUrl);
    }

    /**
     * 여러 심볼의 현재가를 <b>한 번에</b> 가져온다. 심볼은 {@link BinanceSymbol}이 유도하고,
     * 계약(400·좁은 예외)은 {@link BinanceClient#prices}에 있다.
     */
    @Cacheable(cacheNames = CacheNames.BINANCE_PRICE, key = "#symbols", unless = "#result.isEmpty()")
    @RateLimiter(name = "binance")
    @Retry(name = "binance")
    @CircuitBreaker(name = "binance")
    @Override
    public List<BinancePrice> prices(List<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        Instant bannedUntil = banGate.bannedUntil();
        if (bannedUntil != null) {
            throw new BinanceClient.Banned(bannedUntil);
        }
        RuntimeException failure = null;
        for (String baseUrl : baseUrls) {
            try {
                TickerResponse[] response = restClient.get()
                        .uri(URI.create(baseUrl + query(symbols)))
                        .retrieve()
                        .body(TickerResponse[].class);

                if (response == null) {
                    throw new IllegalStateException("바이낸스 시세 응답이 비어 있습니다: " + symbols);
                }
                if (failure != null) {
                    log.info("[crypto] 바이낸스 1순위가 막혀 {}가 답했습니다", host(baseUrl));
                }
                return Stream.of(response).map(TickerResponse::toPrice).toList();
            } catch (RuntimeException e) {
                // 없는 심볼은 호스트를 바꿔도 없다. 좁은 타입으로 갈라 던져 CryptoService가
                // '미상장'으로 읽고, 브레이커도 이것만 무시하게 한다
                int status = statusOf(e);
                if (status == UNKNOWN_SYMBOL) {
                    throw new BinanceClient.UnknownSymbol("바이낸스에 없는 심볼입니다: " + symbols, e);
                }
                log.warn("[crypto] 바이낸스 {} 조회 실패: {}", host(baseUrl), FailureReason.of(e));
                // ⚠️ **451에만 다음 호스트로 간다.** 418은 IP 밴이고(429를 받고도 계속 불러서
                //    생긴다) 바이낸스 규칙상 **계속 부르면 밴이 길어진다** — 미러를 부르는 것은
                //    밴 회피이고, 실측으로 두 호스트가 **한 IP 예산을 공유한다**:
                //    api → data-api를 번갈아 물으니 x-mbx-used-weight-1m이 2·4·6·8로 이어졌다.
                //    즉 우회해도 같은 밴을 받는다. 아무 이득 없이 밴만 늘리는 일이다
                if (status != REGION_BLOCKED) {
                    closeGateIfBanned(e, status);
                    throw e;
                }
                failure = e;
            }
        }
        throw failure;
    }

    /**
     * 418·429를 받았으면 문을 닫는다 — <b>다음 호출부터 HTTP가 아예 안 나간다.</b>
     *
     * <p>브레이커에 맡기면 열리기까지 다섯 번을 더 부르고, 그 다섯 번이 정확히 밴을 늘리는 호출이다.
     */
    private void closeGateIfBanned(RuntimeException e, int status) {
        if (status != IP_BANNED && status != TOO_MANY_REQUESTS) {
            return;
        }
        Duration wait = retryAfter(e)
                .orElse(status == IP_BANNED ? BinanceBanGate.MIN_BAN : BinanceBanGate.WARNING_BACKOFF);
        banGate.ban(wait);
        log.warn("[crypto] 바이낸스 호출을 {}초 동안 멈춥니다 (HTTP {}) — 밴 중에 부르면 밴이 길어집니다",
                wait.toSeconds(), status);
    }

    /**
     * {@code Retry-After}(초) — 바이낸스가 <b>언제 풀리는지 직접 말해 주는</b> 값이다.
     * 넘겨짚기보다 이것이 먼저다.
     *
     * <p>못 읽으면 빈 값을 준다. 헤더 하나 때문에 새 실패를 만들 이유가 없다 —
     * 그때는 부르는 쪽이 상대가 문서로 말한 최소값으로 넘겨짚는다.
     */
    private static Optional<Duration> retryAfter(RuntimeException e) {
        if (!(e instanceof HttpClientErrorException http)) {
            return Optional.empty();
        }
        HttpHeaders headers = http.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds > 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException ignored) {
            // 규격에는 HTTP 날짜 형식도 있지만 바이낸스는 초로 준다(실측). 못 읽으면 넘겨짚는다
            return Optional.empty();
        }
    }

    /** {@code -1121 Invalid symbol.} — 우리가 없는 심볼을 물은 것이다. */
    private static final int UNKNOWN_SYMBOL = 400;

    /** IP 자동 밴. 2분~3일이고 <b>밴 중의 호출이 그것을 연장한다</b> — 물러서는 것만이 답이다. */
    private static final int IP_BANNED = 418;

    /** 밴 직전 경고. 여기서 물러서지 않으면 {@link #IP_BANNED}가 된다. */
    private static final int TOO_MANY_REQUESTS = 429;

    /** 지역 차단. 호스트를 바꿔 볼 값이 있는 <b>유일한</b> 실패다. */
    private static final int REGION_BLOCKED = 451;

    /** @return HTTP 상태. HTTP 실패가 아니면 {@code 0} */
    private static int statusOf(RuntimeException e) {
        return e instanceof HttpClientErrorException http ? http.getStatusCode().value() : 0;
    }

    /** 로그에 호스트만 남긴다 — 어느 쪽이 답했는지가 진단의 전부다. */
    private static String host(String baseUrl) {
        return URI.create(baseUrl).getHost();
    }

    /**
     * {@code /api/v3/ticker/24hr?symbols=["BTCUSDT","ETHUSDT"]} 를 인코딩한다(등락률이 24hr에만 있다).
     *
     * <p><b>직접 조립해 완성된 URI로 넘긴다.</b> {@code uriBuilder}에 맡기면 이 값이 한 번 더
     * 인코딩돼 {@code %255B}가 되고 400이 난다 — {@code StockPriceApi}·{@code FmpApi}가
     * 같은 이유로 같은 방식을 쓴다.
     *
     * <p>심볼이 하나뿐이어도 배열로 보낸다. 단수 {@code symbol=}은 객체를, 복수 {@code symbols=}는
     * 배열을 돌려주는데, 개수에 따라 응답 모양이 갈리면 파싱이 두 갈래가 된다.
     */
    static String query(List<String> symbols) {
        String json = symbols.stream()
                .map(symbol -> "\"" + symbol + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "/api/v3/ticker/24hr?symbols=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    /**
     * {@code /ticker/24hr} 한 행. 필드 뜻은 {@link BinancePrice}에 있다.
     *
     * <p>현재가는 {@code price}가 아니라 {@code lastPrice}다. 등락률은 업비트와 달리 <b>이미 %다</b>
     * ({@code -1.451}이 -1.451%).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TickerResponse(String symbol, BigDecimal lastPrice, BigDecimal priceChangePercent) {

        BinancePrice toPrice() {
            return new BinancePrice(symbol, Price.of(lastPrice).orElse(null),
                    PercentChange.ofNullable(priceChangePercent));
        }
    }
}
