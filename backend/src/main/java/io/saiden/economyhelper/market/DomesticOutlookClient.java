package io.saiden.economyhelper.market;

import java.util.Optional;

/**
 * 국내 종목의 전망 — <b>이중화 상대가 없다.</b>
 *
 * <p>그래서 {@link StockClient}들과 달리 예외가 아니라 빈 값으로 실패한다. 다음에 시도할 곳이
 * 없으므로 던져 봐야 부르는 쪽이 삼킬 뿐이고, 무엇보다 <b>의견이 아예 없는 종목</b>과
 * 조회 실패가 화면에서 같은 결과(그 줄이 없음)이기 때문이다.
 */
public interface DomesticOutlookClient {

    /** @param code 6자리 종목코드 */
    Optional<StockOutlook> outlook(String code);
}
