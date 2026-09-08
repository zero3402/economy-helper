package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 한 종목에 대한 <b>전망</b> — 지금 얼마인가({@link StockQuote}) 옆에 붙는 값이다.
 *
 * <p><b>셋이 따로 논다</b>(배당은 그 안에서 다시 셋이라 화면 블록은 최대 다섯이다).
 * 목표주가·실적발표일·배당은 출처가 갈리고 어떤 것은 아예 없다 —
 * 국내에는 무료 실적발표일 출처가 없고, FMP 무료 티어는 심볼별 허용목록이라 {@code ORCL}이
 * 402다(실측 2026-08-20). 그래서 <b>필드마다 {@code null}일 수 있고, 없는 것은 줄을 안 적는다.</b>
 * {@code 0}으로 찍으면 「목표가 0원」이라는 <b>값</b>이 되어 모른다는 뜻이 아니게 된다.
 *
 * <p><b>보충이지 폴백이 아니다.</b> 전망 조회가 실패해도 시세는 그대로 나간다 —
 * {@code WeatherService.withHalvesHours}가 강수 시각을 다루는 방식과 같은 자리다.
 *
 * <p>⚠️ <b>투자의견은 담지 않는다.</b> 예전에는 「매수 (111곳)」 줄이 있었고 그것을 위해
 * 국내는 증권사가 쓴 글자를 정규화하고 미국은 FMP {@code grades-consensus}를 심볼마다
 * 한 번 더 불렀다. <b>요구가 걷어내는 쪽으로 바뀌어 그 둘을 함께 지웠다</b> — 화면에서만
 * 빼고 조회를 남기면 심볼당 호출 하나가 아무도 안 보는 값에 쓰인다.
 *
 * @param earningsDate 다음 실적발표 예정일. <b>미국에만 있다</b> — 국내는 언제나 {@code null}
 * @param targetPrice  목표주가. 통화는 {@link StockQuote#currency()}를 따른다
 * @param dividend     다음 배당(기준일·지급일·배당금). 두 시장 다 있다 — 없으면 {@code null}
 * @param source       조회처. 시세 출처와 다를 수 있어 화면이 따로 밝힌다
 * @param at           조회 시각
 */
public record StockOutlook(LocalDate earningsDate, BigDecimal targetPrice, Dividend dividend,
                           StockSource source, Instant at) {

    /** 아무 값도 없는 전망 — 테스트와 「전망을 묻지 않는」 자리가 쓴다. */
    public static final StockOutlook NONE = new StockOutlook(null, null, null, null, null);

    /**
     * 「이 출처가 이 시각에 물었는데 아무것도 없었다」 — <b>값이다.</b> {@code Optional.empty()}로 돌려주던
     * 동안 스프링이 그것을 {@code null}로 벗겨 캐시에 담지 못했고, 전망 없는 종목마다 조회가
     * KIS 간격 1~2초·FMP 3회를 다시 썼다. 빈 값 객체는 담긴다.
     */
    public static StockOutlook none(StockSource source, Instant at) {
        return new StockOutlook(null, null, null, source, at);
    }

    /** 하나도 못 구했으면 붙일 것이 없다 — 그때는 아예 안 붙인다. */
    public boolean isEmpty() {
        return earningsDate == null && targetPrice == null && dividend == null;
    }

    /**
     * 배당 한 건 — 출처가 준 행 하나이기도 하고, 그 행들을 접은 <b>「다음 배당」</b>이기도 하다.
     *
     * <p><b>배당락일이 아니라 기준일이다.</b> 두 출처가 다 기준일을 <b>직접</b> 준다(FMP {@code recordDate} ·
     * 예탁원 {@code record_date}). 배당락일은 기준일의 <b>전 거래일</b>이라 휴장일 달력이 있어야 맞게
     * 나오는데(12/31 기준일이면 락일은 12/30이 아니라 12/29다 — 12/31이 휴장이다) 우리는 그 달력이 없다.
     * 지어내면 틀린 날이 화면에 나가므로, 있는 값을 제 이름으로 적는다.
     *
     * <p><b>필드마다 {@code null}일 수 있다.</b> 기준일은 지났고 지급일만 남은 분기가 흔하고, 국내는
     * 기준일이 먼저 잡히고 배당금·지급일이 뒤에 정해진다. 화면은 있는 줄만 적는다.
     *
     * @param recordDate 배당기준일 — 이날 주주명부에 올라 있어야 받는다. 그 시장의 달력이다
     * @param payDate    배당금 지급일
     * @param amount     주당 배당금. 통화는 {@link StockQuote#currency()}를 따른다. {@code 0}은 값이 아니다
     */
    public record Dividend(LocalDate recordDate, LocalDate payDate, BigDecimal amount) {

        /**
         * 다음 배당 <b>한 건</b> — <b>가장 가까운 사건</b>이다.
         *
         * <p>행마다 「앞으로 올 날짜 중 이른 것」을 사건 시각으로 잡고 그 최솟값을 고른다.
         * 지난 날짜는 그 칸만 비우므로, 기준일이 지난 건은 <b>지급일이 사건 시각</b>이 된다.
         *
         * <p>⚠️ <b>세 줄이 한 사건에서 나와야 한다.</b> 기준일과 지급일을 각각 「가장 이른 앞날」로
         * 따로 고르면 <b>서로 다른 분기의 날짜가 한 블록에 선다</b> — 이번 분기 기준일(9/30)과 지난
         * 분기 지급일(8/28)이 함께 잡히면 화면이 「기준일 9/30 · 지급일 8/28」이 되어 <b>지급이
         * 기준일에 앞서는</b>, 있을 수 없는 통이 된다. 날씨의 「한 블록의 강수 값은 한 예보에서
         * 나온다」와 같은 자리다.
         *
         * <p>⚠️ <b>「앞으로 올 기준일 우선」이 아니다 — 그렇게 썼다가 고쳤다.</b> 기준일이 있는 건을
         * 무조건 앞세우면 <b>가까운 확정 건이 먼 미확정 건에 가려진다</b>: 창 안에
         * {@code (기준 06-30, 지급 09-30, 375원)}과 {@code (기준 12-31, 지급 미정)}이 있을 때
         * 12-31 한 줄만 나가고 <b>22일 뒤 들어올 375원이 사라졌다.</b> 그리고 국내는 앞으로 180일을
         * 보므로 <b>매년 하반기에 늘 그 모양이 된다</b>(문서가 근거로 든 실측 화면이 그때부터 틀린다).
         *
         * <p>⚠️ <b>동점은 응답 순서로 가르지 않는다.</b> {@code min}은 비길 때 먼저 온 것을 주는데,
         * 기준일이 같은 두 행(하나는 예비 — 지급일 공백·배당금 {@code 0})이 오면 <b>순서만 바뀌어도
         * 화면이 달라진다.</b> 그래서 <b>더 채워진 행</b>을 고른다 — 「첫 행을 그냥 집지 않는다」가
         * 이 자리에서도 지켜져야 한다.
         *
         * <p>배당금 {@code 0}·{@code null}은 「아직 안 정해졌다」이므로 그 줄만 빠진다.
         *
         * @param rows  출처가 준 행들. 필드가 비어 있어도 되고 {@code null} 원소가 있어도 된다
         * @param today 그 시장의 오늘. 오늘은 아직 「앞날」이다
         * @return 앞으로 올 날짜가 하나도 없으면 {@code null} — 화면이 그 블록을 통째로 안 적는다
         */
        public static Dividend nextOf(List<Dividend> rows, LocalDate today) {
            return rows.stream()
                    .filter(Objects::nonNull)
                    .map(row -> new Dividend(upcomingOrNull(row.recordDate(), today),
                            upcomingOrNull(row.payDate(), today), positive(row.amount())))
                    .filter(row -> row.soonest() != null)
                    .min(Comparator.comparing(Dividend::soonest)
                            .thenComparing(Comparator.comparingInt(Dividend::filled).reversed()))
                    .orElse(null);
        }

        /** 이 건의 사건 시각 — 남은 날짜 중 이른 것. 둘 다 지났으면 {@code null}이라 고를 대상이 아니다. */
        private LocalDate soonest() {
            if (recordDate == null) {
                return payDate;
            }
            if (payDate == null) {
                return recordDate;
            }
            // 정상이면 기준일이 앞이지만, 출처가 뒤집어 줘도 「이른 것」이 사건 시각이다
            return recordDate.isBefore(payDate) ? recordDate : payDate;
        }

        /** 채워진 칸 수 — 동점일 때 <b>덜 아는 행이 이기지 않게</b> 한다. */
        private int filled() {
            return (recordDate == null ? 0 : 1) + (payDate == null ? 0 : 1) + (amount == null ? 0 : 1);
        }

        private static LocalDate upcomingOrNull(LocalDate date, LocalDate today) {
            return date == null || date.isBefore(today) ? null : date;
        }

        /** {@code 0}은 배당금이 아니다 — 「배당금 0원」은 모른다는 뜻이 아니라 값이다. */
        private static BigDecimal positive(BigDecimal amount) {
            return amount == null || amount.signum() <= 0 ? null : amount;
        }
    }
}
