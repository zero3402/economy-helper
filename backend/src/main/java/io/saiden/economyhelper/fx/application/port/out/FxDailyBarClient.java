package io.saiden.economyhelper.fx.application.port.out;

import io.saiden.economyhelper.shared.domain.DailyBar;
import java.util.List;

/**
 * 원/달러 일봉(차트용).
 *
 * <p>시세 포트({@link FxRateClient})와 따로 둔다 — 시세는 3단 이중화이고 일봉은 출처 하나다.
 * 한 포트에 묶으면 「차트도 이중화된다」로 읽힌다. 실패는 삼키지 않고 던진다.
 */
public interface FxDailyBarClient {

    List<DailyBar> dailyBars();
}
