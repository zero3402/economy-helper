package io.saiden.economyhelper.market.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
import java.math.BigDecimal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 한국투자증권 원/달러 — <b>환율 이중화의 1순위</b>({@code FxService.ORDER}).
 *
 * <p>실제로 호출해 확인한 것들이다(2026-08-18, 모의 계정).
 *
 * <ul>
 *   <li><b>계좌번호가 필요 없다.</b> 환율을 계좌 종속 엔드포인트(예수금·증거금)의 부수 필드로만
 *       주는 줄 알았는데, 해외시세 쪽에 공개 경로가 있다 — 그래서 봇에서 쓸 수 있다
 *   <li>심볼은 <b>{@code FX@KRW}</b>다. KIS 자체 마스터 파일에 {@code XFX@KRW 대한민국 원/달러(KMB)}로
 *       실려 있다. {@code FX@KRWKFTC}(금융결제원)와 {@code FX@KRWJS}(원/엔)도 따로 있다
 *   <li><b>하루 중에 움직인다.</b> 실측에서 오늘 봉의 고가·저가가 {@code 1417.0}·{@code 1408.0}으로
 *       형성 중이었다 — 하루 한 번 고시가 아니다. 그래서 {@link FxSource#KIS}는 {@code intraday}다
 *   <li><b>200에 에러가 실려 온다.</b> 초당 한도를 넘기면 {@code rt_cd=1} +
 *       "초당 거래건수를 초과하였습니다"가 온다(실측). 상태코드가 아니라 {@code rt_cd}를 봐야 한다
 * </ul>
 *
 * <p><b>시각 필드를 주지 않는다.</b> 그래서 {@code asOf}는 <b>읽은 시각</b>이다. 하루 한 번
 * 고시하는 값에 분 단위를 붙이면 실제보다 신선해 보이지만, 계속 움직이는 값에는 "언제 받았는가"가
 * 곧 그 값의 시각이다. 캐시가 1분이라 표시 오차도 그 안이다.
 */
@Component
public class KisFxClient implements FxRateClient {

    private static final Logger log = LoggerFactory.getLogger(KisFxClient.class);

    private static final String PATH = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
    private static final String TR_ID = "FHKST03030100";

    /** 환율 구분. {@code N}은 해외지수, {@code I}는 국채, {@code S}는 금선물이다. */
    private static final String FX_MARKET = "X";
    private static final String USD_KRW = "FX@KRW";

    private final RestClient restClient;
    private final KisTokenStore tokens;
    private final KisHeaders headers;
    private final Clock clock;

    public KisFxClient(RestClient.Builder builder,
                       @Value("${economy-helper.market.kis.base-url}") String baseUrl,
                       KisTokenStore tokens, KisHeaders headers, Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
        this.headers = headers;
        this.clock = clock;
    }

    @Override
    public FxSource source() {
        return FxSource.KIS;
    }

    @Override
    @Cacheable(cacheNames = "fx-kis", unless = "#result == null")
    @RateLimiter(name = "kis")
    @CircuitBreaker(name = "kisFx")
    public FxRate usdToKrw() {
        Response response = request();
        Quote quote = response == null ? null : response.output();

        if (quote == null || quote.price() == null) {
            throw new IllegalStateException("KIS 환율 응답에 현재가가 없습니다");
        }
        // 시각을 주지 않는다 — 계속 움직이는 값이라 '읽은 시각'이 곧 이 값의 시각이다
        return new FxRate("USD", "KRW", quote.price(), quote.changePercent(),
                FxSource.KIS, clock.instant());
    }

    private Response request() {
        Response response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", FX_MARKET)
                            .queryParam("FID_INPUT_ISCD", USD_KRW)
                            // 오늘만 물으면 휴일·이른 아침에 빈 배열이 온다. 일주일을 물어도
                            // output1의 현재가는 하나뿐이라 파싱은 그대로다
                            .queryParam("FID_INPUT_DATE_1", KisHeaders.daysAgo(clock, 7))
                            .queryParam("FID_INPUT_DATE_2", KisHeaders.today(clock))
                            .queryParam("FID_PERIOD_DIV_CODE", "D")
                            .build())
                    .headers(headers.of(tokens.token(), TR_ID))
                    .retrieve()
                    .body(Response.class);
        } catch (RuntimeException e) {
            // 헤더에 토큰이 실려 있다 — 예외를 그대로 흘리면 로그에 남을 수 있다
            log.warn("[kis] 환율 조회 실패: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("KIS 환율 조회 실패: " + e.getClass().getSimpleName());
        }
        KisHeaders.verify(response == null ? null : response.resultCode(),
                response == null ? null : response.message(), "환율");
        return response;
    }

    /**
     * @param output 이름이 {@code output1}이다 — 해외시세 쪽은 {@code output}이 아니다.
     *               {@code output2}는 일자별 배열인데 우리는 현재가만 쓴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(@JsonProperty("rt_cd") String resultCode,
                    @JsonProperty("msg1") String message,
                    @JsonProperty("output1") Quote output) {}

    /**
     * @param price         {@code ovrs_nmix_prpr} — 현재가. {@code "1412.5000"}처럼 온다
     * @param changePercent {@code prdy_ctrt} — 전일 대비율(%). 이미 %라서 그대로 쓴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(@JsonProperty("ovrs_nmix_prpr") BigDecimal price,
                 @JsonProperty("prdy_ctrt") BigDecimal changePercent) {}
}
