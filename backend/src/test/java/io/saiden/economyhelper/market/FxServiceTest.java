package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code CLAUDE.md}의 "TOSS 증권 API, 수출입은행 API로 이중화"가 실제로 이중화인지 본다.
 *
 * <p>가장 중요한 건 <b>1순위가 성공하면 2순위를 부르지 않는다</b>는 점이다.
 * 수출입은행은 하루 1,000회 제한이 있어, 매번 같이 부르면 폴백이 아니라 낭비다.
 */
class FxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T01:36:00Z");

    @Test
    @DisplayName("1순위가 성공하면 폴백은 호출조차 하지 않는다")
    void doesNotCallFallbackWhenPrimarySucceeds() {
        CountingClient frankfurter = CountingClient.returning(FxSource.FRANKFURTER, "1414.7");
        CountingClient kexim = CountingClient.returning(FxSource.KEXIM, "1415");

        FxRate rate = new FxService(List.of(frankfurter, kexim)).usdToKrw().orElseThrow();

        assertThat(rate.source()).isEqualTo(FxSource.KEXIM);
        assertThat(kexim.calls).hasValue(1);
        assertThat(frankfurter.calls).hasValue(0);
    }

    @Test
    @DisplayName("수출입은행이 비면 유럽중앙은행이 받는다 — 주말·공휴일에 실제로 밟는 길이다")
    void fallsBackWhenPrimaryFails() {
        CountingClient kexim = CountingClient.failing(FxSource.KEXIM);
        CountingClient frankfurter = CountingClient.returning(FxSource.FRANKFURTER, "1414.7");

        FxRate rate = new FxService(List.of(kexim, frankfurter)).usdToKrw().orElseThrow();

        assertThat(rate.source()).isEqualTo(FxSource.FRANKFURTER);
        assertThat(rate.rate()).isEqualByComparingTo("1414.7");
        assertThat(frankfurter.calls).hasValue(1);
    }

    @Test
    @DisplayName("전부 실패하면 빈 결과 — 예외를 밖으로 던지지 않는다")
    void returnsEmptyWhenAllFail() {
        FxService service = new FxService(
                List.of(CountingClient.failing(FxSource.FRANKFURTER), CountingClient.failing(FxSource.KEXIM)));

        assertThat(service.usdToKrw()).isEmpty();
    }

    @Test
    @DisplayName("주입 순서가 뒤바뀌어도 수출입은행이 1순위다 — 빈 등록 순서에 이중화가 딸려 가면 안 된다")
    void orderIsDeclaredNotInjected() {
        CountingClient frankfurter = CountingClient.returning(FxSource.FRANKFURTER, "1414.7");
        CountingClient kexim = CountingClient.returning(FxSource.KEXIM, "1415");

        // 유럽중앙은행을 먼저 주입해도
        FxRate rate = new FxService(List.of(frankfurter, kexim)).usdToKrw().orElseThrow();

        assertThat(rate.source())
                .as("원/달러는 한국 공식 고시환율이 기준이다")
                .isEqualTo(FxSource.KEXIM);
        assertThat(frankfurter.calls).hasValue(0);
    }

    private static final class CountingClient implements FxRateClient {
        private final FxSource source;
        private final String rate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingClient(FxSource source, String rate) {
            this.source = source;
            this.rate = rate;
        }

        static CountingClient returning(FxSource source, String rate) {
            return new CountingClient(source, rate);
        }

        static CountingClient failing(FxSource source) {
            return new CountingClient(source, null);
        }

        @Override
        public FxSource source() {
            return source;
        }

        @Override
        public FxRate usdToKrw() {
            calls.incrementAndGet();
            if (rate == null) {
                throw new IllegalStateException("서킷브레이커 열림");
            }
            return new FxRate("USD", "KRW", new BigDecimal(rate), source, NOW);
        }
    }
}
