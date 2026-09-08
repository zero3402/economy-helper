package io.saiden.economyhelper.market;

/**
 * 국내 종목의 전망(목표주가·배당) — <b>이중화 상대가 없다.</b>
 *
 * <p>구현은 KIS 하나이고 그 안에서 <b>엔드포인트 둘</b>을 부른다({@code invest-opinion}·
 * 예탁원정보 배당일정). <b>하나라도 받았으면 그것으로 답하고</b>, 성공한 것이 하나도 없을 때만
 * 던진다 — 그 판단은 구현 안에 있다. 반쪽을 안 담으면 조회마다 KIS 문 2초를 다시 쓰기 때문이다.
 *
 * <p>⚠️ <b>실적발표일은 없다.</b> 두 엔드포인트 어느 쪽도 그 필드를 주지 않고 무료 대안을
 * 찾지 못했다 — 그 칸은 언제나 {@code null}이고 화면은 그 줄을 안 적는다.
 */
public interface DomesticOutlookClient {

    /**
     * @param code 6자리 종목코드
     * @param fund 색인이 <b>ETF·ETN이라고 알려 줬나</b>. 참이면 목표주가 조회를 <b>건너뛴다</b> —
     *             증권사가 목표가를 내는 것은 기업이고 ETF에 물으면 늘 0행이다(실측 426030).
     *             <b>배당은 그래도 묻는다</b>: ETF의 분배금이 같은 배당일정 응답에 실제로 온다
     *             (실측 2026-09-08, 코드가 보내는 ±180일 창 — KODEX 200(069500) 두 행). 모르는 경로는 거짓을 준다
     * @return 전망. <b>{@code null}이 아니다</b> — 아무 값도 없으면 {@link StockOutlook#isEmpty()}가 참인 값이다.
     *         빈 값도 값이라 캐시된다(「의견 낸 증권사가 없다」는 12시간 안에 안 바뀐다). 조회 실패는 던진다
     */
    StockOutlook outlook(String code, boolean fund);
}
