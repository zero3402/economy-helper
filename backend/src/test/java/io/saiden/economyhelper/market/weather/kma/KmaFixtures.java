package io.saiden.economyhelper.market.weather.kma;

import io.saiden.economyhelper.market.weather.GeoLocation;
import java.time.ZoneId;

/**
 * 기상청 테스트가 함께 쓰는 <b>실측 지점</b>들.
 *
 * <p>이 좌표들은 임의의 숫자가 아니라 <b>시간대 검사를 정당화하는 실측</b>이다 — 격자가
 * 직사각형이라 후쿠오카·평양이 안으로 들어오고, 울릉도·독도·마라도는 진짜 국내다.
 * 그래서 두 각도에서 단언한다({@code KmaGridTest}는 격자 안인지, {@code KmaWeatherClientTest}는
 * {@code supports}가 거절하는지) — <b>단언은 각자 자리에 두고 숫자만 여기 모은다.</b>
 *
 * <p>{@code KisFixtures}와 같은 자리다.
 */
final class KmaFixtures {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // 알람 지역 넷 — 전부 국내이므로 오전 6시 알람이 기상청을 탄다
    static final double MIGEUM_LATITUDE = 37.35;
    static final double MIGEUM_LONGITUDE = 127.10889;
    static final double SEOHYEON_LATITUDE = 37.3851167;
    static final double SEOHYEON_LONGITUDE = 127.1232944;
    static final double JAMSIL_LATITUDE = 37.513250;
    static final double JAMSIL_LONGITUDE = 127.100111;
    static final double SAMSEONG_LATITUDE = 37.512806;
    static final double SAMSEONG_LONGITUDE = 127.052612;

    /** ⚠️ 격자 <b>안</b>이지만 국외다 — 시간대가 가른다. */
    static final double FUKUOKA_LATITUDE = 33.5904;
    static final double FUKUOKA_LONGITUDE = 130.4017;
    static final double PYONGYANG_LATITUDE = 39.0392;
    static final double PYONGYANG_LONGITUDE = 125.7625;

    /** 격자 안이고 <b>진짜 국내</b>다 — 격자를 좁혀 가르려 하면 이쪽이 잘린다. */
    static final double ULLEUNG_LATITUDE = 37.4844;
    static final double ULLEUNG_LONGITUDE = 130.9057;
    static final double MARA_LATITUDE = 33.1120;
    static final double MARA_LONGITUDE = 126.2680;

    private KmaFixtures() {
    }

    static GeoLocation seohyeon() {
        return domestic("서현역", SEOHYEON_LATITUDE, SEOHYEON_LONGITUDE);
    }

    /** 국내 지점 — 시간대가 {@code Asia/Seoul}이다. */
    static GeoLocation domestic(String name, double latitude, double longitude) {
        return new GeoLocation(name, null, latitude, longitude, SEOUL);
    }

    /** 국외 지점 — 시간대가 다르므로 {@code supports}가 거절해야 한다. */
    static GeoLocation abroad(String name, double latitude, double longitude, String zone) {
        return new GeoLocation(name, null, latitude, longitude, ZoneId.of(zone));
    }
}
