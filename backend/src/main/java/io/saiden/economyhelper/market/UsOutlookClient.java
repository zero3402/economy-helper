package io.saiden.economyhelper.market;

/**
 * 미국 종목의 전망 — <b>이중화 상대가 없다</b>({@link DomesticOutlookClient}와 같은 이유).
 *
 * <p>⚠️ FMP 무료 티어는 <b>심볼별 허용목록</b>이라 시세와 똑같이 {@code ORCL}·{@code PATH}가
 * 402다(실측 2026-08-20). 즉 이 자리는 되는 종목에만 값이 붙는다 — 그것을 숨기지 않고
 * 그 종목의 줄만 빠뜨린다.
 */
public interface UsOutlookClient {

    /** @return 전망. {@code null}이 아니다 — 비면 {@link StockOutlook#isEmpty()}. 실패는 던진다 */
    StockOutlook outlook(String symbol);
}
