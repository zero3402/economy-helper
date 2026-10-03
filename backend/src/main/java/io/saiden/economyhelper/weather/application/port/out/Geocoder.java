package io.saiden.economyhelper.weather.application.port.out;

import io.saiden.economyhelper.weather.domain.GeoLocation;
import java.util.Optional;

/**
 * 지명 → 좌표. 지명이 실재하는지와 좌표를 여기가 정한다 — LLM이 좌표를 지어내게 두지 않는다.
 */
public interface Geocoder {

    /**
     * @param countryCode ISO 3166-1 alpha-2. 모르면 {@code null}
     * @return 1순위 후보(이름은 상대가 준 날것). 못 찾으면 {@link Optional#empty()}
     */
    Optional<GeoLocation> find(String query, String countryCode);
}
