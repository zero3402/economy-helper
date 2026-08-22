package io.saiden.economyhelper.support;

import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.PrecipitationSpell;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.openmeteo.OpenMeteoHourlyClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * 날씨 테스트가 나눠 쓰는 가짜.
 *
 * <p>대부분의 날씨 테스트는 <b>강수 시각에 관심이 없다</b> — 폴백 순서·기간 판정·렌더를 본다.
 * 그 테스트들이 보충 클라이언트를 손으로 만들면 관심 없는 것이 인자 목록에 남고, 컴포넌트가
 * 늘 때마다 다시 고쳐야 한다({@link TestProperties}를 만든 것과 같은 이유다).
 */
public final class TestWeather {

    private TestWeather() {
    }

    /**
     * 아무것도 보충하지 않는 시간별 클라이언트.
     *
     * <p>빈 map은 <b>실패가 아니라 없음</b>이다 — 그래서 하루는 그대로 나가고 화면에서 강수
     * 시각 줄만 빠진다. 보충이 답을 죽이지 않는다는 계약이 이 가짜에 그대로 담겨 있다.
     */
    public static OpenMeteoHourlyClient noHourly() {
        return new OpenMeteoHourlyClient(RestClient.builder(), "https://example.invalid") {
            @Override
            public Map<LocalDate, List<PrecipitationSpell>> spells(GeoLocation place,
                                                                   WeatherPeriod period) {
                return Map.of();
            }
        };
    }

    /** 날짜별로 정해진 토막을 주는 시간별 클라이언트 — 보충 경로 자체를 보는 테스트가 쓴다. */
    public static OpenMeteoHourlyClient hourly(Map<LocalDate, List<PrecipitationSpell>> spells) {
        return new OpenMeteoHourlyClient(RestClient.builder(), "https://example.invalid") {
            @Override
            public Map<LocalDate, List<PrecipitationSpell>> spells(GeoLocation place,
                                                                   WeatherPeriod period) {
                return spells;
            }
        };
    }

    /**
     * <b>불렸는지를 세는</b> 시간별 클라이언트 — 값은 안 주고 호출만 센다.
     *
     * <p>「보충을 안 부른다」는 <b>없는 일을 단언하는</b> 것이라 결과만 봐서는 증명할 수 없다.
     * 마른 날에는 부르든 안 부르든 화면이 똑같기 때문이다 — 그래서 세는 자리가 필요하다.
     */
    public static OpenMeteoHourlyClient countingHourly(
            java.util.concurrent.atomic.AtomicInteger calls) {
        return new OpenMeteoHourlyClient(RestClient.builder(), "https://example.invalid") {
            @Override
            public Map<LocalDate, List<PrecipitationSpell>> spells(GeoLocation place,
                                                                   WeatherPeriod period) {
                calls.incrementAndGet();
                return Map.of();
            }
        };
    }

    /** 보충이 터지는 클라이언트 — 그래도 일일 예보는 나가야 한다. */
    public static OpenMeteoHourlyClient explodingHourly() {
        return new OpenMeteoHourlyClient(RestClient.builder(), "https://example.invalid") {
            @Override
            public Map<LocalDate, List<PrecipitationSpell>> spells(GeoLocation place,
                                                                   WeatherPeriod period) {
                throw new IllegalStateException("보충 실패");
            }
        };
    }
}
