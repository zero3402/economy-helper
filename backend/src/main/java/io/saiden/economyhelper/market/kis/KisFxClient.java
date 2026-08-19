package io.saiden.economyhelper.market.kis;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.Price;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
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

    /** <b>미국 지수와 같은 경로다</b>({@code KisStockApi}). 구분은 시장 코드뿐이라 스키마도 함께 쓴다. */
    private static final String PATH = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
    private static final String TR_ID = "FHKST03030100";

    /**
     * 시장 분류 코드 — 환율은 {@code X}다.
     *
     * <p>같은 경로를 쓰는 형제들은 다른 값을 쓴다: {@code N} 해외지수, {@code I} 국채,
     * {@code S} 금선물. 스키마가 같아도 이 한 글자가 무엇을 조회하는지를 가른다.
     */
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
        KisChartPrice.Quote quote = request().output();

        // ⚠️ null만 보면 안 된다. 이 응답 스키마(KisChartPrice)는 심볼이 틀릴 때 에러가 아니라
        //    0.00을 주고, 그걸 값으로 받으면 환율 0이 화면의 모든 원화 환산을 오염시킨다 —
        //    KisStockApi가 지수에서 실측으로 겪은 그 함정이고, 같은 스키마라 여기도 걸린다
        if (quote == null) {
            throw new IllegalStateException("KIS 환율 응답에 현재가가 없습니다");
        }
        Price.require(quote.price(), "KIS 환율");
        // 시각을 주지 않는다 — 계속 움직이는 값이라 '읽은 시각'이 곧 이 값의 시각이다
        return new FxRate("USD", "KRW", quote.price(), quote.changePercent(),
                FxSource.KIS, clock.instant());
    }

    private KisChartPrice request() {
        KisChartPrice response;
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
                    .body(KisChartPrice.class);
        } catch (RuntimeException e) {
            // 헤더에 토큰이 실려 있다 — 예외를 그대로 흘리면 로그에 남을 수 있다
            log.warn("[kis] 환율 조회 실패: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("KIS 환율 조회 실패: " + e.getClass().getSimpleName());
        }
        KisHeaders.verify(response == null ? null : response.resultCode(),
                response == null ? null : response.message(), "환율");
        return response;
    }
}
