package io.saiden.economyhelper.market.kis;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

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

    private final String appKey;
    private final String appSecret;

    public KisHeaders(@Value("${economy-helper.market.kis.app-key:}") String appKey,
                      @Value("${economy-helper.market.kis.app-secret:}") String appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
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

    /** KIS는 날짜를 {@code yyyyMMdd}로 받는다. 조회 기준은 그 지점의 달력인 KST다. */
    static String today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), SEOUL).format(YYYYMMDD);
    }

    /** 며칠 전. 비영업일이 끼면 오늘만 물어서는 빈 배열이 온다. */
    static String daysAgo(Clock clock, int days) {
        return LocalDate.ofInstant(clock.instant(), SEOUL).minusDays(days).format(YYYYMMDD);
    }
}
