package io.saiden.economyhelper.infrastructure.kis;

/**
 * KIS 응답 스키마들이 공유하는 부분 — 시세 넷·일봉·투자의견·배당일정이 이것을 구현한다.
 *
 * <p><b>에러가 HTTP 200 본문에 실려 온다</b>(실측: 초당 한도 초과 시 {@code rt_cd=1} +
 * "초당 거래건수를 초과하였습니다"). 그래서 성패는 상태코드가 아니라 본문의 두 필드로
 * 가른다 — {@link KisCall}이 그 판단을 한 곳에서 한다.
 *
 * <p>이 인터페이스가 있어서 호출 한 번을 제네릭 하나로 쓸 수 있다. 없으면 경로마다
 * 같은 {@code try/catch}·같은 토큰 가리기가 그만큼 생긴다.
 */
public interface KisResponse {

    /** {@code rt_cd} — {@code "0"}이 아니면 실패다. */
    String resultCode();

    /** {@code msg1} — 실패 사유. 화면이 아니라 로그·예외 메시지에 쓴다. */
    String message();

    /**
     * {@code msg_cd} — 실패의 <b>종류</b>. 초당 한도 초과({@code EGW00201})와 무효 토큰({@code EGW00121})이
     * 500으로도, 200 본문으로도 온다 — 둘을 같은 길로 다루려면 200 쪽에서도 이 코드를 읽어야 한다.
     */
    String messageCode();
}
