package io.saiden.economyhelper.stock.domain;

/**
 * 상장 종목 하나 — KIS 종목 마스터 한 행.
 *
 * @param code      단축코드 {@code 005930}·{@code 0019K0} — KIS 국내 시세의 조회 키
 * @param name      한글 상장명 {@code TIME 미국나스닥100액티브}. 브랜드는 영문 그대로 온다
 * @param group     그룹코드 {@code ST}·{@code EF}·{@code EN}. 화면에 안 쓰고 로그·테스트가 본다
 * @param marketCap 시가총액(억). 동명 후보를 가르는 내부 신호 — 공공데이터포털의 {@code mrktTotAmt}와 같은 자리
 */
public record Listing(String code, String name, String group, long marketCap) {

    /**
     * ETF·ETN인가 — 증권사가 <b>목표주가</b>를 내지 않는 것들이다. {@code invest-opinion}에 물어도
     * 늘 0행이다(실측 426030).
     *
     * <p>⚠️ 이 플래그가 뜻하는 것은 <b>목표주가를 건너뛰라</b>는 것뿐이다 — 예탁원 배당일정은 ETF에도
     * <b>분배금 행을 준다</b>(실측 2026-09-08, KODEX 200 두 행).
     */
    public boolean fund() {
        return "EF".equals(group) || "EN".equals(group);
    }
}
