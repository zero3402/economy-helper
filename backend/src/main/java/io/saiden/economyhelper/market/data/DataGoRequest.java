package io.saiden.economyhelper.market.data;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 공공데이터포털에 <b>묻는 방법</b> — 시세와 지수 두 클라이언트가 나눠 쓴다.
 *
 * <p>{@code market.weather.openmeteo.OpenMeteoRequest}와 같은 자리다. 그쪽 javadoc이 밝힌
 * 이유가 여기 그대로 통한다 — <b>「둘 다에 반드시 있어야 하는 것이 하나에서 빠지면 그 출처가
 * 답한 날만 조용히 틀린다」</b>. 그쪽에서는 {@code timezone=auto}였고 여기서는
 * <b>서비스키를 다시 인코딩하지 않는 것</b>이다: 발급된 키는 이미 URL 인코딩된 형태(`%` 포함)라
 * {@code UriBuilder.queryParam()}처럼 한 번 더 인코딩하면 <b>403 「등록되지 않은 서비스키」</b>가
 * 난다. 그래서 URI를 문자열로 직접 조립한다.
 *
 * <p><b>합치는 것은 질문하는 방법과 되짚기뿐이다.</b> 두 클라이언트는 각자 {@code @Cacheable}
 * (캐시 이름 하나에 타입 하나 — 섞었다가 캐시 히트에서만 터지는 사고가 있었다),
 * {@code @CircuitBreaker}, 응답 레코드, 골라내는 규칙을 그대로 쥔다.
 *
 * <p>⚠️ <b>리미터는 HTTP 호출 자리에 있어야 한다.</b> 애너테이션을 바깥 {@code @Cacheable}
 * 메서드에 걸면 <b>진입 한 번에 퍼밋 하나</b>인데, 되짚기 루프가 최대 {@link #MAX_LOOKBACK_DAYS}회
 * HTTP를 부른다 — {@code dataGo}가 초당 10건이므로 캐시가 빈 조회 하나가 산수로 이미 한도를
 * 채우는데 리미터는 그걸 1회로 셌다. 그래서 퍼밋을 {@link #fetch}에서 얻는다.
 */
final class DataGoRequest {

    private static final Logger log = LoggerFactory.getLogger(DataGoRequest.class);

    /** 연휴가 길어도 이 안에서 잡힌다. 한 번에 하루씩 호출을 태우므로 상한을 둔다. */
    static final int MAX_LOOKBACK_DAYS = 10;

    /** 한 번에 받아올 최대 건수. {@code 삼성}이 26건·{@code 코스피}가 32건이라 100이면 넉넉하다. */
    private static final int PAGE_SIZE = 100;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DataGoRequest() {
    }

    /**
     * 되짚기 루프가 태우는 호출을 세는 리미터.
     *
     * @return {@code registry}가 {@code null}이면 {@code null} — 테스트가 그렇게 만든다
     */
    static RateLimiter limiterOf(RateLimiterRegistry registry) {
        return registry == null ? null : registry.rateLimiter("dataGo");
    }

    /**
     * <b>가장 최근 영업일을 되짚어 찾는다.</b>
     *
     * <p>오늘은 항상 0건이므로 <b>어제부터</b> 시작한다(오늘 날짜로 조회하면
     * {@code totalCount=0}이다). 주말·공휴일이면 계속 0건이라 하루씩 물린다.
     *
     * <p>⚠️ <b>「응답이 왔다」와 「답이 있다」는 다르다.</b> 그래서 {@code usable}을 따로 받는다 —
     * 지수 쪽은 이름이 전부 비어 온 날에 골라내지 못하는데, 그때 그 결과를 그대로 돌려주면
     * <b>남은 되짚기 날들을 버린다</b>(하루치 응답이 망가진 것과 「그런 지수가 없다」가 화면에서
     * 구분되지 않았다). 골라내지 못하면 어제로 넘어간다.
     *
     * @param attempt 하루를 조회해 결과를 만든다. 실패하면 던진다 — <b>여기서 삼키지 않는다</b>:
     *                상대가 죽었으면 나머지 아흐레도 실패하고 초당 한도만 태운다
     * @param usable  그 결과가 답인가. 아니면 다음 날로 넘어간다
     * @param none    끝까지 못 찾았을 때 돌려줄 것
     */
    static <R> R lookBack(Clock clock, String tag, String what,
                          Function<LocalDate, R> attempt, Predicate<R> usable, R none) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);
        for (int back = 1; back <= MAX_LOOKBACK_DAYS; back++) {
            R found = attempt.apply(today.minusDays(back));
            if (usable.test(found)) {
                return found;
            }
        }
        log.info("[{}] 최근 {}일 안에 '{}'가 없습니다", tag, MAX_LOOKBACK_DAYS, what);
        return none;
    }

    /**
     * 하루치를 한 번 조회한다 — <b>퍼밋을 얻고, 키를 지운 예외로 바꿔 던진다.</b>
     *
     * <p>예외를 그대로 흘리지 않는 이유는 {@code serviceKey}가 URL에 박혀 있기 때문이다
     * ({@code KeximFxClient}가 세운 규칙이다). 이유만 클래스 이름으로 남긴다.
     *
     * @return 역직렬화한 응답. 본문이 없으면 {@code null} — 「없음」은 호출부가 읽는다
     */
    static <T> T fetch(RestClient restClient, RateLimiter limiter, String uri,
                       Class<T> type, LocalDate date, String tag) {
        // ⚠️ **acquirePermission()은 던지지 않는다 — boolean을 준다.**
        //    resilience4j 2.4.0의 시그니처가 `boolean acquirePermission()`이라(javap로 확인)
        //    타임아웃 안에 퍼밋을 못 얻으면 조용히 false다. 반환값을 버리고 있었으므로
        //    **리미터가 가장 필요한 포화 상황에 그대로 HTTP가 나갔다** — 스로틀이 아니라
        //    장식이었다. 거절을 RequestNotPermitted로 올려야 dataGo 브레이커가 그것을
        //    (baseConfig의 ignoreExceptions로) 상대 장애가 아닌 우리 스로틀로 읽는다
        if (limiter != null && !limiter.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(limiter);
        }
        try {
            return restClient.get().uri(URI.create(uri)).retrieve().body(type);
        } catch (RuntimeException e) {
            log.warn("[{}] {} 조회 실패: {}", tag, date.format(BAS_DT), e.getClass().getSimpleName());
            throw new IllegalStateException("공공데이터포털 조회 실패 (basDt=" + date.format(BAS_DT) + ")");
        }
    }

    /**
     * 조회 URI — <b>서비스키만 그대로, 나머지는 우리가 인코딩해서.</b>
     *
     * <p>이 한 줄이 두 클라이언트에 각자 있던 것이 이 클래스가 생긴 이유다. 한쪽에서만
     * {@code queryParam()}으로 바꾸는 순간 그 출처가 403으로 조용히 죽는다.
     */
    static String uri(String baseUrl, String path, String serviceKey, LocalDate date,
                      String filterParam, String filterValue) {
        return baseUrl + path
                + "?serviceKey=" + serviceKey
                + "&resultType=json"
                + "&numOfRows=" + PAGE_SIZE
                + "&pageNo=1"
                + "&basDt=" + date.format(BAS_DT)
                + "&" + filterParam + "=" + URLEncoder.encode(filterValue, StandardCharsets.UTF_8);
    }
}
