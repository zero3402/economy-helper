package io.saiden.economyhelper.market.kis;

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
 * <p>경로마다 응답 타입만 다르고 <b>헤더·에러 처리·비밀 취급이 같다.</b> 그래서 클라이언트
 * 셋({@link KisStockApi}·{@link KisFxClient}·{@link KisDomesticOutlookClient})이 이 열댓 줄을
 * <b>각자 한 벌씩</b> 들고 있었고, 이미 <b>세 갈래로 어긋나 있었다</b> — 빈 응답을 하나는
 * {@code rt_cd=null}이라는 헷갈리는 메시지로 냈고, 하나는 제 메시지로 던졌고, 하나는 로그의
 * 태그가 빠져 있었다.
 *
 * <p><b>어긋나면 안 되는 이유가 둘이고 둘 다 안전에 걸린다.</b>
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
 * <p>즉 <b>한 파일의 정확성이 나머지 둘에 달려 있었다.</b> 네 번째 KIS 엔드포인트를 붙이는
 * 사람이 {@code isInvalidToken}을 잊으면 그날부터 최대 하루가 그렇게 된다. 이제 잊을 자리가 없다.
 *
 * <p>⚠️ <b>브레이커는 합쳐지지 않는다 — 합치면 §4-3을 어긴다.</b>
 * {@code @CircuitBreaker}는 각 클라이언트의 <b>공개 SPI 메서드</b>에 붙어 있고 이 클래스에는
 * 없다. {@code kisStock}·{@code kisFx}·{@code kisOutlook}이 그대로 갈려 있어야 전망(보충)의
 * 실패가 시세(답 자체)를 끊지 않는다. 여기서 공유하는 것은 <b>호출 한 번의 기계</b>뿐이다.
 *
 * <p>⚠️ <b>대가가 하나 있다: 실패 경고의 발행 클래스가 바뀌었다.</b> 전에는 {@code KisStockApi}·
 * {@code KisFxClient}·{@code KisDomesticOutlookClient} 로거에서 났고 지금은 전부 여기서 난다 —
 * <b>클라이언트 이름으로 로그를 걸러 오던 사람은 그 실패를 못 찾는다.</b> 그래도 받아들이는
 * 이유는 {@code what}이 이미 그 일을 하기 때문이다({@code "국내 종목 005930"}·{@code "환율"}·
 * {@code "005930 배당일정"}) — 클래스 이름보다 좁게 가리킨다. 호출자마다 로거를 넘겨받는 쪽은
 * 얻는 것 없이 인자만 늘린다. (적대적 리뷰가 짚어 여기 적어 둔다.)
 *
 * <p>공유 단위가 <b>벤더</b>인 것도 {@link KisHeaders}가 이미 정해 둔 granularity다 —
 * 「벤더 단위로만 공유하고, 다른 벤더까지 아우르는 공통 베이스는 만들지 않는다」.
 * {@code KisHeaders}가 헤더와 {@code rt_cd}를 맡았는데 {@code try/catch}·{@code pace}·
 * {@code invalidate} 절반은 따라오지 못했던 것을 여기서 마친다.
 */
@Component
class KisCall {

    private static final Logger log = LoggerFactory.getLogger(KisCall.class);

    private final RestClient restClient;
    private final KisTokenStore tokens;
    private final KisHeaders headers;
    private final KisThrottle throttle;

    KisCall(RestClient.Builder builder,
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
    <T extends KisResponse> T get(Class<T> type, String trId, String what,
                                  Function<UriBuilder, URI> uri) {
        // 호출 하나에 간격 하나 — KIS의 제약은 "초당 몇 건"이 아니라 "호출 사이 얼마"다.
        // 거래소를 두 번 물어보면 그 사이도 벌어진다
        throttle.pace();
        T response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .headers(headers.of(tokens.token(), trId))
                    .retrieve()
                    .body(type);
        } catch (RuntimeException e) {
            // 예외 이름만으로는 부족하다 — 무효 토큰이 500으로 오고 이유가 본문에만 있다
            String reason = KisHeaders.reasonOf(e);
            log.warn("[kis] {} 조회 실패: {}", what, reason);
            // 무효 토큰은 다음 호출에서도 같은 이유로 실패한다. 알아차린 자리에서 버려야
            // 스스로 낫는다 — 안 버리면 기록된 만료까지(최대 24시간) 모든 KIS 호출이 죽는다
            if (KisHeaders.isInvalidToken(e)) {
                tokens.invalidate();
            }
            throw new IllegalStateException("KIS " + what + " 조회 실패: " + reason);
        }
        // ⚠️ 빈 본문을 verify에 넘기면 「rt_cd=null」이라는 헷갈리는 메시지가 나간다 —
        //    원인을 KIS의 응답 코드 탓으로 오해하게 만드는 자리라 제 이름으로 던진다
        if (response == null) {
            throw new IllegalStateException("KIS " + what + " 응답이 비어 있습니다");
        }
        // ⚠️ 에러가 HTTP 200 본문에 실려 온다 — rt_cd를 봐야 한다
        KisHeaders.verify(response.resultCode(), response.message(), what);
        return response;
    }
}
