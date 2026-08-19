package io.saiden.economyhelper.market;

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
}
