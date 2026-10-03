package io.saiden.economyhelper.crypto.application.port.out;

import io.saiden.economyhelper.crypto.domain.UpbitMarket;
import io.saiden.economyhelper.crypto.domain.UpbitTicker;
import io.saiden.economyhelper.shared.domain.DailyBar;
import java.util.List;

/**
 * 업비트 칸 — 원화 시세·마켓 목록·일봉.
 *
 * <p>바이낸스({@link BinanceClient})와 한 포트로 합치지 않는다. 화면이 두 거래소를 나란히 적고
 * 김치 프리미엄을 내므로 둘은 폴백 관계가 아니라 <b>각자의 칸</b>이다.
 * 실패는 삼키지 않고 던진다 — 알릴지는 호출자가 정한다.
 */
public interface UpbitClient {

    /** 원화 마켓 전체 목록. 이름 매칭({@link io.saiden.economyhelper.crypto.domain.UpbitMarketIndex})의 재료다. */
    List<UpbitMarket> krwMarkets();

    /** 여러 마켓의 시세를 <b>한 번에</b>. 비어 있으면 호출하지 않는다. */
    List<UpbitTicker> tickers(List<String> markets);

    /** 차트용 일봉. 상장 직후 코인은 요청한 개수보다 적게 온다 — 실패가 아니다. */
    List<DailyBar> dailyBars(String market);
}
