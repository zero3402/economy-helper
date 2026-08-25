package io.saiden.economyhelper.market.kis;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * KIS 호출에 공통으로 붙는 것들 — <b>헤더와 {@code rt_cd} 검사.</b>
 *
 * <p>환율과 국내 주식이 같은 앱키·같은 호스트를 쓰므로 이 규칙도 하나다({@code AccuFailure}가
 * 지점 조회와 예보에 걸쳐 있는 것과 같은 자리다). 벤더 단위로만 공유하고, 다른 벤더까지
 * 아우르는 공통 베이스는 만들지 않는다 — 키 위치도 에러 의미도 벤더마다 다르다.
 */
@Component
public class KisHeaders {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String OK = "0";

    /**
     * 무효 토큰. 이 코드만 따로 알아본다 — <b>대응이 다르기 때문이다.</b> 나머지 에러는
     * 다시 부르면 낫지만 이건 안 낫는다({@link #reasonOf} 참고).
     */
    private static final String INVALID_TOKEN = "EGW00121";

    private static final Pattern MESSAGE = Pattern.compile("\"msg1\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CODE = Pattern.compile("\"msg_cd\"\\s*:\\s*\"([^\"]*)\"");

    private final String appKey;
    private final String appSecret;

    public KisHeaders(@Value("${economy-helper.market.kis.app-key:}") String key,
                      @Value("${economy-helper.market.kis.app-secret:}") String secret) {
        // ⚠️ **끝의 줄바꿈을 뗀다** — KisTokenStore와 같은 이유이고, 여기는 한 겹 더 나쁘다:
        //    이 값이 HTTP **헤더**로 실리므로 개행이 붙으면 헤더가 깨진다
        this.appKey = key == null ? "" : key.trim();
        this.appSecret = secret == null ? "" : secret.trim();
    }

    /**
     * <b>{@code custtype}이 빠지면 이유 없이 실패한다.</b> KIS 자체 예제도 무조건 넣는다 —
     * {@code P}가 개인·법인, {@code B}가 제휴사다.
     *
     * <p>키를 쿼리가 아니라 <b>헤더</b>로 보낸다. URL은 로그·프록시에 그대로 남는다
     * ({@code GeminiApi}가 같은 이유로 헤더를 쓴다). 대신 예외 메시지에 헤더가 실릴 수 있으므로
     * 각 클라이언트가 예외를 다시 감싼다.
     */
    Consumer<HttpHeaders> of(String token, String trId) {
        return headers -> {
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", appKey);
            headers.set("appsecret", appSecret);
            headers.set("tr_id", trId);
            headers.set("custtype", "P");
        };
    }

    /**
     * <b>에러도 HTTP 200으로 온다.</b> 실측: 초당 한도를 넘기면 {@code rt_cd=1} +
     * {@code "초당 거래건수를 초과하였습니다"}가 200 본문에 실려 왔다. 수출입은행이
     * {@code result}를 본문에 담는 것과 같은 함정이라 같은 방식으로 막는다.
     *
     * <p><b>여기까지 오지 않는 에러가 있다.</b> HTTP 상태가 에러면 본문 파싱이 먼저 던져서
     * 이 검사는 실행되지 않는다 — 그 경로의 이유는 {@link #reasonOf}가 꺼낸다.
     *
     * @throws IllegalStateException {@code rt_cd}가 {@code "0"}이 아닐 때. 던져야 이중화가 폴백한다
     */
    static void verify(String resultCode, String message, String what) {
        if (OK.equals(resultCode)) {
            return;
        }
        String reason = message == null || message.isBlank() ? "알 수 없는 오류" : message.trim();
        throw new IllegalStateException(
                "KIS " + what + " 조회 실패 (rt_cd=" + resultCode + "): " + reason);
    }

