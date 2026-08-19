package io.saiden.economyhelper.market.fmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.fmp.FmpApi.FmpQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * <b>미국 이중화의 마지막 자리</b>라 여기서 빈 값이 새면 받아 줄 출처가 없다.
 *
 * <p>HTTP는 {@code FmpApiTest}가 본다. 여기서는 <b>SPI 계약</b>만 본다 — 값이 아닌 것을
 * 값으로 내보내지 않는가.
 */
class FmpStockClientTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("현재가가 null이면 던진다 — 상장폐지·장전 무거래가 그렇게 온다")
    void refusesAQuoteWithoutAPrice() {
        FmpStockClient client = client(new FmpQuote("PATH", "UiPath Inc.", null, null, null));

        assertThatThrownBy(() -> client.quote(new UsSymbol("PATH", "유아이패스")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("값이 없습니다");
    }

    @Test
    @DisplayName("현재가가 0이면 던진다 — 0은 값이 아니라 '없다'다")
    void refusesZeroAsAPrice() {
        FmpStockClient client = client(new FmpQuote("PATH", "UiPath Inc.", BigDecimal.ZERO, null, null));

        assertThatThrownBy(() -> client.quote(new UsSymbol("PATH", "유아이패스")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("값이 있으면 우리 이름으로 내보낸다 — FMP의 영문명이 한 화면에 섞이지 않게")
    void prefersOurOwnKoreanName() {
        FmpStockClient client = client(
                new FmpQuote("AAPL", "Apple Inc.", new BigDecimal("232.78"), new BigDecimal("1.2"), null));

        StockQuote quote = client.quote(new UsSymbol("AAPL", "애플"));

        assertThat(quote.name()).isEqualTo("애플");
        assertThat(quote.price()).isEqualByComparingTo("232.78");
        assertThat(quote.realtime()).isTrue();
        assertThat(quote.at()).isEqualTo(NOW);
    }

    private static FmpStockClient client(FmpQuote quote) {
        return new FmpStockClient(new FixedApi(quote), CLOCK);
    }

    /** HTTP를 타지 않는다 — 이 테스트가 보는 것은 SPI 계약뿐이다. */
    private static final class FixedApi extends FmpApi {
        private final FmpQuote quote;

        private FixedApi(FmpQuote quote) {
            super(RestClient.builder(), "http://localhost:1", "key", null);
            this.quote = quote;
        }

        @Override
        public FmpQuote quote(String symbol) {
            return quote;
        }
    }
}
