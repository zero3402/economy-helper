package io.saiden.economyhelper.market.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.DomesticOutlookClient;
import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.StockSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 국내 종목의 목표주가·투자의견 — KIS {@code invest-opinion}.
 *
 * <p><b>모의 계정에서도 200이다</b>(실측 2026-08-20). 문서가 「실전 전용 엔드포인트가 막힌다」고
 * 적어 둔 목록에 이것을 넣어 짐작할 뻔했는데, <b>막힌 것과 안 해 본 것은 다르다.</b>
 *
 * <p><b>응답은 컨센서스가 아니라 발표 건이다</b>(실측 2026-08-21, 삼성전자 7~8월 12행):
 *
 * <pre>
 * {"stck_bsop_date":"20260810","mbcr_name":"키움",
 *  "invt_opnn":"BUY","invt_opnn_cls_code":"2","hts_goal_prc":"350000", …}
 * </pre>
 *
 * 그래서 {@link InvestOpinions}가 증권사별 최신 한 건만 남겨 접는다 — 자주 내는 증권사가
 * 여러 표를 갖지 않게 하려는 것이다.
 *
 * <p>⚠️ <b>등급은 {@code invt_opnn_cls_code}가 아니라 {@code invt_opnn}에서 읽는다.</b>
 * 코드는 등급이 아니다(실측: 코드 하나에 {@code Strong BUY}·{@code Hold}·{@code Outperform}·
 * {@code Buy}가 섞여 있었다). 그리고 <b>같은 응답 안에서 표기가 갈린다</b> — 위 실측 12행에
 * {@code "BUY"}(키움·삼성)와 {@code "매수"}(한국투자)가 함께 왔다. 그래서 정규화가
 * {@code StockOutlook.Rating.ofLabel}에 있고, 그것이 영문과 한글을 둘 다 받는다.
 *
 * <p>⚠️ <b>실패를 삼키지 않는다 — 던진다.</b> {@link DomesticOutlookClient}가 「빈 값으로
 * 실패한다」고 적혀 있었지만 그대로 하면 아래 {@code @CircuitBreaker}가 <b>정상 반환을 보고
 * 성공을 센다</b>. 실패율이 영원히 0이라 브레이커가 열리지 않고, KIS가 죽어 있는 동안 조회마다
 * 간격 1초를 헛되이 지불한다 — {@code HackerNewsApi}가 실제로 그 상태였고 그 브레이커의
 * 설정값이 전부 죽은 값이었다. 그래서 <b>삼키는 일은 {@code StockService}가 한다.</b>
 * 화면에서 「의견이 없는 종목」과 「조회 실패」가 같은 결과(그 줄이 없음)라는 것은 여전히
 * 맞고, 그 판단을 브레이커가 실패를 본 <b>뒤에</b> 하는 것뿐이다.
 *
 * <p>빈 {@link Optional}은 <b>값</b>이다 — 그 종목에 의견을 낸 증권사가 없다는 뜻이고,
 * 그건 실패가 아니다.
 */
@Component
public class KisDomesticOutlookClient implements DomesticOutlookClient {

    private static final Logger log = LoggerFactory.getLogger(KisDomesticOutlookClient.class);

    private static final String PATH = "/uapi/domestic-stock/v1/quotations/invest-opinion";
    private static final String TR_ID = "FHKST663300C0";

    /** 화면 구분 코드. 이 엔드포인트가 요구하는 고정값이다. */
    private static final String SCREEN_DIV = "16633";

    /**
     * 며칠치를 물을지.
     *
     * <p>증권사는 분기 실적 즈음에 몰아서 내므로 한 달로는 의견이 없는 종목이 흔하다.
     * 실측(2026-08-21)으로 삼성전자가 7~8월 두 달에 12행이었다. 넉넉히 잡아도 응답이
     * 수십 행이라 비용이 같고, 접는 쪽에서 증권사별 최신 하나만 남기므로 오래된 것이
     * 화면에 새지 않는다.
     */
    private static final int LOOKBACK_DAYS = 180;

    private final RestClient restClient;
    private final KisTokenStore tokens;
    private final KisHeaders headers;
    private final KisThrottle throttle;
    private final Clock clock;

    public KisDomesticOutlookClient(RestClient.Builder builder,
                                    @Value("${economy-helper.market.kis.base-url}") String baseUrl,
                                    KisTokenStore tokens, KisHeaders headers,
                                    KisThrottle throttle, Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
        this.headers = headers;
        this.throttle = throttle;
        this.clock = clock;
    }

