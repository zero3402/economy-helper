package io.saiden.economyhelper.market.frankfurter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Frankfurter — 유럽중앙은행 고시 환율. 이중화의 1순위.
 *
 * <p><b>인증이 없다.</b> 토스증권을 떠난 이유가 IP 허용목록이었으므로, 배포 환경에서
 * 그대로 도는 것이 이 출처를 고른 가장 큰 이유다.
 *
 * <pre>
 * GET /v1/latest?base=USD&amp;symbols=KRW
 *   → {"amount":1.0,"base":"USD","date":"2026-08-11","rates":{"KRW":1412.17}}
 * </pre>
 *
 * <p><b>시각이 아니라 날짜만 온다.</b> ECB가 영업일에 한 번 고시하기 때문이다 —
 * {@link FxSource#intraday()}가 거짓이라 메시지에도 "08-11 고시"로 나간다.
 * 주말이면 금요일 값이 그대로 오므로 별도의 영업일 후퇴 로직이 필요 없다
 * (수출입은행은 빈 배열을 줘서 직접 되짚어야 하는 것과 다르다).
 */
@Component
public class FrankfurterFxClient implements FxRateClient {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String USD = "USD";
    private static final String KRW = "KRW";

    private final RestClient restClient;

    public FrankfurterFxClient(RestClient.Builder builder,
                               @Value("${economy-helper.market.frankfurter.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public FxSource source() {
        return FxSource.FRANKFURTER;
    }

    @Override
    @Cacheable(cacheNames = "fx", unless = "#result == null")
    @CircuitBreaker(name = "fxFrankfurter")
    public FxRate usdToKrw() {
        LatestRates response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/latest")
                        .queryParam("base", USD)
                        .queryParam("symbols", KRW)
                        .build())
                .retrieve()
                .body(LatestRates.class);

        if (response == null || response.rates() == null || response.rates().get(KRW) == null) {
            throw new IllegalStateException("Frankfurter 응답에 KRW 환율이 없습니다");
        }

        // 고시일 00:00(KST)로 맞춘다 — 시각 정보가 없으므로 있는 척하지 않는다
        LocalDate date = response.date() == null ? LocalDate.now(SEOUL) : LocalDate.parse(response.date());
        return new FxRate(USD, KRW, response.rates().get(KRW),
                FxSource.FRANKFURTER, date.atStartOfDay(SEOUL).toInstant());
    }

    /** @param date {@code yyyy-MM-dd} — ECB 고시일이다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LatestRates(String base, String date, Map<String, BigDecimal> rates) {}
}
