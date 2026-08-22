package io.saiden.economyhelper.market.weather.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.HalfDays;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Open-Meteo의 {@code hourly} 블록 — <b>하루 안의 시각</b>을 담는다.
 *
 * <p>{@link DailyBlock}과 모양이 같다(병렬 배열: {@code time[]} + 값 배열들). 그런데 <b>파서를
 * 나눠야 한다</b> — {@code DailyBlock}은 {@code LocalDate.parse}로 읽는데 시간별 {@code time}은
 * {@code 2026-08-20T15:00} 꼴이라 <b>그 파서로는 못 읽는다</b>(예외가 난다).
 *
 * <p><b>{@code timezone=auto}가 걸려 있어야 이 시각이 그 지역 현지시다.</b> 안 걸리면 UTC로
 * 와서 「오후 1시」가 남의 오후가 된다 — 일 단위에서 이미 세운 규칙이 시간 단위에서 더 눈에 띈다.
 *
 * <p>예보는 확률과 강수량을 함께 주고 재분석은 강수량만 준다. 그래서 확률 배열이 통째로 없을 수
 * 있고, {@link HalfDays}가 그때 강수량으로만 토막을 만든다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HourlyBlock(
        List<String> time,
        @JsonProperty("precipitation_probability") List<Integer> precipitationProbability,
        List<BigDecimal> precipitation,
        @JsonProperty("weather_code") List<Integer> weatherCode) {

    boolean isEmpty() {
        return time == null || time.isEmpty();
    }

    /**
     * 날짜별 강수 토막.
     *
     * <p>못 읽는 시각은 <b>버리고 넘어간다</b> — 시간 하나의 형식이 바뀌었다고 하루치 답을
     * 못 주게 하지 않는다. 강수 시각은 <b>보충</b>이라 없으면 줄만 빠지는 값이다.
     */
    Map<LocalDate, List<HalfDay>> halvesByDay() {
        if (isEmpty()) {
            return Map.of();
        }
        List<LocalDateTime> times = new ArrayList<>(time.size());
        for (String at : time) {
            times.add(parse(at));
        }
        return HalfDays.byDay(times, precipitationProbability, precipitation, weatherCode);
    }

    /** @return 못 읽으면 {@code null} — 그 시간만 빠진다 */
    private static LocalDateTime parse(String at) {
        try {
            return at == null ? null : LocalDateTime.parse(at);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