    /**
     * @param code 6자리 종목코드
     * @return 접은 전망. 의견을 낸 증권사가 없으면 빈 값 — 그건 값이고 실패가 아니다
     * @throws RuntimeException 조회가 실패하면 던진다. 삼키는 것은 {@code StockService}이고,
     *                          그래야 브레이커가 실패를 먼저 센다
     */
    @Override
    @Cacheable(cacheNames = CacheNames.KIS_OUTLOOK, key = "#code")
    @CircuitBreaker(name = "kisStock")
    public Optional<StockOutlook> outlook(String code) {
        // 호출 하나에 간격 하나 — KIS의 제약은 "초당 몇 건"이 아니라 "호출 사이 얼마"다
        throttle.pace();
        Opinions response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_COND_SCR_DIV_CODE", SCREEN_DIV)
                            .queryParam("FID_INPUT_ISCD", code)
                            .queryParam("FID_INPUT_DATE_1", KisHeaders.daysAgo(clock, LOOKBACK_DAYS))
                            .queryParam("FID_INPUT_DATE_2", KisHeaders.today(clock))
                            .build())
                    .headers(headers.of(tokens.token(), TR_ID))
                    .retrieve()
                    .body(Opinions.class);
        } catch (RuntimeException e) {
            // 헤더에 접근토큰이 실려 있어 예외를 그대로 흘리면 유출된다 — 이유만 꺼낸다
            String reason = KisHeaders.reasonOf(e);
            log.warn("[kis] {} 투자의견 조회 실패: {}", code, reason);
            if (KisHeaders.isInvalidToken(e)) {
                tokens.invalidate();
            }
            throw new IllegalStateException("KIS 투자의견 조회 실패 (" + code + "): " + reason);
        }
        if (response == null) {
            throw new IllegalStateException("KIS 투자의견 응답이 비어 있습니다 (" + code + ")");
        }
        // ⚠️ 에러가 HTTP 200 본문에 실려 온다 — rt_cd를 봐야 한다
        KisHeaders.verify(response.resultCode(), response.message(), code + " 투자의견");

        return InvestOpinions.of(rowsOf(response))
                .map(consensus -> new StockOutlook(
                        // 실적발표일은 이 엔드포인트가 주지 않는다. 국내에 무료 출처가 없어
                        // null로 남고, 화면은 그 줄을 안 적는다
                        null,
                        consensus.targetPrice(), consensus.rating(), consensus.analystCount(),
                        StockSource.KIS, clock.instant()));
    }

    private static List<InvestOpinions.Opinion> rowsOf(Opinions response) {
        if (response.output() == null) {
            return List.of();
        }
        return response.output().stream()
                .filter(java.util.Objects::nonNull)
                .map(row -> new InvestOpinions.Opinion(
                        row.broker(), row.date(), row.opinion(), row.targetPrice()))
                .toList();
    }

    /**
     * ⚠️ 배열 이름이 {@code output}이다 — 시세 경로들의 {@code output1}·{@code output2}가 아니다
     * (실측 2026-08-21: 최상위 키가 {@code rt_cd}·{@code msg_cd}·{@code msg1}·{@code output}).
     * 이름을 잘못 적으면 {@code @JsonIgnoreProperties} 때문에 <b>오류 없이 빈 목록</b>이 되어
     * 「의견을 낸 증권사가 없다」와 구분되지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Opinions(@JsonProperty("rt_cd") String resultCode,
                    @JsonProperty("msg1") String message,
                    List<Row> output) implements KisResponse {
    }

    /**
     * 발표 한 건.
     *
     * @param date        {@code stck_bsop_date} — 발표일 {@code yyyyMMdd}
     * @param broker      {@code mbcr_name} — 증권사. 같은 곳의 옛 발표를 걷어내는 키다
     * @param opinion     {@code invt_opnn} — <b>증권사가 쓴 글자 그대로.</b> 등급 코드가 아니다
     * @param targetPrice {@code hts_goal_prc} — 목표가. {@code 0}일 수 있고 그건 값이 아니다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(@JsonProperty("stck_bsop_date") String date,
               @JsonProperty("mbcr_name") String broker,
               @JsonProperty("invt_opnn") String opinion,
               @JsonProperty("hts_goal_prc") BigDecimal targetPrice) {
    }
}
