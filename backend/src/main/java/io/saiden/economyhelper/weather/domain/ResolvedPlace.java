package io.saiden.economyhelper.weather.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 사용자가 친 말에서 읽어 낸 <b>지명과 기간</b> — 해석기가 내고 {@code WeatherFacade}가 편다.
 *
 * <p><b>문자열은 이미 다듬어져 온다.</b> LLM이 준 {@code null}·빈 문자열·{@code "null"} 리터럴은
 * 해석기가 담기 전에 {@code null}로 떨어뜨린다 — 그래서 여기서는 {@code null}만 보면 된다.
 *
 * @param query      지오코딩에 넘길 지명. 지역을 못 읽었으면 {@code null}
 * @param country    ISO 3166-1 alpha-2. 같은 지명이 여러 나라에 있을 때 좁힌다
 * @param date       사용자가 <b>연도까지</b> 적었을 때만 찬다. 그 밖에는 {@code null}
 * @param month      연도 없이 월·일만 적었을 때의 월. 연도는 코드가 고른다
 * @param day        위와 같은 자리의 일
 * @param offsetDays 오늘로부터 며칠 뒤. <b>절대 날짜로 굳히지 않는 이유가 여기 있다</b>
 * @param days       며칠치. 비어 있으면 하루
 * @param weekend    「주말」을 물었나. <b>일수가 아니라 요일이다</b> — {@code days=2}로 받으면 화요일의
 *                   「주말」이 화·수가 된다. 참이면 {@code WeatherPeriod#weekend}가 날짜를 편다
 * @param weekday    요일({@code 1}=월 … {@code 7}=일). <b>요일도 날짜는 LLM이 세지 않는다</b> —
 *                   코드가 편다({@code WeatherPeriod#weekday}). 주말 플래그보다 이긴다
 * @param weekOffset 몇 주 뒤의 그 요일인가. {@code 1}이 「다음 주」. 비어 있으면 다가오는 그 요일
 */
public record ResolvedPlace(String query, String country, String date,
                            Integer month, Integer day,
                            Integer offsetDays, Integer days, Boolean weekend,
                            Integer weekday, Integer weekOffset) {

    /** 주말·요일을 묻지 않은 해석. */
    public ResolvedPlace(String query, String country, String date,
                         Integer month, Integer day, Integer offsetDays, Integer days) {
        this(query, country, date, month, day, offsetDays, days, null, null, null);
    }

    /** 요일을 묻지 않은 해석. */
    public ResolvedPlace(String query, String country, String date,
                         Integer month, Integer day, Integer offsetDays, Integer days, Boolean weekend) {
        this(query, country, date, month, day, offsetDays, days, weekend, null, null);
    }

    /** 요일을 물었나 — 1~7 밖은 못 읽은 것이다. */
    public boolean asksWeekday() {
        return weekday != null && weekday >= 1 && weekday <= 7;
    }

    public boolean asksWeekend() {
        return Boolean.TRUE.equals(weekend);
    }

    /**
     * 사용자가 <b>날짜를 적기는 했는가.</b>
     *
     * <p>적었는데 우리가 못 편 경우를 <b>조용히 오늘로 만들지 않기 위해</b> 있다.
     * {@code 2025년 8월}처럼 일자 없이 연·월만 적으면 펼 날이 없는데, 그때 오늘 날씨를
     * 답하면 사용자는 자기가 적은 날짜가 무시된 줄 모른다.
     */
    public boolean mentionsDate() {
        return absoluteDate() != null || month != null || day != null;
    }

    public boolean hasPlace() {
        return query != null && !query.isBlank();
    }

    /**
     * <b>아무것도 읽어내지 못한 결과인가.</b> 지명도 기간도 없으면 해석이 실패한 것과
     * 같은데, 그걸 {@code Optional.of}로 감싸면 호출자의 분기가 어긋난다.
     *
     * <p>{@code isEmpty}로 이름 짓지 않는다 — 직렬화가 게터로 읽어 캐시 JSON에 필드가 는다.
     */
    public boolean readsNothing() {
        return !hasPlace() && !mentionsDate() && offsetDays == null && days == null && !asksWeekend()
                && !asksWeekday();
    }

    /**
     * 나라 코드. 해석기가 {@code query}와 같은 정규화를 거쳐 담는다 —
     * 안 거르면 {@code countryCode=null}이 쿼리에 실려 헛호출을 한 번 태운다.
     */
    public String countryCode() {
        return country;
    }

    /**
     * 절대 날짜. LLM이 엉뚱한 문자열을 주면 {@code null}로 떨어뜨린다 —
     * 여기서 던지면 검색 전체가 죽는데, 날짜 하나 때문에 그럴 이유가 없다.
     */
    public LocalDate absoluteDate() {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
