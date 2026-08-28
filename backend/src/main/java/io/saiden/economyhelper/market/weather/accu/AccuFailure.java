package io.saiden.economyhelper.market.weather.accu;

import io.saiden.economyhelper.support.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

/**
 * 실패 사유를 <b>키 없이</b> 한 마디로 줄인다.
 *
 * <p>AccuWeather는 키를 쿼리 파라미터로만 받는다. RestClient 예외 메시지에는 요청 URL이
 * 그대로 들어 있어, 던지거나 로그에 남기면 키가 유출된다({@code FmpApi}·{@code KeximFxClient}가
 * 같은 이유로 예외를 다시 감싼다). 지점 조회와 예보가 같은 규칙을 써야 해서 여기 모았다.
 */
final class AccuFailure {

    private static final Logger log = LoggerFactory.getLogger(AccuFailure.class);

    /** 하루 50회를 다 썼다는 뜻이다(실측 문구: {@code The allowed number of requests has been exceeded}). */
    private static final int QUOTA_EXHAUSTED = 503;

    private AccuFailure() {
    }

    /**
     * 로그 한 줄과 던질 예외를 <b>함께</b> 만든다 — 지점 조회와 예보가 글자까지 같은 블록을 각자 들고 있었고,
     * 그 안에서 {@link #reasonOf}를 두 번씩 불렀다.
     *
     * @param what  「지점 조회」·「조회」 — 로그와 예외 문구의 목적어
     * @param place 지점 이름
     */
    static IllegalStateException failed(String what, String place, RuntimeException e) {
        String reason = reasonOf(e);
        log.warn("[accu] {} {} 실패: {}", place, what, reason);
        return new IllegalStateException("AccuWeather " + what + " 실패 (" + place + "): " + reason);
    }

    /**
     * 재시도가 소용 있는 실패인지 드러나게 적는다.
     *
     * <p>503은 한도 소진, 401·403은 키 문제라 자정까지 기다려도 안 풀린다. 둘 다 상대 장애와
     * 구분해 두어야 로그를 보고 무엇을 고쳐야 할지 알 수 있다. 그 밖의 실패는
     * {@link FailureReason}이 분류한다 — 브레이커 열림·리미터 거절·타임아웃이 거기서 갈린다.
     *
     * <p>⚠️ <b>상태 코드를 {@code e.toString()}에서 찾지 않는다.</b> 예전에는
     * {@code message.contains("503")}이었는데, 그 문자열에는 <b>요청 URL이 들어 있고</b> URL에는
     * 지점 키와 API 키가 실려 있다 — 키에 {@code 503}이 섞이면 멀쩡한 실패가 "한도 소진"으로
     * 읽히고, 반대로 브레이커가 열린 것은 아무 갈래에도 안 걸려 클래스 이름만 남았다.
     * 지금은 {@link RestClientResponseException#getStatusCode()}로 정확히 본다.
     */
    static String reasonOf(RuntimeException e) {
        if (e instanceof RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == QUOTA_EXHAUSTED) {
                return "일일 호출 한도(50회)를 소진했거나 서비스가 일시 중단됐습니다";
            }
            if (status == 401 || status == 403) {
                return "키가 잘못됐거나 권한이 없습니다";
            }
        }
        // ⚠️ FailureReason은 상태 코드와 예외 이름만 돌려준다 — 메시지를 싣지 않으므로
        //    URL에 실린 키가 여기로 새지 않는다. 이 클래스의 존재 이유가 그것이다
        return FailureReason.of(e);
    }
}
