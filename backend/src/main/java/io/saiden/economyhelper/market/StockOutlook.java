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
     * @param past       <b>이미 지난 배당인가.</b> 앞으로 올 것이 없어 <b>가장 최근에 끝난</b> 건을
     *                   대신 든 경우다 — 화면이 이름표에 그렇게 적어야 한다. 출처가 준 <b>행</b>을
     *                   담을 때는 뜻이 없으므로 {@link #row}가 거짓을 넣는다
     */
    public record Dividend(LocalDate recordDate, LocalDate payDate, BigDecimal amount,
                           boolean past) {

        /**
         * 「물었는데 배당이 없다」 — <b>값이다.</b> {@code null}로 돌려주면 스프링 캐시가
         * {@code disableCachingNullValues}로 <b>거절</b>해 {@code IllegalArgumentException}이 튀고
         * (실물 감사 2026-08-28에 전망 캐시가 그렇게 물렸다), 배당 안 주는 종목을 검색할 때마다
         * 상대를 다시 부른다. 빈 값 객체는 담긴다.
         */
        public static Dividend none() {
            return new Dividend(null, null, null, false);
        }

        /** 셋이 다 없으면 붙일 것이 없다 — 화면이 그 블록을 안 적는다. */
        public boolean isEmpty() {
            return recordDate == null && payDate == null && amount == null;
        }

        /** 출처가 준 행 하나 — 「지났나」는 {@link #nextOf}가 고를 때 정한다. */
        public static Dividend row(LocalDate recordDate, LocalDate payDate, BigDecimal amount) {
            return new Dividend(recordDate, payDate, amount, false);
        }

        /**
         * 배당 <b>한 건</b> — <b>앞으로 올 가장 가까운 사건</b>, 없으면 <b>가장 최근에 끝난 건</b>.
         *
         * <p>행마다 「앞으로 올 날짜 중 이른 것」을 사건 시각으로 잡고 그 최솟값을 고른다.
         * 지난 날짜는 그 칸만 비우므로, 기준일이 지난 건은 <b>지급일이 사건 시각</b>이 된다.
         *
         * <p>⚠️ <b>앞으로 올 것이 없으면 빈손으로 두지 않는다 — 지난 건을 든다.</b>
         * 「앞으로 올 것만」으로 뒀더니 <b>배당을 주는 종목이 분기마다 몇 주씩 빈칸</b>이었다:
         * 실측(2026-09-08) 삼성전자는 예탁원이 준 행이 기준일 {@code 20260630} · 지급
         * {@code 2026/08/28}까지뿐이고 <b>다음 기준일을 아직 안 올렸다</b>(앞으로 180일을 물어도
         * 0행이다). 그래서 8/28이 지난 뒤로는 화면에 배당이 통째로 없었다 — 신고받은 그 자리다.
         * 지난 건은 <b>{@link #past}가 참</b>이고 화면이 이름표에 그렇게 적는다: 지난 것을
         * 「다음」이라 부르지 않는다는 규칙은 <b>이름표로</b> 지킨다.
         *
         * <p>⚠️ <b>세 줄이 한 사건에서 나와야 한다.</b> 기준일과 지급일을 각각 「가장 이른 앞날」로
         * 따로 고르면 서로 다른 분기의 날짜가 한 블록에 서서 <b>지급이 기준일에 앞선다.</b>
         *
         * <p>⚠️ <b>「앞으로 올 기준일 우선」이 아니다.</b> 기준일이 있는 건을 무조건 앞세우면
         * 가까운 확정 건이 먼 미확정 건에 가려진다(창 안에 미래 기준일이 늘 있는 하반기에 특히).
         *
         * <p>⚠️ <b>동점은 응답 순서로 가르지 않는다</b> — <b>더 채워진 행</b>이 이긴다.
         *
         * @param rows  출처가 준 행들. 필드가 비어 있어도 되고 {@code null} 원소가 있어도 된다
         * @param today 그 시장의 오늘. 오늘은 아직 「앞날」이다
         * @return 날짜가 하나도 없으면 {@code null} — 화면이 그 블록을 통째로 안 적는다
         */
        public static Dividend nextOf(List<Dividend> rows, LocalDate today) {
            List<Dividend> clean = rows.stream().filter(Objects::nonNull).toList();
            Dividend upcoming = clean.stream()
                    .map(row -> new Dividend(upcomingOrNull(row.recordDate(), today),
                            upcomingOrNull(row.payDate(), today), positive(row.amount()), false))
                    .filter(row -> row.soonest() != null)
                    .min(Comparator.comparing(Dividend::soonest)
                            .thenComparing(Comparator.comparingInt(Dividend::filled).reversed()))
                    .orElse(null);
            if (upcoming != null) {
                return upcoming;
            }
            // 앞으로 올 것이 없다 — 가장 최근에 끝난 건을 든다. 날짜를 비우지 않으므로 셋이 다 나온다
            return clean.stream()
                    .map(row -> new Dividend(row.recordDate(), row.payDate(),
                            positive(row.amount()), true))
                    .filter(row -> row.latest() != null)
                    .max(Comparator.comparing(Dividend::latest)
                            .thenComparing(Comparator.comparingInt(Dividend::filled)))
                    .orElse(null);
        }

        /** 이 건이 끝난 시각 — 든 날짜 중 늦은 것. 지난 건들 중 「가장 최근」을 고르는 열쇠다. */
        private LocalDate latest() {
            if (recordDate == null) {
                return payDate;
            }
            if (payDate == null) {
                return recordDate;
            }
            return recordDate.isAfter(payDate) ? recordDate : payDate;
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
