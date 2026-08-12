package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 코인 현재가 한 건.
 *
 * <p>등락률·전일종가는 업비트가 주지만 담지 않는다. {@code CLAUDE.md}가 요구하는 건 "현재 가격"이고,
 * 토스는 등락률을 주지 않아 {@code /stock}에는 넣을 수 없다 — 코인에만 붙이면 명령마다
 * 정보 밀도가 달라진다.
 *
 * @param market     업비트 마켓 코드 ({@code KRW-BTC})
 * @param koreanName 화면에 쓸 이름 ({@code 비트코인})
 * @param price      원화 현재가
 * @param at         체결 시각
 */
public record CryptoQuote(String market, String koreanName, BigDecimal price, Instant at) {
}
