package io.saiden.economyhelper.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeoLocationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("로마자로 온 지명은 물어본 한국어 지명으로 바꾼다 — 좌표는 그대로다")
    void relabelsARomanizedNameWithTheKoreanQuery() {
        GeoLocation found = new GeoLocation("Jejudo", "대한민국", 33.5, 126.5, SEOUL);

        GeoLocation labelled = found.labelledFor(" 제주시 ");

        assertThat(labelled.name()).isEqualTo("제주시");
        assertThat(labelled.latitude()).isEqualTo(33.5);
        assertThat(labelled.longitude()).isEqualTo(126.5);
    }

    @Test
    @DisplayName("한글이 아닌 질의로는 바꾸지 않는다 — 지오코딩이 확정한 이름을 사용자 철자로 덮지 않는다")
    void keepsTheConfirmedNameWhenTheQueryIsNotKorean() {
        GeoLocation found = new GeoLocation("Tokyo", "일본", 35.7, 139.7, ZoneId.of("Asia/Tokyo"));

        assertThat(found.labelledFor("tokyo").name()).isEqualTo("Tokyo");
    }

    @Test
    @DisplayName("이미 한글인 지명은 그대로 둔다")
    void keepsAKoreanName() {
        GeoLocation found = new GeoLocation("성남시", "대한민국", 37.4, 127.1, SEOUL);

        assertThat(found.labelledFor("성남")).isSameAs(found);
    }
}
