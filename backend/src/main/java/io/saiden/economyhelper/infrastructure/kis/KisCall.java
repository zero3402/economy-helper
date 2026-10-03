package io.saiden.economyhelper.infrastructure.kis;

import java.net.URI;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * <b>KIS 호출 한 번</b> — 간격을 지키고, 토큰을 가린 이유만 남기고, 200 본문의 {@code rt_cd}까지 본다.
 *
 * <p>경로마다 응답 타입만 다르고 <b>헤더·에러 처리·비밀 취급이 같아서</b> 클라이언트
 * 셋({@code KisStockApi}·{@code KisFxClient}·{@code KisDomesticOutlookClient})이 이것을 함께 쓴다.
 * <b>갈라지면 안 되는 이유가 둘이고 둘 다 안전에 걸린다.</b>
 *
 * <ol>
 *   <li><b>예외를 그대로 흘리면 접근토큰이 샌다.</b> 헤더에 실려 있어 로그·모니터링에 그대로
 *       남는다. 그래서 {@link KisHeaders#reasonOf}로 <b>이유만</b> 꺼낸다
 *       ({@code FmpApi}·{@code KeximFxClient}가 URL에 실린 키를 가리는 것과 같다).
 *   <li><b>무효 토큰을 알아차린 자리에서 버려야 스스로 낫는다.</b> 앱키당 활성 토큰이 하나라
 *       어느 클라이언트가 먼저 알아차리든 버려야 나머지도 함께 낫는다. 안 버리면 기록된
 *       만료까지 <b>최대 24시간</b> 모든 KIS 호출이 죽는다 — KIS는 무효 토큰에 401이 아니라
 *       <b>500</b>을 주고 이유가 그 500 본문에만 있다.
 * </ol>
 *
 * <p>⚠️ <b>브레이커는 합쳐지지 않는다 — 합치면 docs/design.md 4.4를 어긴다.</b>
 * {@code @CircuitBreaker}는 각 클라이언트의 <b>공개 SPI 메서드</b>에 붙어 있고 이 클래스에는
 * 없다. {@code kisStock}·{@code kisFx}·{@code kisOutlook}이 그대로 갈려 있어야 전망(보충)의
 * 실패가 시세(답 자체)를 끊지 않는다. 여기서 공유하는 것은 <b>호출 한 번의 기계</b>뿐이다.
 *
 * <p>⚠️ <b>KIS 실패 경고는 전부 이 로거({@code [kis]})에서 난다</b> — 클라이언트 이름으로 로그를
 * 거르면 못 찾는다. 어느 조회인지는 {@code what}({@code "국내 종목 005930"}·{@code "환율"}·
 * {@code "005930 배당일정"})이 클래스 이름보다 좁게 가리킨다.
 *
 * <p>공유 단위는 <b>벤더</b>다({@link KisHeaders} 참고).
 */
@Component
public class KisCall {

    private static final Logger log = LoggerFactory.getLogger(KisCall.class);

    private final RestClient restClient;
    private final KisTokenStore tokens;
    private final KisHeaders headers;
    private final KisThrottle throttle;

    public KisCall(RestClient.Builder builder,
            @Value("${economy-helper.market.kis.base-url}") String baseUrl,
            KisTokenStore tokens, KisHeaders headers, KisThrottle throttle) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokens = tokens;
        this.headers = headers;
        this.throttle = throttle;
    }

    /**
     * @param what 로그와 예외 메시지에 그대로 실리는 이름({@code "국내 종목 005930"}). 도메인마다
     *             달라야 어느 조회가 실패했는지 로그에서 갈린다
     * @throws IllegalStateException 호출이 실패했거나 {@code rt_cd}가 {@code "0"}이 아닐 때.
     *                               <b>원래 예외를 감싸지 않고 이유 문자열만 싣는다</b> — 토큰이
     *                               실린 예외를 사슬에 남기지 않으려는 것이다
     */
    public <T extends KisResponse> T get(Class<T> type, String trId, String what,
                                  Function<UriBuilder, URI> uri) {
        try {
            return attempt(type, trId, what, uri);
        } catch (RateLimited first) {
            // ⚠️ 한 번만, 그리고 간격 문을 다시 지나서 — 다른 실패(무효 토큰·잘못된 키)는 다시 불러도
            //    안 낫고, 거절 하나에 2~3초를 쓴다(ADR-0001)
            log.info("[kis] {} 초당 거래건수 초과 — 간격을 두고 한 번 더 부릅니다", what);
        }
        try {
            return attempt(type, trId, what, uri);
        } catch (RateLimited second) {
            log.warn("[kis] {} 조회 실패: {}", what, second.reason);
            throw second.failure;
        }
    }

    /**
     * 한 번 부른다 — <b>실패는 HTTP 에러로도, 200 본문으로도 온다.</b> 둘 다 {@code msg_cd}로 종류를 갈라
     * 같은 길로 보낸다: 초당 한도 초과는 {@link RateLimited}로(호출자가 한 번 더 부른다), 무효 토큰은 버린다.
     * 실측으로 초당 한도 초과는 200 본문으로 더 자주 왔다.
     */
    private <T extends KisResponse> T attempt(Class<T> type, String trId, String what,
                                              Function<UriBuilder, URI> uri) {
        T response;
        try {
            response = send(type, trId, uri);
        } catch (KisThrottle.Congested ours) {
            // 우리 간격 문이 거절한 것이다 — 감싸지 않는다. 브레이커 무시 목록이 이 타입을 적는다
            throw ours;
        } catch (RuntimeException e) {
            throw failed(KisHeaders.codeOf(e), KisHeaders.reasonOf(e), "KIS " + what + " 조회 실패: ", what);
        }
        // ⚠️ 빈 본문을 rt_cd로 읽으면 「rt_cd=null」이라는 헷갈리는 메시지가 나간다 —
        //    원인을 KIS의 응답 코드 탓으로 오해하게 만드는 자리라 제 이름으로 던진다
        if (response == null) {
            throw new IllegalStateException("KIS " + what + " 응답이 비어 있습니다");
        }
        if (!KisHeaders.isOk(response.resultCode())) {
            String message = response.message();
            String reason = message == null || message.isBlank() ? "알 수 없는 오류" : message.trim();
            throw failed(response.messageCode(), reason,
                    "KIS " + what + " 조회 실패 (rt_cd=" + response.resultCode() + "): ", what);
        }
        return response;
    }

    /** 호출 하나에 간격 하나 — KIS의 제약은 "초당 몇 건"이 아니라 "호출 사이 얼마"다. 재시도도 문을 지난다. */
    private <T> T send(Class<T> type, String trId, Function<UriBuilder, URI> uri) {
        throttle.pace();
        return restClient.get()
                .uri(uri)
                .headers(headers.of(tokens.token(), trId))
                .retrieve()
                .body(type);
    }

    /**
     * 실패 하나를 던질 예외로 — <b>초당 한도 초과만 {@link RateLimited}</b>(다시 부를 수 있다)이고
     * 나머지는 이유만 실은 {@link IllegalStateException}이다.
     *
     * @param code   {@code msg_cd}. HTTP 에러 본문에서든 200 본문에서든 같은 판정을 받는다
     * @param reason 사람이 읽을 이유. 예외 이름만으로는 부족하다 — 무효 토큰이 500으로 오고 이유가 본문에만 있다
     */
    private RuntimeException failed(String code, String reason, String prefix, String what) {
        IllegalStateException failure = new IllegalStateException(prefix + reason);
        if (KisHeaders.isRateLimited(code)) {
            return new RateLimited(failure, reason);
        }
        log.warn("[kis] {} 조회 실패: {}", what, reason);
        // 무효 토큰은 다음 호출에서도 같은 이유로 실패한다. 알아차린 자리에서 버려야
        // 스스로 낫는다 — 안 버리면 기록된 만료까지(최대 24시간) 모든 KIS 호출이 죽는다
        if (KisHeaders.isInvalidToken(code)) {
            tokens.invalidate();
        }
        return failure;
    }

    /**
     * 「한 번 더 부르라」는 내부 신호 — 밖으로 나가지 않는다. 두 번째도 이것이면 {@link #failure}를 던진다.
     *
     * <p>스택을 안 남긴다 — 흐름 제어용이고 사슬에 토큰이 실린 원래 예외를 걸지 않는다.
     */
    private static final class RateLimited extends RuntimeException {

        private final transient IllegalStateException failure;
        private final String reason;

        private RateLimited(IllegalStateException failure, String reason) {
            super(null, null, false, false);
            this.failure = failure;
            this.reason = reason;
        }
    }
}
