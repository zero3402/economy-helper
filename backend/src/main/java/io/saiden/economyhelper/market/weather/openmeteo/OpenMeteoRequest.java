package io.saiden.economyhelper.market.weather.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * 예보와 재분석이 <b>같은 모양으로 묻는다.</b>
 *
 * <p>두 API는 호스트와 경로와 받을 항목만 다르고, 좌표·기간·{@code timezone=auto}를 싣는 방식이
 * 같다. 그 조립을 두 곳에 두면 한쪽만 고쳐질 수 있다 — {@code timezone=auto}는 둘 다에 반드시
 * 있어야 하는 것이라, 하나에서 빠지면 그 출처가 답한 날만 남의 하루가 쪼개진다.
 *
 * <p><b>출처를 합치는 것이 아니다.</b> 두 클라이언트는 각자 {@link WeatherSource}와
 * {@code supports()}와 서킷브레이커와 <b>캐시 키 접두사</b>를 그대로 쥔다 — 예보 격자(~1km)와
 * ERA5 재분석(~11km)의 차이가 화면에서 사라지면 안 된다는 규칙은 그대로다. 합치는 것은
 * 질문하는 방법뿐이다.
 */
final class OpenMeteoRequest {

    private OpenMeteoRequest() {
    }

    /**
     * @param fields       받을 일일 항목. 예보는 강수확률, 재분석은 강수량을 받는다
     * @param hourlyFields 받을 시간별 항목 — 하루 안의 <b>강수 시각</b>을 여기서 얻는다.
     *                     일일 값과 한 응답으로 오므로 <b>호출이 늘지 않는다</b>
     * @param source 화면에 적을 출처. 실패 메시지에도 이 이름이 들어간다
     * @throws IllegalStateException 응답에 일일 값이 없을 때. <b>던져야</b> {@code WeatherService}가
     *                               다음 출처로 넘어간다 — 빈 값을 돌려주면 폴백이 안 일어난다
     */
    static Weather daily(RestClient restClient, String path, String fields, String hourlyFields,
                         GeoLocation place, WeatherPeriod period, WeatherSource source) {
        Response response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam("latitude", place.latitude())
                        .queryParam("longitude", place.longitude())
                        .queryParam("start_date", period.from())
                        .queryParam("end_date", period.to())
                        .queryParam("daily", fields)
                        // 하루 안의 강수 시각. 일일 값과 **한 응답**으로 오므로 호출이 안 늘어난다
                        .queryParam("hourly", hourlyFields)
                        // 일일 값이 그 지점의 지역시로 잘려야 한다 — KST로 자르면 남의 하루가 쪼개진다.
                        // ⚠️ 시간별에도 이것이 걸려야 한다. 안 걸리면 「오후 1시」가 UTC 오후가 된다
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .body(Response.class);

        if (response == null || response.daily() == null || response.daily().isEmpty()) {
            throw new IllegalStateException(source.displayName() + " 응답에 일일 값이 없습니다");
        }
        // 강수 시각을 날짜별로 얹는다. 시간별 값이 없으면 빈 map이라 하루가 그대로 나간다 —
        // **보충은 답을 죽이지 않는다**는 규칙이 여기서부터 걸린다
        Map<LocalDate, List<HalfDay>> halves = response.hourly() == null
                ? Map.of()
                : response.hourly().halvesByDay();
        List<Weather.Daily> days = response.daily().toDays().stream()
                .map(day -> halves.containsKey(day.date())
                        ? day.withHalves(halves.get(day.date()))
                        : day)
                .toList();
        return new Weather(place, days, source);
    }

    /**
     * <b>시간별만</b> 묻는다 — 1순위가 일별을 맡았을 때의 보충이다.
     *
     * <p>일별을 안 받으므로 {@code daily}가 없어도 실패가 아니다. <b>예외를 올리지 않는다</b> —
     * 보충이 답을 죽이면 안 된다({@code OpenMeteoHourlyClient} javadoc 참고).
     */
    static Map<LocalDate, List<HalfDay>> hourly(RestClient restClient, String path,
                                                           String hourlyFields, GeoLocation place,
                                                           WeatherPeriod period) {
        Response response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam("latitude", place.latitude())
                        .queryParam("longitude", place.longitude())
                        .queryParam("start_date", period.from())
                        .queryParam("end_date", period.to())
                        .queryParam("hourly", hourlyFields)
                        // 시각이 그 지역 현지시여야 한다 — 없으면 「오후 1시」가 UTC 오후가 된다
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .body(Response.class);

        return response == null || response.hourly() == null
                ? Map.of()
                : response.hourly().halvesByDay();
    }

    /**
     * 두 API 모두 좌표·표준시대 등을 함께 주므로 나머지는 무시한다.
     *
     * <p>⚠️ <b>{@code hourly}를 여기 적지 않으면 조용히 버려진다.</b> {@code @JsonIgnoreProperties}
     * 때문에 오류도 안 난다 — 예전에 이 레코드는 필드가 {@code daily} 하나뿐이었다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(DailyBlock daily, HourlyBlock hourly) {}
}
