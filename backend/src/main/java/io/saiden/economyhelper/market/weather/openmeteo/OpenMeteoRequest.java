package io.saiden.economyhelper.market.weather.openmeteo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
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
     * @param fields 받을 일일 항목. 예보는 강수확률, 재분석은 강수량을 받는다
     * @param source 화면에 적을 출처. 실패 메시지에도 이 이름이 들어간다
     * @throws IllegalStateException 응답에 일일 값이 없을 때. <b>던져야</b> {@code WeatherService}가
     *                               다음 출처로 넘어간다 — 빈 값을 돌려주면 폴백이 안 일어난다
     */
    static Weather daily(RestClient restClient, String path, String fields,
                         GeoLocation place, WeatherPeriod period, WeatherSource source) {
        Response response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam("latitude", place.latitude())
                        .queryParam("longitude", place.longitude())
                        .queryParam("start_date", period.from())
                        .queryParam("end_date", period.to())
                        .queryParam("daily", fields)
                        // 일일 값이 그 지점의 지역시로 잘려야 한다 — KST로 자르면 남의 하루가 쪼개진다
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .body(Response.class);

        if (response == null || response.daily() == null || response.daily().isEmpty()) {
            throw new IllegalStateException(source.displayName() + " 응답에 일일 값이 없습니다");
        }
        return new Weather(place, response.daily().toDays(), source);
    }

    /** 두 API 모두 {@code daily} 말고도 좌표·표준시대 등을 함께 주므로 나머지는 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(DailyBlock daily) {}
}
