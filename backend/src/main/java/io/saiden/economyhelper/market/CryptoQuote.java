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
 * <p>원화 환산값은 담지 않는다. 환산은 <b>표시 시점의 관심사</b>고, {@code formatStock}이 환율을
 * 인자로 받는 것과 같은 이유다 — 모델에 넣어 두면 시세와 환율의 시각이 엇갈린 채로 굳는다.
 *
 * @param market      업비트 마켓 코드 ({@code KRW-BTC})
 * @param koreanName  화면에 쓸 이름 ({@code 비트코인})
 * @param price       업비트 원화 현재가
 * @param at          체결 시각
 * @param binanceUsdt 바이낸스 USDT 현재가. <b>{@code null}이면 표시하지 않는다</b> —
 *                    바이낸스에 상장되지 않았거나(예: USDT 자신) 조회가 실패한 경우다.
 *                    0이나 빈 값으로 채우면 "시세가 0"과 "모른다"가 구분되지 않는다
 */
public record CryptoQuote(String market, String koreanName, BigDecimal price, Instant at,
                          BigDecimal binanceUsdt) {

    /** 바이낸스 값을 붙인 사본. 업비트 조회와 바이낸스 조회가 별개 호출이라 필요하다. */
    public CryptoQuote withBinance(BigDecimal usdt) {
        return new CryptoQuote(market, koreanName, price, at, usdt);
    }
}
