package io.saiden.economyhelper.infrastructure.kis;

import io.saiden.economyhelper.config.EconomyHelperProperties.Kis;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final String OK = "0";

    /**
     * 무효 토큰. 이 코드만 따로 알아본다 — <b>대응이 다르기 때문이다.</b> 나머지 에러는
     * 다시 부르면 낫지만 이건 안 낫는다({@link #reasonOf} 참고).
     */
    private static final String INVALID_TOKEN = "EGW00121";
    private static final String RATE_LIMITED = "EGW00201";

    private static final Pattern MESSAGE = Pattern.compile("\"msg1\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CODE = Pattern.compile("\"msg_cd\"\\s*:\\s*\"([^\"]*)\"");

    private final String appKey;
    private final String appSecret;

    public KisHeaders(EconomyHelperProperties properties) {
        Kis kis = properties.market().kis();
        // ⚠️ **끝의 줄바꿈을 뗀다** — KisTokenStore와 같은 이유이고, 여기는 한 겹 더 나쁘다:
        //    이 값이 HTTP **헤더**로 실리므로 개행이 붙으면 헤더가 깨진다
        this.appKey = trimmed(kis.appKey());
        this.appSecret = trimmed(kis.appSecret());
    }

    private static String trimmed(String key) {
        return key == null ? "" : key.trim();
    }

    /**
     * <b>{@code custtype}이 빠지면 이유 없이 실패한다.</b> KIS 자체 예제도 무조건 넣는다 —
     * {@code P}가 개인·법인, {@code B}가 제휴사다.
     *
     * <p>키를 쿼리가 아니라 <b>헤더</b>로 보낸다. URL은 로그·프록시에 그대로 남는다
     * ({@code GeminiApi}가 같은 이유로 헤더를 쓴다). 대신 예외 메시지에 헤더가 실릴 수 있으므로
     * {@link KisCall}이 예외를 이유 문자열로만 바꿔 던진다.
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
     * <p>⚠️ 200에 실린 실패도 {@code msg_cd}로 종류를 가른다 — 초당 한도 초과·무효 토큰이 500과 200
     * 양쪽으로 오므로 판정은 {@link #isRateLimited}·{@link #isInvalidToken} 하나씩이다({@link KisCall}).
     *
     * @return {@code rt_cd}가 {@code "0"}인가. 아니면 호출자가 던져야 이중화가 폴백한다
     */
    static boolean isOk(String resultCode) {
        return OK.equals(resultCode);
    }

    /**
     * HTTP 에러에 담긴 <b>KIS가 말한 이유</b>. 없으면 예외 이름만 돌려준다.
     *
     * <p><b>200 본문의 {@code rt_cd}만으로는 부족했다.</b> KIS는 <b>무효 토큰에 401이 아니라 500</b>을
     * 주고 이유는 그 500 본문에만 있다(실측 2026-08-19):
     * {@code {"rt_cd":"1","msg1":"유효하지 않은 token 입니다.","msg_cd":"EGW00121"}}.
     * {@code body(type)}가 먼저 던지므로 응답 레코드는 아예 만들어지지 않는다.
     *
     * <p>이 본문을 버리면 남는 것이 {@code InternalServerError} 하나라 상대 서버 장애로 읽힌다 —
     * 실제로 "NYSE 종목은 못 온다"로 오진한 적이 있다(유효한 토큰이면 다 온다: 실측 {@code ORCL}
     * 141.25 · {@code PATH} 15.54).
     *
     * <p><b>무효 토큰은 6시간 안에는 재발급으로 낫지 않는다</b> — KIS가 같은 죽은 토큰을
     * 돌려준다({@code KisTokenStore} 참고). 그래서 {@link #isInvalidToken}이 이 코드를 알아보면
     * {@code KisTokenStore.invalidate()}가 <b>버리고 6시간 뒤에 다시 받는다.</b> 안 버리면 기록된
     * 만료까지(최대 24시간) 모든 KIS 호출이 죽는다.
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
    static boolean isInvalidToken(String code) {
        return INVALID_TOKEN.equals(code);
    }

    /**
     * <b>KIS가 "초당 거래건수를 넘겼다"고 말한 것인가</b>({@code msg_cd=EGW00201}) — 500 가운데
     * <b>유일하게 잠시 뒤 다시 하면 낫는</b> 실패다(실측 2026-08-20). 호출 사이 1초를 지켜도 모의 서버가
     * 가끔 이것을 준다(실물 감사 2026-09-29: {@code PATH} NAS→NYS 1.2초 간격, 삼성전자 배당일정).
     */
    static boolean isRateLimited(String code) {
        return RATE_LIMITED.equals(code);
    }

    /** HTTP 에러 본문의 {@code msg_cd}. 본문이 없거나 JSON이 아니면 {@code null} — 절대 던지지 않는다. */
    static String codeOf(RuntimeException e) {
        if (!(e instanceof RestClientResponseException failure)) {
            return null;
        }
        return group(CODE, failure.getResponseBodyAsString(StandardCharsets.UTF_8));
    }

    private static String group(Pattern pattern, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(body);
        return matcher.find() && !matcher.group(1).isBlank() ? matcher.group(1).trim() : null;
    }

    /**
     * KIS가 준 {@code uuuuMMdd} 날짜 — STRICT로 읽는다({@code 20260231}을 2월 28일로 고치지 않는다).
     *
     * @return 비었거나 못 읽으면 {@code null}. 응답 한 줄의 결함이 값 전체를 던지게 하지 않는다
     */
    public static LocalDate dateOf(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(yyyymmdd.trim(), YYYYMMDD);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 이 날짜의 값이 <b>오늘 값이 아닌가</b> — 주말·휴일·장 전에는 마지막 영업일의 종가가 현재가 자리에 온다.
     *
     * <p>그 값에 읽은 시각을 찍으면 낡은 값을 신선하게 꾸미는 셈이다. 날짜를 모르면 거짓이다 — 모른다고
     * 낡았다고 단정하지 않는다.
     */
    public static boolean closedBefore(LocalDate latest, Clock clock) {
        return closedBefore(latest, clock, SEOUL);
    }

    /**
     * {@link #closedBefore(LocalDate, Clock)}의 시장 달력 판 — 미국 시세의 일봉 날짜는 미국 날짜다. KST로 견주면
     * 서울 새벽 2시(뉴욕 오후 1시, 장중)의 값이 「어제 종가」로 찍힌다.
     */
    public static boolean closedBefore(LocalDate latest, Clock clock, ZoneId market) {
        return latest != null && latest.isBefore(LocalDate.ofInstant(clock.instant(), market));
    }

    /** 그 날짜의 KST 자정 — 「종가」 값의 기준 시각이다(시각은 모르므로 있는 척하지 않는다). */
    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(SEOUL).toInstant();
    }

    /** KIS는 날짜를 {@code yyyyMMdd}로 받는다. 조회 기준은 그 지점의 달력인 KST다. */
    public static String today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), SEOUL).format(YYYYMMDD);
    }

    /** 며칠 전. 비영업일이 끼면 오늘만 물어서는 빈 배열이 온다. */
    public static String daysAgo(Clock clock, int days) {
        return LocalDate.ofInstant(clock.instant(), SEOUL).minusDays(days).format(YYYYMMDD);
    }
}
