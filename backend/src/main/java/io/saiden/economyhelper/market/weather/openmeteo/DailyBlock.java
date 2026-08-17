package io.saiden.economyhelper.market.weather.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Open-Meteo의 {@code daily} 블록 — <b>예보와 재분석이 같은 모양이다.</b>
 *
 * <p>두 API가 호스트만 다르고 응답 스키마가 같아서 파서를 한 번만 쓴다. 강수만 갈리는데
 * (예보는 {@code precipitation_probability_max}, 재분석은 {@code precipitation_sum})
 * 둘 다 선언해 두고 <b>온 쪽만 채운다</b> — 없는 필드는 Jackson이 {@code null}로 둔다.
 *
 * <p><b>배열이 평행하다.</b> {@code time[i]}·{@code weatherCode[i]}·{@code max[i]}가 같은 날을
 * 가리킨다. 길이가 어긋나면 날짜와 값의 짝이 밀리므로 {@code time}을 기준으로만 훑고,
 * 나머지는 그 자리에 값이 있을 때만 읽는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DailyBlock(
        List<String> time,
        @JsonProperty("weather_code") List<Integer> weatherCode,
        @JsonProperty("temperature_2m_max") List<BigDecimal> temperatureMax,
        @JsonProperty("temperature_2m_min") List<BigDecimal> temperatureMin,
        @JsonProperty("precipitation_probability_max") List<Integer> precipitationProbability,
        @JsonProperty("precipitation_sum") List<BigDecimal> precipitationSum) {

    boolean isEmpty() {
        return time == null || time.isEmpty();
    }

    /**
     * 하루치 목록으로 편다.
     *
     * <p>강수확률을 아는 날은 확률로, 아닌 날은 강수량으로 담는다 — 강수량을 확률이라 부르지
     * 않기 위해 {@link Weather.Daily}가 칸을 나눠 두었고 여기서 그 칸을 골라 채운다.
     */
    List<Weather.Daily> toDays() {
        List<Weather.Daily> days = new ArrayList<>(time.size());
        for (int i = 0; i < time.size(); i++) {
            LocalDate date = LocalDate.parse(time.get(i));
            SkyCondition sky = SkyCondition.ofWmoCode(at(weatherCode, i));
            BigDecimal low = at(temperatureMin, i);
            BigDecimal high = at(temperatureMax, i);

            Integer chance = at(precipitationProbability, i);
            days.add(chance != null
                    ? Weather.Daily.withChance(date, sky, low, high, chance)
                    : Weather.Daily.withAmount(date, sky, low, high, at(precipitationSum, i)));
        }
        return List.copyOf(days);
    }

    /** 평행 배열이 짧거나 아예 없을 때 조용히 비워 둔다 — 그 칸만 빠지고 날짜는 살아남는다. */
    private static <T> T at(List<T> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }
}
