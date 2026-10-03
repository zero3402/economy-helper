package io.saiden.economyhelper.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 값을 값으로 읽는 두 자리 — <b>둘 다 실측 함정에서 뽑혀 나왔다.</b>
 *
 * <p>{@code Price}는 <b>KIS의 {@code 0.00}</b>을 막는다. 없는 지수 심볼에 에러가 아니라 0이 와서
 * ({@code DJI}·{@code DJIA} 실측) {@code null}만 보면 화면에 지수 0이 찍히고 폴백도 안 일어난다.
 *
 * <p>{@code PercentChange}는 <b>등락률의 유일한 규칙</b>이다. 식이 둘이면 반올림 횟수가 달라
 * 경계에서 {@code 0.01%p}가 갈려 한 통 안에서 본문과 캡션이 다른 숫자를 말한다.
 */
class PriceAndPercentTest {

    @Test
    @DisplayName("KIS의 0은 값이 아니다 — null만 보면 화면에 지수 0이 찍힌다")
    void treatsZeroAsMissing() {
        assertThat(Price.of(BigDecimal.ZERO)).isEmpty();
        assertThat(Price.of(new BigDecimal("0.00"))).isEmpty();
        assertThat(Price.of(null)).isEmpty();
        assertThat(Price.of(new BigDecimal("-1"))).as("음수도 시세가 아니다").isEmpty();
        assertThat(Price.of(new BigDecimal("0.01"))).contains(new Price(new BigDecimal("0.01")));
    }

    @Test
    @DisplayName("Price는 만들어졌으면 값이다 — 0·음수·null로는 아예 못 만든다")
    void priceRejectsWhatIsNotAValue() {
        assertThatThrownBy(() -> new Price(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(new BigDecimal("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PercentChange는 null을 거절한다 — 모르면 그 값 자체가 없다. 0은 「보합」이라는 값이다")
    void percentRejectsNullButKeepsZero() {
        assertThatThrownBy(() -> new PercentChange(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PercentChange(BigDecimal.ZERO).percent()).isEqualByComparingTo("0");
        assertThat(PercentChange.ofNullable(null)).isNull();
        assertThat(PercentChange.ofNullable(new BigDecimal("-1.26")).percent()).isEqualByComparingTo("-1.26");
    }

    @Test
    @DisplayName("비율은 fromRatio를 거쳐야 %가 된다 — 반올림하지 않는다")
    void fromRatioMovesRatioToPercentWithoutRounding() {
        assertThat(PercentChange.fromRatio(new BigDecimal("-0.0070571945")).percent())
                .isEqualByComparingTo("-0.70571945");
        assertThat(PercentChange.fromRatio(null)).isNull();
    }

    @Test
    @DisplayName("require는 값이 아니면 던진다 — 폴백이 일어나려면 여기서 실패해야 한다")
    void requireThrowsSoTheFallbackHappens() {
        assertThatThrownBy(() -> Price.require(BigDecimal.ZERO, "나스닥"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("나스닥");
        assertThat(Price.require(new BigDecimal("232.14"), "애플").value())
                .isEqualByComparingTo("232.14");
    }

    @Test
    @DisplayName("분모가 0이거나 없으면 등락률이 없다 — 0.00%로 찍으면 「보합」이라는 값이 된다")
    void givesNoPercentWhenThereIsNoBase() {
        assertThat(PercentChange.between(new BigDecimal("100"), BigDecimal.ZERO)).isEmpty();
        assertThat(PercentChange.between(new BigDecimal("100"), null)).isEmpty();
        assertThat(PercentChange.between(null, new BigDecimal("100"))).isEmpty();
    }

    @Test
    @DisplayName("2자리 HALF_UP — 0.005는 올린다")
    void roundsHalfUpToTwoDecimals() {
        // 100 → 100.005 는 +0.005%다. HALF_EVEN이면 0.00, HALF_UP이면 0.01
        assertThat(percent(new BigDecimal("100.005"), new BigDecimal("100")))
                .isEqualByComparingTo("0.01");
        assertThat(percent(new BigDecimal("99"), new BigDecimal("100")))
                .isEqualByComparingTo("-1.00");
        assertThat(percent(new BigDecimal("100"), new BigDecimal("100")))
                .as("보합은 0.00이고, 그건 「모른다」와 다른 값이다")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("반올림을 두 번 하지 않는다 — 8자리로 접고 다시 2자리로 접으면 0.09%가 0.10%가 된다")
    void roundsOnlyOnce() {
        // ⚠️ 이 입력이 이 테스트의 전부다. 정확한 값이 0.094999568%라서
        //    한 번에 접으면 0.09, 두 번 접으면(8자리 → 0.00095000 → ×100 → 2자리) 0.10이 된다.
        //    34567 → 34569 같은 입력은 두 방식이 **둘 다 0.01**이라 아무것도 증명하지 못한다
        assertThat(percent(new BigDecimal("11590"), new BigDecimal("11579")))
                .as("정확한 값은 0.094999568%다 — 올려서 0.10을 만들면 안 된다")
                .isEqualByComparingTo("0.09");
    }

    @Test
    @DisplayName("차트 캡션의 등락률이 본문과 같은 계산이다 — 식이 둘이면 같은 통에서 두 숫자가 나온다")
    void chartCaptionUsesTheSameRuleAsTheBody() {
        BigDecimal first = new BigDecimal("11579");
        BigDecimal last = new BigDecimal("11590");
        List<DailyBar> bars = List.of(
                new DailyBar(LocalDate.of(2026, 8, 24), first),
                new DailyBar(LocalDate.of(2026, 8, 25), last));

        assertThat(DailySeries.changePercent(bars))
                .isEqualTo(PercentChange.between(last, first).orElseThrow());
        assertThat(DailySeries.changePercent(bars).percent())
                .as("캡션도 한 번만 접는다").isEqualByComparingTo("0.09");
    }

    @Test
    @DisplayName("점이 둘 미만이면 캡션에 등락률이 없다 — 견줄 앞값이 없다")
    void givesNoPercentWithoutTwoPoints() {
        assertThat(DailySeries.changePercent(null)).isNull();
        assertThat(DailySeries.changePercent(List.of())).isNull();
        assertThat(DailySeries.changePercent(List.of(
                new DailyBar(LocalDate.of(2026, 8, 25), new BigDecimal("100"))))).isNull();
    }

    @Test
    @DisplayName("앞값이 0이어도 던지지 않는다 — 나누기가 터지는 대신 등락률만 빠진다")
    void survivesAZeroFirstBar() {
        // recent()가 0을 걸러 주므로 실제로는 안 오지만, 여기가 터지면 차트가 통째로 죽는다
        assertThat(DailySeries.changePercent(List.of(
                new DailyBar(LocalDate.of(2026, 8, 24), BigDecimal.ZERO),
                new DailyBar(LocalDate.of(2026, 8, 25), new BigDecimal("100"))))).isNull();
    }

    private static BigDecimal percent(BigDecimal latest, BigDecimal previous) {
        return PercentChange.between(latest, previous).orElseThrow().percent();
    }
}