    /**
     * HTTP 에러에 담긴 <b>KIS가 말한 이유</b>. 없으면 예외 이름만 돌려준다.
     *
     * <p><b>{@link #verify}만으로는 부족했다.</b> 그쪽은 200 본문의 {@code rt_cd}를 보는데,
     * KIS는 <b>무효 토큰에 401이 아니라 500</b>을 주고 이유는 그 500 본문에만 있다(실측
     * 2026-08-19): {@code {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}}.
     * {@code body(type)}가 그보다 먼저 던지므로 {@code verify}는 아예 실행되지 않는다.
     *
     * <p><b>이 본문을 버리던 대가가 컸다.</b> 남는 것이 {@code InternalServerError} 하나여서
     * 상대 서버 장애로 읽혔고, "NYSE 종목은 어느 출처로도 못 온다"가 문서 셋에 <b>측정 사실로</b>
     * 적혔다. 실제로는 유효한 토큰이면 다 온다(실측 {@code ORCL} 141.25 · {@code PATH} 15.54).
     *
     * <p><b>무효 토큰은 <i>당장</i>은 재발급으로 낫지 않는다 — 6시간 안에는.</b> 그 창 안의
     * 재요청에 KIS가 <b>같은 토큰을</b> 돌려주는데({@code KisTokenStore} 참고) 그 규칙이 죽은
     * 토큰에도 걸린다. 그래서 캐시를 지우고 다시 발급해도 같은 죽은 토큰이 온다.
     *
     * <p>⚠️ <b>예전에는 그것을 "자동 복구를 하지 않는다"로 읽었고, 그게 절반만 맞았다.</b>
     * 조건이 "6시간 안"인데 결론에서 그 조건이 사라져, 아무도 토큰을 버리지 않았다 —
     * 죽은 토큰이 <b>기록된 만료까지(최대 24시간) 그대로 남아</b> 그 창 동안 모든 KIS 호출이
     * 같은 이유로 실패했다. {@code /stock 유아이패스}가 하루 종일 빈손이던 정체가 그것이다
     * (미국 종목은 2순위 FMP가 {@code PATH}를 402로 막아 KIS가 유일한 길이다).
     *
     * <p>지금은 {@link #isInvalidToken}이 이 코드를 알아보고
     * {@code KisTokenStore.invalidate()}가 <b>버리고 6시간 뒤에 다시 받는다.</b> 앞당기지는
     * 않는다 — 그러면 알림톡만 한 번 더 가고 결과는 같다.
     *
     * <p><b>본문을 통째로 싣지 않는다.</b> 두 필드만 꺼낸다 — 다른 응답 본문에 무엇이 실릴지
     * 우리가 정하지 않기 때문이다. 같은 이유로 <b>토큰 발급 응답에는 이 메서드를 쓰지 않는다</b>
     * (그 본문에 접근토큰이 들어 있다 — {@code KisTokenStore.request}가 예외 이름만 남기는 이유다).
     *
     * <p>매퍼가 아니라 정규식인 이유는, 여기가 {@code catch} 안이라 <b>절대 던지지 않아야</b>
     * 하고 본문이 JSON이 아닐 수도 있어서다(게이트웨이가 HTML을 주는 일이 있다).
     */
    static String reasonOf(RuntimeException e) {
        String name = e.getClass().getSimpleName();
        if (!(e instanceof RestClientResponseException failure)) {
            return name;
        }
        // 폴백 문자셋을 UTF-8로 준다 — 안 주면 ISO-8859-1로 읽혀 한글 이유가 깨진다
        String body = failure.getResponseBodyAsString(StandardCharsets.UTF_8);
        String message = group(MESSAGE, body);
        if (message == null) {
            return name;
        }
        String code = group(CODE, body);
        if (code == null) {
            return name + " — " + message;
        }
        return name + " — " + message + " (" + code
                + (INVALID_TOKEN.equals(code)
                        ? ": 토큰을 버렸습니다 — 6시간 뒤에 다시 발급합니다. 앞당겨도 같은 죽은 "
                                + "토큰이 돌아옵니다)"
                        : ")");
    }

    /**
     * <b>KIS가 "네 토큰이 무효다"라고 말한 것인가</b>({@code msg_cd=EGW00121}).
     *
     * <p>{@link #reasonOf}가 만든 <b>문장을 되파싱하지 않는다.</b> 그 문자열은 사람이 읽는
     * 것이고 문구가 바뀌면 조용히 무력해진다 — 판정이 필요한 곳에는 코드를 보는 좁은 통로를
     * 따로 낸다({@code Failover}가 이름이 아니라 열거형으로 순서를 잡는 것과 같은 방향이다).
     *
     * <p><b>{@code EGW00304}에는 거짓을 준다.</b> 잘못된 앱시크릿도 500으로 오지만(실측
     * 2026-08-20: {@code 고객식별키(법인 personalSeckey, 개인 appSecret)가 유효하지
     * 않습니다}) 그건 토큰 문제가 아니라 <b>설정</b> 문제다. 참을 주면 멀쩡한 토큰을 버리고
     * 알림톡만 한 통 더 가고 결과는 같다. 즉 <b>KIS의 500은 영구 실패의 기본 표현</b>이고,
     * 그중 우리가 스스로 고칠 수 있는 하나만 여기서 갈라낸다.
     *
     * <p>{@code catch} 안에서 불리므로 {@link #reasonOf}와 같은 이유로 <b>절대 던지지 않는다.</b>
     */
    static boolean isInvalidToken(RuntimeException e) {
        if (!(e instanceof RestClientResponseException failure)) {
            return false;
        }
        return INVALID_TOKEN.equals(
                group(CODE, failure.getResponseBodyAsString(StandardCharsets.UTF_8)));
    }

    private static String group(Pattern pattern, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(body);
        return matcher.find() && !matcher.group(1).isBlank() ? matcher.group(1).trim() : null;
    }

    /** KIS는 날짜를 {@code yyyyMMdd}로 받는다. 조회 기준은 그 지점의 달력인 KST다. */
    static String today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), SEOUL).format(YYYYMMDD);
    }

    /** 며칠 전. 비영업일이 끼면 오늘만 물어서는 빈 배열이 온다. */
    static String daysAgo(Clock clock, int days) {
        return LocalDate.ofInstant(clock.instant(), SEOUL).minusDays(days).format(YYYYMMDD);
    }
}
