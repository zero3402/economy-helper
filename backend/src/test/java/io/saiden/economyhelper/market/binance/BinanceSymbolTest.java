package io.saiden.economyhelper.market.binance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 심볼 유도 규칙. <b>테더를 어떻게 잇느냐가 이 클래스의 존재 이유다</b> — 없는 심볼이 하나라도
 * 섞이면 요청 전체가 400이라 브리핑의 코인 시세가 통째로 사라지는데, 테더는 없는 것이 아니라
 * 호가가 USD로 갈릴 뿐이다.
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
    @DisplayName("테더는 USDTUSD다 — USDTUSDT는 400이지만 USDTUSD는 실재한다(2026-08-15 실측)")
    void derivesUsdSymbolForTether() {
        assertThat(BinanceSymbol.of("KRW-USDT")).contains("USDTUSD");
    }

    @Test
    @DisplayName("호가 자산은 테더만 USD고 나머지는 USDT다 — 화면 단위가 여기서 갈린다")
    void tellsQuoteAsset() {
        assertThat(BinanceSymbol.quoteAssetOf("BTC")).isEqualTo("USDT");
        assertThat(BinanceSymbol.quoteAssetOf("USDT")).isEqualTo("USD");
        assertThat(BinanceSymbol.quoteAssetOf("usdt")).isEqualTo("USD");
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
