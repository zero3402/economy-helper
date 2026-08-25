package io.saiden.economyhelper.market.kis;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.kis.InvestOpinions.Opinion;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>국내는 우리가 접는다.</b> KIS {@code invest-opinion}은 컨센서스가 아니라 <b>발표 건</b>을
 * 준다(실측 2026-08-21: 삼성전자 두 달에 12행 · SK하이닉스 88행). FMP가 미국에서 이미 합쳐
 * 주는 값을 국내에서는 이 클래스가 만드는 셈이고, 그래야 두 시장의 숫자가 같은 뜻이 된다.
 *
 * <p>그 접는 규칙이 문서에는 함정으로 적혀 있는데 <b>테스트가 하나도 없었다.</b>
 */
class InvestOpinionsTest {

    @Test
    @DisplayName("증권사마다 최신 하나만 남긴다 — 발표 건을 다 평균하면 많이 낸 곳이 두 표를 갖는다")
    void keepsOnlyTheLatestPerBroker() {
        // 키움이 세 번 냈다. 그대로 평균하면 키움의 옛 목표가가 셋 중 둘을 차지한다
        List<Opinion> rows = List.of(
                new Opinion("키움증권", "20260801", new BigDecimal("100000")),
                new Opinion("키움증권", "20260815", new BigDecimal("200000")),
                new Opinion("키움증권", "20260810", new BigDecimal("150000")),
                new Opinion("삼성증권", "20260812", new BigDecimal("400000")));

        assertThat(InvestOpinions.averageTargetOf(rows))
                .as("키움 200,000(최신)과 삼성 400,000의 평균")
                .contains(new BigDecimal("300000"));
    }

    @Test
    @DisplayName("목표가 0은 값이 아니다 — 평균에 넣으면 실제보다 낮은 목표가가 나간다")
    void neverAveragesAZeroTarget() {
        // 목표가를 안 낸 발표가 0으로 온다. 시세에서 Price.positive가 막는 그 함정과 같다
        List<Opinion> rows = List.of(
                new Opinion("키움증권", "20260815", new BigDecimal("300000")),
                new Opinion("삼성증권", "20260815", BigDecimal.ZERO),
                new Opinion("NH투자증권", "20260815", new BigDecimal("400000")));

        assertThat(InvestOpinions.averageTargetOf(rows))
                .as("0을 세면 (300000+0+400000)/3 = 233,333이 되어 실제보다 한참 낮다")
                .contains(new BigDecimal("350000"));
    }

    @Test
    @DisplayName("음수 목표가도 뺀다 — signum > 0만 값이다")
    void dropsNegativeTargets() {
        List<Opinion> rows = List.of(
                new Opinion("키움증권", "20260815", new BigDecimal("-100")),
                new Opinion("삼성증권", "20260815", new BigDecimal("300000")));

        assertThat(InvestOpinions.averageTargetOf(rows)).contains(new BigDecimal("300000"));
    }

    @Test
    @DisplayName("셀 수 있는 것이 하나도 없으면 빈 값이다 — 그건 값이고 실패가 아니다")
    void givesNothingWhenNoBrokerHasATarget() {
        assertThat(InvestOpinions.averageTargetOf(List.of())).isEmpty();
        assertThat(InvestOpinions.averageTargetOf(List.of(
                new Opinion("키움증권", "20260815", BigDecimal.ZERO)))).isEmpty();
        assertThat(InvestOpinions.averageTargetOf(List.of(
                new Opinion("키움증권", "20260815", null)))).isEmpty();
    }

    @Test
    @DisplayName("증권사 이름이 없는 행은 버린다 — 누가 낸 것인지 모르면 셀 수 없다")
    void dropsRowsWithoutABroker() {
        List<Opinion> rows = List.of(
                new Opinion(null, "20260815", new BigDecimal("999999")),
                new Opinion("  ", "20260815", new BigDecimal("999999")),
                new Opinion("키움증권", "20260815", new BigDecimal("300000")));

        assertThat(InvestOpinions.averageTargetOf(rows)).contains(new BigDecimal("300000"));
    }

    @Test
    @DisplayName("날짜가 없는 행은 있는 쪽에 진다 — 언제 낸 것인지 모르면 최신이라 할 수 없다")
    void prefersTheRowThatHasADate() {
        assertThat(InvestOpinions.averageTargetOf(List.of(
                new Opinion("키움증권", null, new BigDecimal("100000")),
                new Opinion("키움증권", "20260801", new BigDecimal("300000")))))
                .contains(new BigDecimal("300000"));
        // 순서를 뒤집어도 같아야 한다 — merge의 인자 순서에 기대면 안 된다
        assertThat(InvestOpinions.averageTargetOf(List.of(
                new Opinion("키움증권", "20260801", new BigDecimal("300000")),
                new Opinion("키움증권", null, new BigDecimal("100000")))))
                .contains(new BigDecimal("300000"));
    }

    @Test
    @DisplayName("평균은 원 단위로 HALF_UP — 목표가에 소수점을 적을 일이 없다")
    void roundsTheAverageHalfUpToWholeWon() {
        // (100001 + 100002) / 2 = 100001.5 → 100002
        assertThat(InvestOpinions.averageTargetOf(List.of(
                new Opinion("키움증권", "20260815", new BigDecimal("100001")),
                new Opinion("삼성증권", "20260815", new BigDecimal("100002")))))
                .contains(new BigDecimal("100002"));
    }
}
