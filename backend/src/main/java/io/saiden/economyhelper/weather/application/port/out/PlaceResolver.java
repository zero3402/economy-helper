package io.saiden.economyhelper.weather.application.port.out;

import io.saiden.economyhelper.weather.domain.ResolvedPlace;
import java.util.Optional;

/**
 * 사용자가 친 말 → 지명과 기간. 해석만 하고 좌표는 {@link Geocoder}가 확정한다.
 */
public interface PlaceResolver {

    /**
     * @param query 사용자가 친 그대로. 다듬는 것(캐시 키)은 구현이 한다 — 해석에는 띄어쓰기와
     *              날짜 기호가 필요하다
     * @return 읽어 낸 지명과 기간. 실패하면 {@link Optional#empty()} — 호출자는 원문 그대로
     *         지오코딩을 시도한다
     */
    Optional<ResolvedPlace> resolve(String query);
}
