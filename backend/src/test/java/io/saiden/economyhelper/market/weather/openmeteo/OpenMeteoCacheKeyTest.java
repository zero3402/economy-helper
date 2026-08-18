package io.saiden.economyhelper.market.weather.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

/**
 * 예보와 재분석이 <b>같은 캐시({@code weather})에 서로 다른 키</b>로 쓰는지 못 박는다.
 *
 * <p>둘의 키 모양이 같던 때가 있었다. 지금 안 부딪히는 이유가 {@code supports()}의 과거/미래
 * 구분뿐이었는데, 그 근거는 제3의 파일({@code WeatherService})에 있었다. TTL이 10분이라
 * <b>23:57에 '오늘 예보'로 담긴 항목이 00:00 이후에는 과거 조회에 맞는다</b> — 그 10분 동안
 * 재분석 실측 자리에 예보값이 {@code Open-Meteo} 이름으로 나갔다.
 *
 * <p>런타임 캐시를 띄우지 않고 애너테이션만 본다. 이 규칙은 값이 아니라 <b>선언</b>이고,
 * 어긋나는 순간이 배포 후 자정이라 테스트로 잡는 편이 유일하게 확실하다.
 */
class OpenMeteoCacheKeyTest {

    @Test
    @DisplayName("예보와 재분석이 한 캐시를 쓰지만 키 접두사가 다르다 — 같으면 자정에 섞인다")
    void forecastAndArchiveDoNotShareACacheKey() {
        Cacheable forecast = cacheableOf(OpenMeteoForecastClient.class);
        Cacheable archive = cacheableOf(OpenMeteoArchiveClient.class);

        assertThat(forecast.cacheNames())
                .as("한 캐시를 쓰는 것 자체는 의도다 — 담기는 타입이 같다")
                .containsExactly("weather");
        assertThat(archive.cacheNames()).containsExactly("weather");

        assertThat(forecast.key())
                .as("접두사가 없으면 좌표·기간만으로 키가 만들어져 재분석과 겹친다")
                .startsWith("'om:'");
        assertThat(archive.key()).startsWith("'oma:'");
        assertThat(forecast.key())
                .as("두 키가 같은 문자열이면 접두사를 붙인 의미가 없다")
                .isNotEqualTo(archive.key());
    }

    @Test
    @DisplayName("날씨 출처 셋이 서로 다른 접두사를 쓴다 — AccuWeather까지 포함해 셋이다")
    void everyWeatherSourceHasItsOwnPrefix() {
        String accu = cacheableOf(
                io.saiden.economyhelper.market.weather.accu.AccuWeatherClient.class).key();

        assertThat(Arrays.asList(accu,
                        cacheableOf(OpenMeteoForecastClient.class).key(),
                        cacheableOf(OpenMeteoArchiveClient.class).key()))
                .as("셋이 한 캐시를 나눠 쓰므로 접두사 셋이 모두 달라야 한다")
                .doesNotHaveDuplicates();
        assertThat(accu).startsWith("'accu:'");
    }

    /** {@code forecast(GeoLocation, WeatherPeriod)}에 달린 애너테이션. */
    private static Cacheable cacheableOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> "forecast".equals(method.getName()))
                .map(method -> method.getAnnotation(Cacheable.class))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        type.getSimpleName() + ".forecast에 @Cacheable이 없습니다"));
    }
}
