package io.saiden.economyhelper.fx.adapter.out.kis;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.fx.application.port.out.FxRateClient;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.fx.domain.FxSource;
import io.saiden.economyhelper.infrastructure.kis.KisCall;
import io.saiden.economyhelper.infrastructure.kis.KisChartPrice;
import io.saiden.economyhelper.infrastructure.kis.KisHeaders;
import io.saiden.economyhelper.infrastructure.kis.KisThrottle;
import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

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

    private final KisCall kis;
    private final Clock clock;

    /** 환율도 주식과 <b>같은 문</b>({@link KisThrottle})을 {@link KisCall} 안에서 지난다 — 한도가 앱키 단위다. */
    public KisFxClient(KisCall kis, Clock clock) {
        this.kis = kis;
        this.clock = clock;
    }

    @Override
    public FxSource source() {
        return FxSource.KIS;
    }

    @Override
    @Cacheable(cacheNames = CacheNames.FX_KIS)
    @CircuitBreaker(name = "kisFx")
    public FxRate usdToKrw() {
        KisChartPrice response = kis.get(KisChartPrice.class, TR_ID, "환율",
                uriBuilder -> uriBuilder
                        .path(PATH)
                        .queryParam("FID_COND_MRKT_DIV_CODE", FX_MARKET)
                        .queryParam("FID_INPUT_ISCD", USD_KRW)
                        // 오늘만 물으면 휴일·이른 아침에 빈 배열이 온다. 일주일을 물어도
                        // output1의 현재가는 하나뿐이라 파싱은 그대로다
                        .queryParam("FID_INPUT_DATE_1", KisHeaders.daysAgo(clock, 7))
                        .queryParam("FID_INPUT_DATE_2", KisHeaders.today(clock))
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build());
        KisChartPrice.Quote quote = response.output();

        // ⚠️ null만 보면 안 된다 — 이 스키마는 심볼이 틀리면 0.00을 준다(Price 참고).
        //    환율 0은 화면의 모든 원화 환산을 오염시킨다
        if (quote == null) {
            throw new IllegalStateException("KIS 환율 응답에 현재가가 없습니다");
        }
        Price rate = Price.require(quote.price(), "KIS 환율");
        PercentChange change = PercentChange.ofNullable(quote.changePercent());
        // ⚠️ 주말·휴일·개장 전에는 현재가 자리에 마지막 영업일 종가가 온다 — 그 값에 읽은 시각을
        //    찍으면 낡은 값을 실시간으로 꾸미는 셈이다. 마지막 일봉의 날짜로 적고 실시간이 아니라고 밝힌다
        LocalDate latest = response.latestDate();
        if (KisHeaders.closedBefore(latest, clock)) {
            return new FxRate("USD", "KRW", rate, change, FxSource.KIS, KisHeaders.startOfDay(latest), false);
        }
        // 시각을 주지 않는다 — 계속 움직이는 값이라 '읽은 시각'이 곧 이 값의 시각이다
        return new FxRate("USD", "KRW", rate, change, FxSource.KIS, clock.instant(), true);
    }

}
