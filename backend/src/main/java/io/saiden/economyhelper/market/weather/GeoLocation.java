package io.saiden.economyhelper.market.weather;

import java.time.ZoneId;

/**
 * 날씨를 조회할 한 지점.
 *
 * <p><b>이름은 지오코딩이 확정한 것을 담는다.</b> 사용자가 친 말({@code 분당 서현역})을 그대로
 * 화면에 올리면 실제로 조회한 지점과 어긋날 수 있다 — LLM이 {@code 성남}으로 옮기고 지오코딩이
 * {@code 성남시}를 찾았다면 화면에도 {@code 성남시}가 적혀야 어디 날씨인지 검산이 된다.
 *
 * <p><b>시간대를 함께 들고 다닌다.</b> 일일 예보는 "그 지역의 하루"로 잘려야 한다.
 * 부에노스아이레스를 KST 달력으로 자르면 남의 하루가 둘로 쪼개지는데, 이건 뉴스 신선도에서
 * 이미 한 번 겪어 고친 문제다. Open-Meteo가 {@code timezone=auto}로 돌려주는 값을 그대로 받는다.
 *
 * @param name      지오코딩이 확정한 지명. 알람은 설정에 적은 역 이름을 그대로 쓴다
 * @param country   나라 이름. {@code language=ko}로 물어 한국어로 돌아온다({@code 아르헨티나}).
 *                  알 수 없으면 {@code null}이고 화면에서 자리째 빠진다
 * @param latitude  북위 +, 남위 −
 * @param longitude 동경 +, 서경 −
 * @param zone      이 지점의 시간대. 하루의 경계가 여기서 정해진다
 */
public record GeoLocation(String name, String country,
                          double latitude, double longitude, ZoneId zone) {

    /**
     * 화면에 쓸 이름 — {@code 부에노스아이레스, 아르헨티나}.
     *
     * <p>나라를 붙이는 이유는 같은 지명이 여러 나라에 있기 때문이다(실측: {@code Buenos Aires}가
     * 아르헨티나·니카라과·파나마에 있다). 나라를 모르면 지명만 적는다 — 빈 쉼표를 남기지 않는다.
     */
    public String displayName() {
        return country == null || country.isBlank() ? name : name + ", " + country;
    }
}
