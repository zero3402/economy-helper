package io.saiden.economyhelper.infrastructure.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 해외 일자별 차트({@code inquire-daily-chartprice}, {@code FHKST03030100}) 응답.
 *
 * <p><b>환율과 미국 지수가 이 한 엔드포인트를 함께 쓴다.</b> 구분은 시장 코드뿐이다 —
 * 환율이 {@code X}({@code FX@KRW}), 해외지수가 {@code N}({@code COMP}·{@code SPX}). 실측에서
 * 응답 필드까지 글자 그대로 같았다. 그래서 스키마도 하나다: 두 벌을 두면 한쪽만 고쳐지는
 * 날이 온다.
 *
 * @param output 이름이 {@code output1}이다 — 해외시세 쪽은 {@code output}이 아니다.
 * @param bars   {@code output2} — 일자별 배열. 차트는 그리지 않는다(환율 차트는 Frankfurter 시계열이
 *               그린다). <b>날짜만 읽는다</b> — 현재가가 <b>어느 날의 값인지</b>는 여기서만 안다({@link #latestDate})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisChartPrice(@JsonProperty("rt_cd") String resultCode,
                     @JsonProperty("msg1") String message,
                     @JsonProperty("msg_cd") String messageCode,
                     @JsonProperty("output1") Quote output,
                     @JsonProperty("output2") List<Bar> bars) implements KisResponse {

    /**
     * 가장 최근 일봉의 날짜 — <b>현재가가 그날의 값이다.</b> 주말·휴일에는 마지막 영업일이 온다.
     *
     * @return 읽을 수 있는 날짜가 하나도 없으면 {@code null} — 「모른다」다
     */
    public LocalDate latestDate() {
        if (bars == null) {
            return null;
        }
        return bars.stream().filter(Objects::nonNull)
                .map(bar -> KisHeaders.dateOf(bar.on()))
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
    }

    /** @param on {@code stck_bsop_date} — {@code uuuuMMdd} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bar(@JsonProperty("stck_bsop_date") String on) {}

    /**
     * @param price         {@code ovrs_nmix_prpr} — 현재가. {@code "1412.5000"}처럼 온다
     * @param changePercent {@code prdy_ctrt} — 전일 대비율(%). 이미 %라서 그대로 쓴다.
     *                      <b>국내 지수만 이 이름이 아니다</b>({@code bstp_nmix_prdy_ctrt})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(@JsonProperty("ovrs_nmix_prpr") BigDecimal price,
                 @JsonProperty("prdy_ctrt") BigDecimal changePercent) {}
}
