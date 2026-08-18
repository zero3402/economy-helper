package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 환율 이중화가 실제로 이중화인지 본다 — <b>KIS → 유럽중앙은행 → 수출입은행</b>이다.
 *
 * <p>순서의 근거는 <b>사용자가 받는 값의 신선도</b>다. KIS만 하루 중에도 움직이고, 나머지 둘은
 * 하루 한 번 고시다. 그 둘 사이에서는 오전 9시 브리핑 기준으로 유럽중앙은행이 더 최근이다
 * (어제 16시 CET ≈ 어제 23시 KST vs 수출입은행 어제 11시 고시).
 *
 * <p>가장 중요한 건 <b>앞이 성공하면 뒤를 부르지 않는다</b>는 점이다. KIS는 초당 한도가 있고
 * 수출입은행은 하루 1,000회 제한이 있어, 매번 같이 부르면 폴백이 아니라 낭비다.
 */
class FxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T01:36:00Z");

    @Test
    @DisplayName("1순위가 성공하면 뒤의 둘은 호출조차 하지 않는다 — 한도가 있는 출처를 헛되이 태우지 않는다")
    void doesNotTouchFallbacksWhenPrimarySucceeds() {
        CountingClient kis = CountingClient.returning(FxSource.KIS, "1412.5");
        CountingClient frankfurter = CountingClient.returning(FxSource.FRANKFURTER, "1414.7");
        CountingClient kexim = CountingClient.returning(FxSource.KEXIM, "1415");

        // ⚠️ 주입 순서를 일부러 뒤집는다 — 이중화 순서가 빈 등록 순서에 딸려 가면 안 된다
        FxRate rate = new FxService(List.of(kexim, frankfurter, kis)).usdToKrw().orElseThrow();

        assertThat(rate.source())
                .as("코드가 정한 순서가 이긴다 — 클래스 이름을 바꾸다 순서가 뒤집히면 안 된다")
                .isEqualTo(FxSource.KIS);
        assertThat(kis.calls).hasValue(1);
        assertThat(frankfurter.calls).hasValue(0);
        assertThat(kexim.calls).hasValue(0);
    }

    @Test
    @DisplayName("KIS가 죽으면 유럽중앙은행이 받는다 — 수출입은행보다 먼저다")
    void fallsBackToFrankfurterBeforeKexim() {
        CountingClient kis = CountingClient.failing(FxSource.KIS);
        CountingClient frankfurter = CountingClient.returning(FxSource.FRANKFURTER, "1414.7");
        CountingClient kexim = CountingClient.returning(FxSource.KEXIM, "1415");

        FxRate rate = new FxService(List.of(kis, frankfurter, kexim)).usdToKrw().orElseThrow();

        assertThat(rate.source()).isEqualTo(FxSource.FRANKFURTER);
        assertThat(rate.rate()).isEqualByComparingTo("1414.7");
        assertThat(kexim.calls)
                .as("2순위가 답했으면 3순위는 부르지 않는다")
                .hasValue(0);
    }

    @Test
    @DisplayName("둘이 죽으면 수출입은행이 받는다 — 최후 보루를 남겨 두는 값이 여기서 드러난다")
    void fallsBackToKeximWhenBothAheadFail() {
        CountingClient kexim = CountingClient.returning(FxSource.KEXIM, "1415");

        FxRate rate = new FxService(List.of(CountingClient.failing(FxSource.KIS),
                CountingClient.failing(FxSource.FRANKFURTER), kexim)).usdToKrw().orElseThrow();

        assertThat(rate.source()).isEqualTo(FxSource.KEXIM);
        assertThat(kexim.calls).hasValue(1);
    }

    @Test
    @DisplayName("전부 실패하면 빈 결과 — 예외를 밖으로 던지지 않는다")
    void returnsEmptyWhenAllFail() {
        FxService service = new FxService(List.of(CountingClient.failing(FxSource.KIS),
                CountingClient.failing(FxSource.FRANKFURTER), CountingClient.failing(FxSource.KEXIM)));

        assertThat(service.usdToKrw()).isEmpty();
        assertThat(service.orNull())
                .as("부르는 쪽은 계속 가야 한다 — 못 구했다고 시세까지 막지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("선언 순서와 이중화 순서가 같아야 한다 — 화면의 출처 줄이 그 선언 순으로 정렬된다")
    void enumOrderMatchesFailoverOrder() {
        assertThat(FxSource.values())
                .containsExactly(FxSource.KIS, FxSource.FRANKFURTER, FxSource.KEXIM);
        assertThat(FxSource.KIS.intraday())
                .as("셋 중 KIS만 하루 중에도 움직인다 — 그래서 시각까지 찍는다")
                .isTrue();
        assertThat(FxSource.FRANKFURTER.intraday()).isFalse();
        assertThat(FxSource.KEXIM.intraday()).isFalse();
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
