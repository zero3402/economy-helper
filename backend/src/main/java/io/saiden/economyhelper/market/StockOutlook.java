package io.saiden.economyhelper.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 한 종목에 대한 <b>전망</b> — 지금 얼마인가({@link StockQuote}) 옆에 붙는 값이다.
 *
 * <p><b>둘이 따로 논다.</b> 목표주가와 실적발표일은 출처가 갈리고 어떤 것은 아예 없다 —
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
 * @param source       조회처. 시세 출처와 다를 수 있어 화면이 따로 밝힌다
 * @param at           조회 시각
 */
public record StockOutlook(LocalDate earningsDate, BigDecimal targetPrice,
                           StockSource source, Instant at) {

    /** 아무 값도 없는 전망 — 테스트와 「전망을 묻지 않는」 자리가 쓴다. */
    public static final StockOutlook NONE = new StockOutlook(null, null, null, null);

    /**
     * 「이 출처가 이 시각에 물었는데 아무것도 없었다」 — <b>값이다.</b> {@code Optional.empty()}로 돌려주던
     * 동안 스프링이 그것을 {@code null}로 벗겨 캐시에 담지 못했고, 전망 없는 종목(ETF 전부)마다 조회가
     * KIS 간격 1초·FMP 2회를 다시 썼다. 빈 값 객체는 담긴다.
     */
    public static StockOutlook none(StockSource source, Instant at) {
        return new StockOutlook(null, null, source, at);
    }

    /** 하나도 못 구했으면 붙일 것이 없다 — 그때는 아예 안 붙인다. */
    public boolean isEmpty() {
        return earningsDate == null && targetPrice == null;
    }
}
