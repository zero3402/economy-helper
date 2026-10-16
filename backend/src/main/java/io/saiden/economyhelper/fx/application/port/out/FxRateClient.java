package io.saiden.economyhelper.fx.application.port.out;

import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.fx.domain.FxSource;

/**
 * 환율 출처 하나 — 구현이 셋이다({@code KisFxClient}·{@code FrankfurterFxClient}·{@code KeximFxClient}).
 *
 * <p>환율 이중화가 이 인터페이스와 {@code FxService}의 순서 목록으로 구현된다.
 *
 * <p><b>실패는 삼키지 않고 던진다.</b> 다음 출처로 넘어갈지는 {@code FxService}가 정한다 —
 * 여기서 빈 값을 돌려주면 "실패"와 "값이 없음"을 구별할 수 없다.
 */
public interface FxRateClient {

    /** 이 클라이언트가 대표하는 출처. 로그와 메시지에 쓴다. */
    FxSource source();

    /**
     * 원/달러 현재 환율.
     *
     * @throws RuntimeException 조회 실패. 서킷브레이커가 이걸 세고, {@code FxService}가 폴백한다
     */
    FxRate usdToKrw();
}
