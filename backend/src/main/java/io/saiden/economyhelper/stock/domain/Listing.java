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
     * 국내 종목코드 모양 — 숫자 여섯이 아니라 <b>「첫 자가 숫자인 영숫자 여섯」</b>이다. 2025년부터 KRX 단축코드에
     * 영숫자가 있다(마스터 실측 {@code 0019K0 TIME 미국나스닥100채권혼합50액티브}, KIS 시세도 받는다).
     * 첫 자가 숫자라 미국 티커(영문 1~5자)와 겹치지 않는다. 대소문자는 가리지 않는다 — 검색어는 정규화가
     * 소문자로 내린다.
     */
    public static boolean codeShaped(String code) {
        return code.length() == 6 && Character.isDigit(code.charAt(0))
                && code.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }

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
