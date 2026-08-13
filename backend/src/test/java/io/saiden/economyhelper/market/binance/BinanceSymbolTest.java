package io.saiden.economyhelper.market.binance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 심볼 유도 규칙. <b>USDT를 거르는 것이 이 클래스의 존재 이유다</b> — 없는 심볼이 하나라도
 * 섞이면 요청 전체가 400이라 브리핑의 코인 시세가 통째로 사라진다.
 */
class BinanceSymbolTest {

    @Test
    @DisplayName("원화 마켓에서 기준 자산만 떼어 USDT를 붙인다")
    void derivesUsdtSymbol() {
        assertThat(BinanceSymbol.of("KRW-BTC")).contains("BTCUSDT");
        assertThat(BinanceSymbol.of("KRW-ETH")).contains("ETHUSDT");
        assertThat(BinanceSymbol.of("KRW-XRP")).contains("XRPUSDT");
    }

    @Test
    @DisplayName("USDT 자신은 제외한다 — USDTUSDT는 존재하지 않고, 섞이면 요청 전체가 400이다")
    void excludesUsdtItself() {
        assertThat(BinanceSymbol.of("KRW-USDT")).isEmpty();
    }

    @Test
    @DisplayName("원화 마켓이 아니면 유도하지 않는다 — BTC 마켓은 가격 단위가 달라 섞이면 안 된다")
    void ignoresNonKrwMarkets() {
        assertThat(BinanceSymbol.of("BTC-ETH")).isEmpty();
        assertThat(BinanceSymbol.of("USDT-BTC")).isEmpty();
        assertThat(BinanceSymbol.of("KRW-")).isEmpty();
        assertThat(BinanceSymbol.of(null)).isEmpty();
    }
}
