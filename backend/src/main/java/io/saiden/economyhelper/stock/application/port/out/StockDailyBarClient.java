package io.saiden.economyhelper.stock.application.port.out;

import io.saiden.economyhelper.shared.domain.DailyBar;
import java.util.List;

/**
 * 차트용 일봉 — 국내 종목·국내 지수·미국 종목.
 *
 * <p>시세 사슬({@link DomesticStockClient}·{@link UsStockClient})과 따로 둔다 — 일봉은 이중화되지
 * 않는다(한 출처가 한 호출로 준다). 실패는 삼키지 않고 던진다 — 「차트만 빼고 보낸다」는 부르는 쪽이 정한다.
 */
public interface StockDailyBarClient {

    /** @param code 국내 종목코드 {@code 005930} */
    List<DailyBar> dailyBars(String code);

    /** @param name 국내 지수 이름(설정의 지수 표 키) */
    List<DailyBar> dailyBarsOfIndex(String name);

    /** @param symbol 미국 종목·지수 심볼 */
    List<DailyBar> dailyBarsOfUs(String symbol);
}
