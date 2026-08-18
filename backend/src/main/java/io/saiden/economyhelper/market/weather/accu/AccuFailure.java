package io.saiden.economyhelper.market.weather.accu;

/**
 * 실패 사유를 <b>키 없이</b> 한 마디로 줄인다.
 *
 * <p>AccuWeather는 키를 쿼리 파라미터로만 받는다. RestClient 예외 메시지에는 요청 URL이
 * 그대로 들어 있어, 던지거나 로그에 남기면 키가 유출된다({@code FmpApi}·{@code KeximFxClient}가
 * 같은 이유로 예외를 다시 감싼다). 지점 조회와 예보가 같은 규칙을 써야 해서 여기 모았다.
 */
final class AccuFailure {

    private AccuFailure() {
    }

    /**
     * 재시도가 소용 있는 실패인지 드러나게 적는다.
     *
     * <p>503은 하루 50회를 다 썼다는 뜻이다(실측 문구: {@code The allowed number of requests
     * has been exceeded}). 401·403은 키 문제라 자정까지 기다려도 안 풀린다. 둘 다 상대 장애와
     * 구분해 두어야 로그를 보고 무엇을 고쳐야 할지 알 수 있다.
     */
    static String reasonOf(RuntimeException e) {
        String message = e.toString();
        if (message.contains("503")) {
            return "일일 호출 한도(50회)를 소진했거나 서비스가 일시 중단됐습니다";
        }
        if (message.contains("401") || message.contains("403")) {
            return "키가 잘못됐거나 권한이 없습니다";
        }
        return e.getClass().getSimpleName();
    }
}
