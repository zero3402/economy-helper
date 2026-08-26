package io.saiden.economyhelper.market.weather.kma;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 위경도 → 격자. <b>공표값과 맞는지</b>, 그리고 <b>격자만으로는 국내를 못 가른다는 것</b>을
 * 못 박는다. 뒤쪽이 더 중요하다 — 그 사실을 모르면 {@code supports}에서 시간대 검사를
 * 빼게 되고, 그러면 후쿠오카 날씨를 기상청에 묻는다.
 */
class KmaGridTest {

    @Test
    @DisplayName("공표된 격자와 맞는다 — 서울 중구와 제주는 정확히 같다")
    void matchesThePublishedGrid() {
        assertThat(KmaGrid.of(37.5665, 126.9780)).isEqualTo(new KmaGrid(60, 127));
        assertThat(KmaGrid.of(33.4996, 126.5312)).isEqualTo(new KmaGrid(53, 38));
    }

    @Test
    @DisplayName("알람 지역 넷이 모두 격자 안이다 — 오전 6시 알람이 기상청을 타는 조건이다")
    void coversTheAlarmLocations() {
        assertThat(KmaGrid.of(KmaFixtures.MIGEUM_LATITUDE, KmaFixtures.MIGEUM_LONGITUDE)).isEqualTo(new KmaGrid(62, 122));
        assertThat(KmaGrid.of(KmaFixtures.SEOHYEON_LATITUDE, KmaFixtures.SEOHYEON_LONGITUDE)).isEqualTo(new KmaGrid(62, 123));
        assertThat(KmaGrid.of(KmaFixtures.JAMSIL_LATITUDE, KmaFixtures.JAMSIL_LONGITUDE)).isEqualTo(new KmaGrid(62, 126));
        assertThat(KmaGrid.of(KmaFixtures.SAMSEONG_LATITUDE, KmaFixtures.SAMSEONG_LONGITUDE)).isEqualTo(new KmaGrid(61, 126));
    }

    @Test
    @DisplayName("먼 나라는 격자 밖이다 — 파리·뉴욕·상하이")
    void rejectsFarAwayPlaces() {
        assertThat(KmaGrid.of(48.8566, 2.3522)).as("파리").isNull();
        assertThat(KmaGrid.of(40.7128, -74.0060)).as("뉴욕").isNull();
        assertThat(KmaGrid.of(31.2304, 121.4737)).as("상하이").isNull();
        assertThat(KmaGrid.of(34.6937, 135.5023)).as("오사카").isNull();
    }

    @Test
    @DisplayName("⚠️ 이웃 나라는 격자 안이다 — 그래서 격자만으로 「국내」를 정하면 안 된다")
    void doesNotByItselfMeanDomestic() {
        // 실측(2026-08-26): 격자가 직사각형이라 이 넷이 다 들어온다.
        // KmaWeatherClient.supports가 시간대를 함께 보는 이유가 정확히 이것이다 —
        // 이 단언이 깨지는 날은 격자가 좁아진 것이고, 그때도 시간대 검사는 남아야 한다
        assertThat(KmaGrid.of(KmaFixtures.FUKUOKA_LATITUDE, KmaFixtures.FUKUOKA_LONGITUDE)).as("후쿠오카").isNotNull();
        assertThat(KmaGrid.of(KmaFixtures.PYONGYANG_LATITUDE, KmaFixtures.PYONGYANG_LONGITUDE)).as("평양").isNotNull();
        assertThat(KmaGrid.of(43.1198, 131.8869)).as("블라디보스토크").isNotNull();
        assertThat(KmaGrid.of(41.8057, 123.4315)).as("선양").isNotNull();
    }

    @Test
    @DisplayName("먼 섬도 격자 안이다 — 격자를 좁혀 국내를 가르려 하면 이쪽이 잘린다")
    void keepsRemoteDomesticIslands() {
        assertThat(KmaGrid.of(KmaFixtures.ULLEUNG_LATITUDE, KmaFixtures.ULLEUNG_LONGITUDE)).as("울릉도").isNotNull();
        assertThat(KmaGrid.of(37.2429, 131.8664)).as("독도").isNotNull();
        assertThat(KmaGrid.of(KmaFixtures.MARA_LATITUDE, KmaFixtures.MARA_LONGITUDE)).as("마라도").isNotNull();
        assertThat(KmaGrid.of(37.9646, 124.6304)).as("백령도").isNotNull();
    }
}
