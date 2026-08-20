package io.saiden.economyhelper.market.frankfurter;

import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.PercentChange;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Frankfurter — 유럽중앙은행 고시 환율. <b>이중화의 폴백</b>({@code FxService.ORDER}).
 *
 * <p><b>인증이 없다.</b> 키도 IP 허용목록도 없어 배포 환경에서
 * 그대로 도는 것이 이 출처를 고른 가장 큰 이유다.
 *
 * <pre>
 * GET /v1/2026-08-04..?base=USD&amp;symbols=KRW
 *   → {"base":"USD","start_date":"2026-08-04","end_date":"2026-08-13",
 *      "rates":{"2026-08-12":{"KRW":1417.13},"2026-08-13":{"KRW":1420.29}, ...}}
 * </pre>
 *
 * <p><b>{@code /latest}가 아니라 시계열을 부른다.</b> 등락률을 내려면 전 고시값이 필요한데
 * {@code /latest}는 당일 하나뿐이라 두 번 불러야 한다. 시계열은 한 번에 여러 날을 주므로
 * <b>호출 수가 늘지 않는다</b> — 수출입은행이 전 영업일을 되짚느라 한 번 더 부르는 것과
 * 갈리는 지점이다.
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

    /**
     * 얼마나 거슬러 받을지. <b>두 영업일만 있으면 되지만 연휴를 넉넉히 넘긴다</b> —
     * 응답이 몇 KB라 범위를 넓혀도 비용이 사실상 같고, 좁혔다가 설 연휴에 한 건만
     * 돌아오면 그때만 등락률이 조용히 빈다.
     */
    private static final int LOOKBACK_DAYS = 10;

    private final RestClient restClient;
    private final Clock clock;

    public FrankfurterFxClient(RestClient.Builder builder,
                               @Value("${economy-helper.market.frankfurter.base-url}") String baseUrl,
                               Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.clock = clock;
    }

    @Override
    public FxSource source() {
        return FxSource.FRANKFURTER;
    }

    @Override
    @Cacheable(cacheNames = CacheNames.FX, unless = "#result == null")
    @Retry(name = "fxFrankfurter")
    @CircuitBreaker(name = "fxFrankfurter")
    public FxRate usdToKrw() {
        TimeSeries response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/{start}..")
                        .queryParam("base", USD)
                        .queryParam("symbols", KRW)
                        .build(LocalDate.ofInstant(clock.instant(), SEOUL).minusDays(LOOKBACK_DAYS)))
                .retrieve()
                .body(TimeSeries.class);

        if (response == null || response.rates() == null || response.rates().isEmpty()) {
            throw new IllegalStateException("Frankfurter 응답에 환율이 없습니다");
        }

        // 날짜 문자열이 yyyy-MM-dd라 사전순이 곧 시간순이다
        List<String> dates = response.rates().keySet().stream().sorted().toList();
        LocalDate latest = LocalDate.parse(dates.get(dates.size() - 1));
        BigDecimal rate = rateOn(response, dates.get(dates.size() - 1));
        if (rate == null) {
            throw new IllegalStateException("Frankfurter 응답에 KRW 환율이 없습니다");
        }

        // 고시일 00:00(KST)로 맞춘다 — 시각 정보가 없으므로 있는 척하지 않는다
        return new FxRate(USD, KRW, rate, changeOf(response, dates),
                FxSource.FRANKFURTER, latest.atStartOfDay(SEOUL).toInstant());
    }

    /**
     * 전 고시 대비 등락률(%).
     *
     * <p>고시가 하루치뿐이면({@code null}) 표시에서 빠진다 — 없는 값을 0%로 찍으면
     * 보합이라고 <b>거짓말</b>을 하게 된다.
     */
    private static BigDecimal changeOf(TimeSeries response, List<String> dates) {
        if (dates.size() < 2) {
            return null;
        }
        return PercentChange.between(rateOn(response, dates.get(dates.size() - 1)),
                rateOn(response, dates.get(dates.size() - 2)));
    }

    private static BigDecimal rateOn(TimeSeries response, String date) {
        Map<String, BigDecimal> onDate = response.rates().get(date);
        return onDate == null ? null : onDate.get(KRW);
    }

    /**
     * @param rates 고시일({@code yyyy-MM-dd}) → 통화별 환율. 비영업일은 <b>키 자체가 없다</b>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeSeries(Map<String, Map<String, BigDecimal>> rates) {}
}
