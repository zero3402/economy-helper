package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.StockOutlook.Dividend;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「다음 배당」을 고르는 규칙 — <b>출처 무관</b>하게 한 곳에 있다(FMP도 예탁원도 이걸로 접는다).
 *
 * <p>행 모양은 실측이다. 미국은 FMP {@code /stable/dividends}의 {@code NVDA}(2026-09-07: 기준일
 * {@code 2026-09-10} · 지급일 {@code 2026-10-01} · 0.25), 국내는 예탁원 배당일정의 삼성전자
 * (같은 날: 기준일 {@code 20260630} · 지급일 {@code 2026/08/28} · 374원).
 */
class StockOutlookTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    private static Dividend row(String record, String pay, String amount) {
        return new Dividend(record == null ? null : LocalDate.parse(record),
                pay == null ? null : LocalDate.parse(pay),
                amount == null ? null : new BigDecimal(amount));
    }

    @Test
    @DisplayName("오늘 이후 가장 이른 기준일·지급일을 고른다 — 실측 NVDA 모양이다")
    void picksTheNearestUpcomingDates() {
        Dividend next = Dividend.nextOf(List.of(
                row("2026-09-10", "2026-10-01", "0.25"),
                row("2026-06-04", "2026-06-26", "0.25"),
                row("2026-03-11", "2026-04-01", "0.01")), TODAY);

        assertThat(next).isEqualTo(row("2026-09-10", "2026-10-01", "0.25"));
    }

    @Test
    @DisplayName("기준일은 지났고 지급일만 남았으면 지급일만 — 지난 날을 「다음」이라 부르지 않는다")
    void keepsOnlyThePayDateOnceTheRecordDateHasPassed() {
        Dividend next = Dividend.nextOf(List.of(
                row("2026-09-10", "2026-10-01", "0.25")), LocalDate.of(2026, 9, 20));

        assertThat(next).isEqualTo(row(null, "2026-10-01", "0.25"));
    }

    @Test
    @DisplayName("두 분기를 한 블록에 섞지 않는다 — 섞으면 지급일이 기준일보다 앞선 통이 나온다")
    void neverMixesTwoEvents() {
        // ⚠️ 국내 모양: 이번 분기는 기준일만 잡혔고(배당금 미정), 지난 분기는 지급만 남았다.
        //    기준일·지급일을 **각각** 「가장 이른 앞날」로 고르면 「기준일 9/30 · 지급일 8/28」이 되어
        //    지급이 기준일에 앞서는, 있을 수 없는 통이 된다 — 날씨가 「한 블록의 값은 한 예보에서
        //    나온다」로 세운 그 규칙이다. **한 건**만 든다
        Dividend next = Dividend.nextOf(List.of(
                row("2026-09-30", null, "0"),
                row("2026-06-30", "2026-08-28", "374")), LocalDate.of(2026, 8, 10));

        assertThat(next)
                .as("가장 가까운 사건은 8/28 지급이다 — 그 건의 지난 기준일(6/30)은 안 적는다")
                .isEqualTo(row(null, "2026-08-28", "374"));
    }

    @Test
    @DisplayName("가까운 확정 건이 먼 미확정 건에 가려지지 않는다 — 매년 하반기에 늘 만나는 모양이다")
    void theNearerEventWins() {
        // ⚠️ 「앞으로 올 기준일이 있으면 그 건」으로 썼다가 고쳤다. 그러면 아래에서 12/31 기준일
        //    한 줄만 나가고 **22일 뒤 들어올 375원이 사라진다.** 국내는 앞으로 180일을 보므로
        //    하반기에는 늘 미래 기준일이 창에 들어와, 문서가 근거로 든 실측 화면이 그때부터 틀렸다
        Dividend next = Dividend.nextOf(List.of(
                row("2026-06-30", "2026-09-30", "375"),
                row("2026-12-31", null, "0")), LocalDate.of(2026, 9, 8));

        assertThat(next)
                .as("실측 SK하이닉스가 이 모양이다 — 지급일 09-30·375원이 나가야 한다")
                .isEqualTo(row(null, "2026-09-30", "375"));
    }

    @Test
    @DisplayName("동점은 응답 순서로 가르지 않는다 — 더 채워진 행이 이긴다")
    void breaksTiesByCompleteness() {
        // ⚠️ min은 비길 때 **먼저 온 것**을 준다. 기준일이 같은 두 행(하나는 예비 — 지급일 공백·
        //    배당금 0)이 오면 순서만 바뀌어도 화면이 달라졌다. 「첫 행을 그냥 집지 않는다」가
        //    동점에서도 지켜져야 한다
        Dividend sparseFirst = Dividend.nextOf(List.of(
                row("2026-09-30", null, "0"),
                row("2026-09-30", "2026-11-19", "374")), TODAY);
        Dividend sparseLast = Dividend.nextOf(List.of(
                row("2026-09-30", "2026-11-19", "374"),
                row("2026-09-30", null, "0")), TODAY);

        assertThat(sparseFirst).isEqualTo(row("2026-09-30", "2026-11-19", "374"));
        assertThat(sparseLast).as("순서가 답을 바꾸면 안 된다").isEqualTo(sparseFirst);
    }

    @Test
    @DisplayName("null 원소가 섞여도 견딘다 — 공개 정적 메서드라 호출자를 믿지 않는다")
    void toleratesNullRows() {
        Dividend next = Dividend.nextOf(java.util.Arrays.asList(
                null, row("2026-09-10", "2026-10-01", "0.25"), null), TODAY);

        assertThat(next).isEqualTo(row("2026-09-10", "2026-10-01", "0.25"));
    }

    @Test
    @DisplayName("지난 날짜는 그 칸만 빠진다 — 남은 것이 없으면 블록이 통째로 없다")
    void pastDatesOnlyDropTheirOwnLine() {
        assertThat(Dividend.nextOf(List.of(
                row("2026-09-30", null, "0"),
                row("2026-06-30", "2026-08-28", "374")), LocalDate.of(2026, 10, 1)))
                .as("9/30 기준일도 8/28 지급도 지났으면 남은 것이 없다").isNull();

        assertThat(Dividend.nextOf(List.of(
                row("2026-06-30", "2026-08-28", "374")), LocalDate.of(2026, 8, 10)))
                .as("기준일 6/30은 지났고 지급 8/28만 남았다 — 기준일 줄은 빠진다")
                .isEqualTo(row(null, "2026-08-28", "374"));
    }

    @Test
    @DisplayName("지급일이 없으면 배당금은 기준일을 준 건의 것이다")
    void amountFollowsTheRecordRowWhenNothingIsDue() {
        Dividend next = Dividend.nextOf(List.of(
                row("2026-09-30", null, "361"),
                row("2026-06-30", "2026-08-28", "374")), TODAY);

        assertThat(next).isEqualTo(row("2026-09-30", null, "361"));
    }

    @Test
    @DisplayName("전부 지났으면 null — 실측 AAPL(2026-09-07)이 이 모양이다: 다음 배당이 미선언이다")
    void nullWhenEverythingHasPassed() {
        assertThat(Dividend.nextOf(List.of(
                row("2026-08-10", "2026-08-13", "0.27"),
                row("2026-05-11", "2026-05-14", "0.27")), TODAY)).isNull();
        assertThat(Dividend.nextOf(List.of(), TODAY)).isNull();
    }

    @Test
    @DisplayName("배당금 0은 값이 아니다 — 날짜는 남고 금액만 빠진다")
    void zeroAmountIsNotAValue() {
        Dividend next = Dividend.nextOf(List.of(row("2026-09-30", null, "0")), TODAY);

        assertThat(next).isEqualTo(row("2026-09-30", null, null));
    }

    @Test
    @DisplayName("오늘은 아직 앞날이다 — 기준일 당일에 그 줄이 사라지면 안 된다")
    void todayCountsAsUpcoming() {
        Dividend next = Dividend.nextOf(List.of(row("2026-09-07", "2026-09-07", "1")), TODAY);

        assertThat(next).isEqualTo(row("2026-09-07", "2026-09-07", "1"));
    }

    @Test
    @DisplayName("응답 순서에 기대지 않는다 — 첫 행을 그냥 집으면 지난 배당이 「다음」으로 나간다")
    void doesNotTrustTheOrder() {
        Dividend next = Dividend.nextOf(List.of(
                row("2026-03-11", "2026-04-01", "0.01"),
                row("2026-12-10", "2026-12-24", "0.30"),
                row("2026-09-10", "2026-10-01", "0.25")), TODAY);

        assertThat(next).isEqualTo(row("2026-09-10", "2026-10-01", "0.25"));
    }

    @Test
    @DisplayName("배당이 있으면 전망은 비어 있지 않다 — 목표가·실적발표일이 없어도 그 블록은 나간다")
    void dividendAloneMakesTheOutlookNonEmpty() {
        StockOutlook outlook = new StockOutlook(null, null, row("2026-09-10", null, "0.25"),
                StockSource.FMP, null);

        assertThat(outlook.isEmpty()).isFalse();
        assertThat(StockOutlook.none(StockSource.FMP, null).isEmpty()).isTrue();
    }
}
