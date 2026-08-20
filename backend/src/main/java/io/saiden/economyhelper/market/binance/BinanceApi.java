package io.saiden.economyhelper.market.binance;

import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.support.FailureReason;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
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
 * <p>업비트와 마찬가지로 실패를 삼키지 않고 던진다. 다만 <b>바이낸스가 죽어도 업비트 시세는
 * 나가야 하므로</b> 그 판단은 {@code CryptoService}가 한다.
 */
@Component
public class BinanceApi {

    private static final Logger log = LoggerFactory.getLogger(BinanceApi.class);

    private final RestClient restClient;

    /** 1순위와 2순위. 순서가 곧 우선순위다 — 앞의 것이 답하면 뒤는 부르지 않는다. */
    private final List<String> baseUrls;

    public BinanceApi(RestClient.Builder builder,
                      @Value("${economy-helper.market.binance.base-url}") String baseUrl,
                      @Value("${economy-helper.market.binance.fallback-base-url:}") String fallbackUrl) {
        this.restClient = builder.build();
        this.baseUrls = fallbackUrl == null || fallbackUrl.isBlank()
                ? List.of(baseUrl)
                : List.of(baseUrl, fallbackUrl);
    }

    /**
     * 여러 심볼의 현재가를 <b>한 번에</b> 가져온다.
     *
     * <p>없는 심볼이 하나라도 섞이면 <b>요청 전체가 400</b>이다(실측: {@code USDTUSDT} →
     * {@code {"code":-1121,"msg":"Invalid symbol."}}). 그래서 호출 전에 걸러야 한다 —
     * {@link BinanceSymbol}이 그 일을 한다.
     */
    @Cacheable(cacheNames = CacheNames.BINANCE_PRICE, key = "#symbols", unless = "#result.isEmpty()")
    @RateLimiter(name = "binance")
    @Retry(name = "binance")
    @CircuitBreaker(name = "binance")
    public List<BinancePrice> prices(List<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        RuntimeException failure = null;
        for (String baseUrl : baseUrls) {
            try {
                BinancePrice[] response = restClient.get()
                        .uri(URI.create(baseUrl + query(symbols)))
                        .retrieve()
                        .body(BinancePrice[].class);

                if (response == null) {
                    throw new IllegalStateException("바이낸스 시세 응답이 비어 있습니다: " + symbols);
                }
                if (failure != null) {
                    log.info("[crypto] 바이낸스 1순위가 막혀 {}가 답했습니다", host(baseUrl));
                }
                return List.of(response);
            } catch (RuntimeException e) {
                // ⚠️ 없는 심볼(400)은 호스트를 바꿔도 없다 — 그건 그대로 던져 CryptoService가
                //    '미상장'으로 읽게 한다. 호스트를 바꿔 볼 값이 있는 것은 지역 차단·장애다
                if (e instanceof HttpClientErrorException http
                        && http.getStatusCode().value() == 400) {
                    throw e;
                }
                log.warn("[crypto] 바이낸스 {} 조회 실패: {}", host(baseUrl), FailureReason.of(e));
                failure = e;
            }
        }
        throw failure;
    }

    /**
     * {@code /api/v3/ticker/24hr?symbols=["BTCUSDT","ETHUSDT"]} 를 인코딩한다.
     * (등락률이 24hr에만 있어 {@code /ticker/price}에서 옮겼다 — 아래 레코드 주석 참조.)
     *
     * <p><b>직접 조립해 완성된 URI로 넘긴다.</b> {@code uriBuilder}에 맡기면 이 값이 한 번 더
     * 인코딩돼 {@code %255B}가 되고 400이 난다 — {@code StockPriceApi}·{@code FmpApi}가
     * 같은 이유로 같은 방식을 쓴다.
     *
     * <p>심볼이 하나뿐이어도 배열로 보낸다. 단수 {@code symbol=}은 객체를, 복수 {@code symbols=}는
     * 배열을 돌려주는데, 개수에 따라 응답 모양이 갈리면 파싱이 두 갈래가 된다.
     */
    /** 로그에 호스트만 남긴다 — 어느 쪽이 답했는지가 진단의 전부다. */
    private static String host(String baseUrl) {
        return URI.create(baseUrl).getHost();
    }

    static String query(List<String> symbols) {
        String json = symbols.stream()
                .map(symbol -> "\"" + symbol + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "/api/v3/ticker/24hr?symbols=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    /**
     * @param symbol             {@code BTCUSDT}
     * @param lastPrice          USDT 기준 현재가.
     *                           <b>{@code /ticker/price}의 {@code price}가 여기서는 이 이름이다</b> —
     *                           등락률을 얻으려 {@code /ticker/24hr}로 옮기면서 갈렸다
     * @param priceChangePercent 24시간 등락률(%). {@code -1.451}이면 -1.451%다.
     *                           업비트의 비율과 달리 이쪽은 이미 %다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BinancePrice(String symbol, BigDecimal lastPrice,
                               BigDecimal priceChangePercent) {}
}
