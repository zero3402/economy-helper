package io.saiden.economyhelper.market;

/**
 * 미국 종목의 전망(목표주가·실적발표일·배당) — <b>이중화 상대가 없다</b>
 * ({@link DomesticOutlookClient}와 같은 이유).
 *
 * <p>⚠️ FMP 무료 티어는 <b>심볼별 허용목록</b>이라 시세와 똑같이 {@code ORCL}·{@code PATH}가
 * 402다(실측 2026-08-20). 배당도 같은 목록에 걸린다 — {@code SCHD}가 402였다(실측 2026-09-07).
 * 즉 이 자리는 되는 종목에만 값이 붙는다 — 그것을 숨기지 않고 그 종목의 줄만 빠뜨린다.
 *
 * <p><b>심볼당 호출이 셋이다</b>(엔드포인트가 셋이고 무료는 배치가 막혔다). 하루 250회에서
 * 심볼 하나가 3회를 쓰므로 12시간 캐시가 그 한도의 실질 방어다.
 */
public interface UsOutlookClient {

    /** @return 전망. {@code null}이 아니다 — 비면 {@link StockOutlook#isEmpty()}. 실패는 던진다 */
    StockOutlook outlook(String symbol);
}
