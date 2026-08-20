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
 * <p><b>이름은 지오코딩이 준 것을 날것으로 담는다.</b> 로마자로 올 때 한국어로 바꾸는 판단은
 * {@link #labelledFor}가 <b>읽을 때</b> 한다 — 담을 때 하지 않는 이유가 실측에 있다(그 메서드 참고).
 *
 * @param name      지오코딩이 확정한 지명, 날것 그대로. 알람은 설정에 적은 역 이름을 그대로 쓴다
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

    /**
     * 로마자로 온 지명을 <b>물어본 한국어 지명으로</b> 바꾼다.
     *
     * <p>{@code language=ko}로 불러도 일부는 로마자로 온다 — 실측(2026-08-19)으로
     * {@code 제주시}를 찾으면 이름이 {@code Jejudo}였다. 그대로 쓰면 국내 종목·코인이 전부
     * 한글인 화면에 지명만 로마자로 튄다. 물어본 쪽은 LLM이 다듬은 한국어 행정명이라
     * 사용자가 알아볼 수 있다. <b>실재와 좌표는 지오코딩이 확정한 그대로고 바뀌는 것은 표기뿐이다.</b>
     *
     * <p>⚠️ <b>이 판단이 담을 때가 아니라 읽을 때 있는 이유</b>는 지오코딩 결과가 30일 캐시에
     * 들어가기 때문이다. 담을 때 정하면 파생된 표기가 캐시에 굳어 규칙을 고쳐도 안 따라온다.
     * 매번 만들면 규칙을 고치는 순간 옛 항목까지 함께 낫는다({@code CLAUDE.md}의 그 사고).
     *
     * @param queried 지오코딩에 넘긴 지명. 비어 있으면 바꾸지 않는다
     */
    public GeoLocation labelledFor(String queried) {
        if (hasHangul(name) || queried == null || queried.isBlank()) {
            return this;
        }
        return new GeoLocation(queried.strip(), country, latitude, longitude, zone);
    }

    /** 한글이 한 글자라도 있는가. 섞여 와도 한글 표기로 본다. */
    private static boolean hasHangul(String text) {
        return text != null && text.codePoints()
                .anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HANGUL);
    }
}
