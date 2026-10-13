package io.saiden.economyhelper.stock.application.port.out;

import io.saiden.economyhelper.stock.domain.StockSource;

/**
 * {@link DomesticStockClient}·{@link UsStockClient}가 공유하는 한 가지 — <b>출처가 무엇이냐.</b>
 *
 * <p>이 하나 때문에 상위 인터페이스를 둔다. {@code StockService}의 폴백 루프가 국내와 미국
 * <b>한 벌</b>로 끝나려면, 실패를 기록할 때 출처 이름을 묻는 방법이 하나여야 하기 때문이다.
 * 없으면 같은 루프가 두 벌 생기고, 그중 하나만 고쳐지는 날이 온다.
 */
public interface StockClient {

    /** 화면에 밝히는 출처. {@code StockService}가 이 값으로 이중화 순서를 정한다. */
    StockSource source();

    /**
     * <b>이 출처는 그 종목을 모른다</b> — 장애가 아니다.
     *
     * <p>던지는 것은 다른 실패와 같다(그래야 다음 출처로 넘어간다). 타입을 따로 두는 이유는
     * {@code StockService}가 <b>전부 실패한 뒤</b> 「못 찾음」과 「잠시 후 다시」를 가르기 때문이다 —
     * 출처가 전부 이것으로 끝났을 때만 「못 찾음」이다. 장애를 「못 찾음」으로 답하면 사용자는
     * 다시 묻지 않는다.
     */
    class NotFound extends IllegalStateException {

        public NotFound(String message) {
            super(message);
        }
    }
}
