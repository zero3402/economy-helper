package io.saiden.economyhelper.crypto.domain;

/**
 * LLM이 검색어에서 짚어 낸 코인 — 업비트에 없는 코인만 여기로 온다.
 *
 * <p><b>이름은 받지 않는다.</b> 업비트에 없는 코인이라 한글명을 확인할 곳이 없고, LLM에게
 * 물으면 {@code BNB → 비앤비}처럼 아무도 그렇게 부르지 않는 표기가 제목에 찍힌다.
 * 그럴 바에는 티커가 정확하다.
 *
 * @param symbol 대문자 티커({@code BNB}). 해석기가 다듬어 담는다 — {@code "null"} 문자열·공백은
 *               여기까지 오지 않는다. 바이낸스 심볼은 여기에 {@code USDT}를 붙여 만든다
 */
public record ResolvedCoin(String symbol) {}
