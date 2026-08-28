package io.saiden.economyhelper.market.kexim;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.saiden.economyhelper.support.Permit;
import io.saiden.economyhelper.config.CacheNames;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxRateClient;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.PercentChange;
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
 * 한국수출입은행 환율 — <b>이중화의 3순위</b>({@code FxSource}의 선언 순이 곧 시도 순이다).
 *
 * <p>마지막에 두는 이유는 신선도다 — 한국의 공식 고시라 값은 믿을 수 있지만 하루 한 번(11시경)이고,
 * 앞의 둘(한국투자증권·유럽중앙은행)이 더 최신을 준다. 최후 보루로 남긴다.
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
 * <p><b>하루 1,000회 제한</b>은 초 단위 리미터로 지킬 수 없다. 1시간 캐시가 실질 방어이고,
 * 리미터는 아래 되짚기 루프가 폭주하는 것만 막는다.
 *
 * <p>⚠️ <b>그 말이 참이려면 리미터가 HTTP 호출마다 걸려야 한다.</b> 예전에는 애너테이션이
 * {@link #usdToKrw()}에만 있어 <b>진입 한 번에 퍼밋 하나</b>였다. 그 안에서 {@link #findAt}이
 * 최대 {@link #MAX_LOOKBACK_DAYS}회를 돌고 {@link #changeOf}가 그 루프를 <b>한 번 더</b> 도므로,
 * 캐시가 빈 조회 한 번이 최대 14회 HTTP였는데 리미터는 그걸 1회로 셌다. 이제 실제 호출 자리
 * ({@link #request})에서 퍼밋을 얻는다 — 애너테이션은 프록시가 필요해 private 메서드에 못 걸리므로
 * 레지스트리에서 직접 꺼내 쓴다.
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
    /** 되짚기 루프가 실제로 태우는 호출을 세는 자리. {@code null}이면 세지 않는다(테스트). */
    private final RateLimiter limiter;

    public KeximFxClient(RestClient.Builder builder,
                         @Value("${economy-helper.market.kexim.base-url}") String baseUrl,
                         @Value("${economy-helper.market.kexim.api-key:}") String authKey,
                         Clock clock,
                         RateLimiterRegistry limiters) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.authKey = authKey;
        this.clock = clock;
        this.limiter = Permit.of(limiters, "kexim");
    }

    @Override
    public FxSource source() {
        return FxSource.KEXIM;
    }

    @Override
    @Cacheable(cacheNames = CacheNames.FX_KEXIM)
    @CircuitBreaker(name = "fxKexim")
    public FxRate usdToKrw() {
        if (authKey.isBlank()) {
            // 부르기 전에 막는다 — 빈 키로 호출하면 하루 1,000회 한도만 축낸다
            throw new IllegalStateException("수출입은행 API 키가 없습니다");
        }
        Quoted current = findAt(LocalDate.ofInstant(clock.instant(), SEOUL));
        return new FxRate(USD, "KRW", current.rate(), changeOf(current),
                FxSource.KEXIM, current.date().atStartOfDay(SEOUL).toInstant());
    }

    /**
     * 전 고시 대비 등락률(%).
     *
     * <p><b>수출입은행은 등락률도 전일값도 주지 않는다</b> — 응답 열한 필드가 전부 당일
     * 고시값이다. 그래서 전 영업일을 한 번 더 부른다. 되짚는 비용은 {@code fx-kexim} 캐시
     * (1시간)가 흡수한다 — 캐시가 비었을 때만 도는 길이고, 고시값은 하루에 바뀌지 않는다.
     *
     * <p><b>못 구하면 {@code null}이다.</b> 연휴가 상한을 넘겼거나 그 호출만 실패한 경우인데,
     * 등락률 하나 때문에 환율 자체를 막는 것은 과하다 — 원화 환산이 실패해도 시세는
     * 내보내는 것과 같은 판단이다.
     *
     * <p><b>다른 기관 값과 비교하지 않는다.</b> 유럽중앙은행 전일값을 끌어다 쓰면 기관 간
     * 고시 차이가 등락률로 둔갑한다.
     */
    private BigDecimal changeOf(Quoted current) {
        try {
            return PercentChange.between(current.rate(), findAt(current.date().minusDays(1)).rate());
        } catch (RuntimeException e) {
            log.info("[kexim] 전 고시를 찾지 못해 등락률을 비웁니다: {}", e.getMessage());
            return null;
        }
    }

    /**
     * {@code from}부터 <b>거슬러 올라가며</b> 가장 가까운 고시를 찾는다.
     *
     * <p>주말·공휴일에는 빈 배열이 오고, 영업일이어도 고시 전(11시경)이면 마찬가지다.
     * 그래서 날짜를 하루씩 물려 가며 찾는다 — 유럽중앙은행이 금요일 값을 그대로 주는 것과
     * 다른 점이다.
     */
    private Quoted findAt(LocalDate from) {
        for (int back = 0; back < MAX_LOOKBACK_DAYS; back++) {
            LocalDate date = from.minusDays(back);
            Rate[] rates = request(date);

            if (rates == null || rates.length == 0) {
                continue;
            }
            verifyResultCode(rates[0], date);

            for (Rate rate : rates) {
                if (USD.equals(rate.currencyUnit()) && rate.dealBasisRate() != null) {
                    return new Quoted(date, parse(rate.dealBasisRate()));
                }
            }
            throw new IllegalStateException("수출입은행 응답에 USD가 없습니다: " + date);
        }
        throw new IllegalStateException(
                from + "부터 " + MAX_LOOKBACK_DAYS + "일 안에 고시된 환율이 없습니다");
    }

    /** 고시 한 건 — 값과 그 값이 고시된 날. */
    private record Quoted(LocalDate date, BigDecimal rate) {}

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
        // 퍼밋은 호출 자리에서 — 캐시가 빈 조회 한 번이 최대 14회 HTTP인데 하루 한도가 1,000회다.
        // boolean을 왜 확인해야 하는지, 왜 DataGoRequest와 한 곳을 쓰는지는 Permit에 적혀 있다
        Permit.acquire(limiter);
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
                @JsonProperty("deal_bas_r") String dealBasisRate) {}
}
