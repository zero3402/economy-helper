package io.saiden.economyhelper.market.kexim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 한국수출입은행 환율 — 이중화의 폴백.
 *
 * <p>실제로 호출해 확인한 함정이 넷이다.
 *
 * <ol>
 *   <li><b>구 도메인이 죽었다.</b> {@code www.koreaexim.go.kr}은 연결 자체가 안 된다
 *       (2026-04-30 지원 종료). {@code oapi.koreaexim.go.kr}을 써야 한다
 *   <li><b>에러도 HTTP 200으로 온다.</b> 키 없이 부르면 {@code 200} + {@code [{"result":3,...}]}이다.
 *       <b>상태코드가 아니라 {@code result}를 봐야 한다</b>
 *   <li><b>비영업일·11시 이전에는 빈 배열</b>이다. 실측에서 일요일이 정확히 그랬다.
 *       하루씩 물려 최대 {@link #MAX_LOOKBACK_DAYS}일까지 되짚는다
 *   <li><b>값이 콤마 낀 문자열</b>이다. {@code "1,415"}(소수점 없음)와 {@code "1,420.1"}
 *       두 형태가 다 나온다 — 콤마만 지우면 둘 다 파싱된다
 * </ol>
 *
 * <p><b>보안</b>: 이 API는 authkey를 <b>쿼리 파라미터로만</b> 받는다. {@code GeminiApi}처럼
 * 헤더로 옮길 수 없으므로, RestClient 예외를 그대로 흘리지 않고 <b>URL 없는 자체 예외</b>로
 * 바꿔 던진다 — 원래 예외 메시지에는 authkey가 박힌 URL이 들어간다.
 *
 * <p><b>하루 1,000회 제한</b>은 초 단위 리미터로 지킬 수 없다. 1시간 캐시가 실질 방어이고
 * (하루 최대 24회), 토스가 1순위라 폴백일 때만 불린다. 리미터는 아래 후퇴 루프가
 * 폭주하는 것만 막는다.
 */
@Component
public class KeximFxClient implements FxRateClient {

    private static final Logger log = LoggerFactory.getLogger(KeximFxClient.class);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 환율 조회 코드. AP02는 대출금리, AP03은 국제금리다. */
    private static final String EXCHANGE_RATE = "AP01";
    private static final String USD = "USD";

    /** 연휴가 길어도 이 안에서는 잡힌다. 호출 1회씩 태우므로 상한을 둔다. */
    private static final int MAX_LOOKBACK_DAYS = 7;

    private static final int RESULT_OK = 1;

    private final RestClient restClient;
    private final String authKey;
    private final Clock clock;

    public KeximFxClient(RestClient.Builder builder,
                         @Value("${economy-helper.market.kexim.base-url}") String baseUrl,
                         @Value("${economy-helper.market.kexim.api-key:}") String authKey,
                         Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.authKey = authKey;
        this.clock = clock;
    }

    @Override
    public FxSource source() {
        return FxSource.KEXIM;
    }

    @Override
    @Cacheable(cacheNames = "fx-kexim", unless = "#result == null")
    @RateLimiter(name = "kexim")
    @CircuitBreaker(name = "fxKexim")
    public FxRate usdToKrw() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);

        for (int back = 0; back < MAX_LOOKBACK_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            Rate[] rates = request(date);

            if (rates == null || rates.length == 0) {
                // 주말·공휴일이거나 아직 고시 전(영업일 11시경)이다. 하루 물린다
                continue;
            }
            verifyResultCode(rates[0], date);

            for (Rate rate : rates) {
                if (USD.equals(rate.currencyUnit()) && rate.dealBasisRate() != null) {
                    return new FxRate(USD, "KRW", parse(rate.dealBasisRate()),
                            FxSource.KEXIM, date.atStartOfDay(SEOUL).toInstant());
                }
            }
            throw new IllegalStateException("수출입은행 응답에 USD가 없습니다: " + date);
        }
        throw new IllegalStateException(
                "최근 " + MAX_LOOKBACK_DAYS + "일 안에 고시된 환율이 없습니다");
    }

    /**
     * 에러도 200으로 오므로 {@code result}를 직접 본다.
     *
     * <p>{@code 4}(일일 1,000회 초과)를 다른 오류와 구분해 로그에 남긴다 — 이건 캐시 설정을
     * 다시 봐야 한다는 신호이지 상대 장애가 아니다.
     */
    private static void verifyResultCode(Rate first, LocalDate date) {
        Integer result = first.result();
        if (result == null || result == RESULT_OK) {
            return;
        }
        String reason = switch (result) {
            case 2 -> "DATA 코드 오류";
            case 3 -> "인증 실패 — 키를 확인하세요";
            case 4 -> "일일 호출 한도(1,000회) 초과";
            default -> "알 수 없는 오류";
        };
        // searchdate와 result만 남긴다. URL을 찍으면 authkey가 로그에 남는다
        throw new IllegalStateException(
                "수출입은행 조회 실패 (searchdate=" + date.format(SEARCH_DATE)
                        + ", result=" + result + "): " + reason);
    }

    /**
     * 예외를 <b>다시 감싸서</b> 던진다.
     *
     * <p>{@code ResourceAccessException} 같은 RestClient 예외는 메시지에 요청 URL을 담는데,
     * 이 API는 authkey가 URL에 실려 있다 — 그대로 로그에 올라가면 키가 유출된다.
     */
    private Rate[] request(LocalDate date) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/site/program/financial/exchangeJSON")
                            .queryParam("authkey", authKey)
                            .queryParam("searchdate", date.format(SEARCH_DATE))
                            .queryParam("data", EXCHANGE_RATE)
                            .build())
                    .retrieve()
                    .body(Rate[].class);
        } catch (RuntimeException e) {
            log.warn("[kexim] {} 조회 실패: {}", date.format(SEARCH_DATE), e.getClass().getSimpleName());
            throw new IllegalStateException(
                    "수출입은행 호출 실패 (searchdate=" + date.format(SEARCH_DATE) + ")");
        }
    }

    /** {@code "1,415"}·{@code "1,420.1"} 둘 다 온다. 콤마만 지우면 된다. */
    static BigDecimal parse(String value) {
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("수출입은행 환율 형식을 해석할 수 없습니다: " + value);
        }
    }

    /**
     * 필요한 필드만 받는다.
     *
     * @param currencyUnit  {@code "USD"}. 엔화는 {@code "JPY(100)"}처럼 단위가 붙는다
     * @param dealBasisRate 매매기준율 — 우리가 쓰는 값
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rate(Integer result,
                @JsonProperty("cur_unit") String currencyUnit,
                @JsonProperty("cur_nm") String currencyName,
                @JsonProperty("deal_bas_r") String dealBasisRate) {}
}
