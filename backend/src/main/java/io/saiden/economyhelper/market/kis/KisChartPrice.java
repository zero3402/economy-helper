package io.saiden.economyhelper.market.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * 해외 일자별 차트({@code inquire-daily-chartprice}, {@code FHKST03030100}) 응답.
 *
 * <p><b>환율과 미국 지수가 이 한 엔드포인트를 함께 쓴다.</b> 구분은 시장 코드뿐이다 —
 * 환율이 {@code X}({@code FX@KRW}), 해외지수가 {@code N}({@code COMP}·{@code SPX}). 실측에서
 * 응답 필드까지 글자 그대로 같았다. 그래서 스키마도 하나다: 두 벌을 두면 한쪽만 고쳐지는
 * 날이 온다.
 *
 * @param output 이름이 {@code output1}이다 — 해외시세 쪽은 {@code output}이 아니다.
 *               {@code output2}는 일자별 배열이다 — 환율 차트는 그것을 쓰지 않고
 *               Frankfurter 시계열로 그린다(키도 한도도 없어 호출 하나가 값을 안 한다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record KisChartPrice(@JsonProperty("rt_cd") String resultCode,
                     @JsonProperty("msg1") String message,
                     @JsonProperty("output1") Quote output) implements KisResponse {

    /**
     * @param price         {@code ovrs_nmix_prpr} — 현재가. {@code "1412.5000"}처럼 온다
     * @param changePercent {@code prdy_ctrt} — 전일 대비율(%). 이미 %라서 그대로 쓴다.
     *                      <b>국내 지수만 이 이름이 아니다</b>({@code bstp_nmix_prdy_ctrt})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(@JsonProperty("ovrs_nmix_prpr") BigDecimal price,
                 @JsonProperty("prdy_ctrt") BigDecimal changePercent) {}
}
