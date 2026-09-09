package io.saiden.economyhelper.market;

/**
 * 미국 종목의 <b>배당</b> — <b>이중화 상대가 없다</b>({@link UsOutlookClient}와 같은 이유).
 *
 * <p><b>왜 전망에서 갈렸나.</b> 목표가·실적발표일은 FMP가 주는데 <b>배당은 FMP로 못 얻는 심볼이
 * 많다</b> — 무료 티어가 심볼별 허용목록이라 실측(2026-09-08) 열다섯 중 다섯이 402였고
 * ({@code ORCL}·{@code SNOW}·{@code SCHD}·{@code QQQ}·{@code SOXL}) 그것이 「배당이 안 나온다」로
 * 신고됐다. 구현(Polygon)은 그 심볼들을 다 주고 {@code AAPL}에서 FMP와 값이 일치했다.
 *
 * <p>그래서 <b>한 값에 한 출처</b>라는 규칙은 그대로 두고 출처만 갈랐다. 합치는 것은
 * {@code StockService}이고, 둘을 <b>따로 삼킨다</b> — 배당만 있는 답과 배당만 없는 답이 둘 다 정상이다.
 */
public interface UsDividendClient {

    /**
     * @param symbol {@code SCHD}. 지수는 부르지 않는다 — 지수에는 배당이 없다
     * @return 고른 한 건({@link StockOutlook.Dividend#nextOf}의 규칙).
     *         배당이 없으면 <b>{@code null}이 아니라</b> {@link StockOutlook.Dividend#none()}이다 —
     *         빈 것도 값이라 캐시된다
     * @throws RuntimeException 조회가 실패하면 던진다. 삼키는 것은 {@code StockService}이고,
     *                          그래야 브레이커가 실패를 먼저 센다
     */
    StockOutlook.Dividend dividend(String symbol);
}
