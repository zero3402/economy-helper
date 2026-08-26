package io.saiden.economyhelper.market.weather.kma;

/**
 * 위경도 → 기상청 <b>격자</b>({@code nx}·{@code ny}). I/O를 모르는 순수 클래스다.
 *
 * <p>기상청 예보는 좌표를 받지 않는다 — 전국을 5km 격자로 나눈 제 좌표계를 요구한다. 변환은
 * 기상청이 공개한 람베르트 정각원추도법 식이라 <b>새 의존성도, 지점 표도 없다.</b>
 * {@code AccuLocationApi}가 지점 키를 받아 오느라 조회 한 번에 호출 두 번을 쓰는 자리와
 * 같은 문제인데, 이쪽은 계산이라 호출이 <b>0</b>이다.
 *
 * <p>검산(2026-08-26): 서울 중구 {@code (60,127)}·제주 {@code (53,38)}가 공표값과 정확히 맞고,
 * 부산 해운대는 {@code (99,75)}로 공표값 {@code (99,76)}과 한 칸 차이다(공표값의 기준점이
 * 해운대 안의 다른 지점이다).
 */
record KmaGrid(int nx, int ny) {

    /** 격자의 크기. 이 밖은 기상청이 답할 수 없다. */
    private static final int MAX_NX = 149;
    private static final int MAX_NY = 253;

    private static final double EARTH_RADIUS_KM = 6371.00877;
    private static final double GRID_KM = 5.0;
    private static final double STANDARD_LATITUDE_1 = 30.0;
    private static final double STANDARD_LATITUDE_2 = 60.0;
    private static final double ORIGIN_LONGITUDE = 126.0;
    private static final double ORIGIN_LATITUDE = 38.0;
    private static final double ORIGIN_X = 43.0;
    private static final double ORIGIN_Y = 136.0;

    private static final double DEGREE = Math.PI / 180.0;
    private static final double QUARTER_TURN = Math.PI * 0.25;

    /**
     * ⚠️ <b>격자 안이라는 것이 「국내」라는 뜻은 아니다.</b> 격자가 직사각형이라 이웃 나라가
     * 함께 들어온다 — 실측(2026-08-26): 후쿠오카 {@code (123,42)}·쓰시마 {@code (103,55)}·
     * 평양 {@code (39,158)}·블라디보스토크 {@code (135,250)}·선양 {@code (2,219)}이 모두
     * 격자 안이다. 반대로 <b>울릉도 {@code (127,127)}·독도 {@code (144,123)}·
     * 마라도 {@code (48,29)}·백령도 {@code (20,135)}는 진짜 국내</b>라 살려야 한다.
     *
     * <p>그래서 이것만으로 가르지 않는다 — {@code KmaWeatherClient.supports}가
     * <b>시간대까지</b> 함께 본다. 남한의 시간대는 {@code Asia/Seoul} 하나뿐이고 위 다섯은
     * 각각 {@code Asia/Tokyo}·{@code Asia/Pyongyang}·{@code Asia/Vladivostok}·
     * {@code Asia/Shanghai}다.
     *
     * @return 격자 밖이면 {@code null} — 「없다」를 값으로 말한다
     */
    static KmaGrid of(double latitude, double longitude) {
        double re = EARTH_RADIUS_KM / GRID_KM;
        double slat1 = STANDARD_LATITUDE_1 * DEGREE;
        double slat2 = STANDARD_LATITUDE_2 * DEGREE;
        double olon = ORIGIN_LONGITUDE * DEGREE;
        double olat = ORIGIN_LATITUDE * DEGREE;

        double sn = Math.log(Math.cos(slat1) / Math.cos(slat2))
                / Math.log(Math.tan(QUARTER_TURN + slat2 * 0.5)
                        / Math.tan(QUARTER_TURN + slat1 * 0.5));
        double sf = Math.pow(Math.tan(QUARTER_TURN + slat1 * 0.5), sn) * Math.cos(slat1) / sn;
        double ro = re * sf / Math.pow(Math.tan(QUARTER_TURN + olat * 0.5), sn);

        double ra = re * sf / Math.pow(Math.tan(QUARTER_TURN + latitude * DEGREE * 0.5), sn);
        double theta = longitude * DEGREE - olon;
        // 날짜변경선을 넘는 경도를 -π~π로 되돌린다. 안 하면 지구 반대편이 격자 안으로 들어온다
        theta = Math.IEEEremainder(theta, 2 * Math.PI);
        theta *= sn;

        int nx = (int) Math.floor(ra * Math.sin(theta) + ORIGIN_X + 0.5);
        int ny = (int) Math.floor(ro - ra * Math.cos(theta) + ORIGIN_Y + 0.5);
        if (nx < 1 || nx > MAX_NX || ny < 1 || ny > MAX_NY) {
            return null;
        }
        return new KmaGrid(nx, ny);
    }
}
