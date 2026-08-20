package io.saiden.economyhelper.market.kis;

/**
 * KIS 응답 네 스키마가 공유하는 부분.
 *
 * <p><b>에러가 HTTP 200 본문에 실려 온다</b>(실측: 초당 한도 초과 시 {@code rt_cd=1} +
 * "초당 거래건수를 초과하였습니다"). 그래서 성패는 상태코드가 아니라 본문의 두 필드로
 * 가른다 — {@link KisHeaders#verify}가 그 판단을 한 곳에서 한다.
 *
 * <p>이 인터페이스가 있어서 호출 한 번을 제네릭 하나로 쓸 수 있다. 없으면 경로마다
 * 같은 {@code try/catch}·같은 토큰 가리기가 네 벌 생긴다.
 */
interface KisResponse {

    /** {@code rt_cd} — {@code "0"}이 아니면 실패다. */
    String resultCode();

    /** {@code msg1} — 실패 사유. 화면이 아니라 로그·예외 메시지에 쓴다. */
    String message();
}
