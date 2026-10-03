package io.saiden.economyhelper.weather.adapter.out.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.weather.adapter.out.accu.AccuWeatherClient;
import io.saiden.economyhelper.weather.adapter.out.kma.KmaWeatherClient;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

/**
 * 예보와 재분석이 <b>같은 캐시({@code weather})에 서로 다른 키</b>로 쓰는지 못 박는다.
 *
 * <p>키 모양이 같으면 TTL이 10분이라 <b>23:57에 '오늘 예보'로 담긴 항목이 00:00 이후에는 과거
 * 조회에 맞아</b> 재분석 실측 자리에 예보값이 나간다.
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
    @DisplayName("날씨 출처 넷이 서로 다른 접두사를 쓴다 — 기상청까지 포함해 넷이다")
    void everyWeatherSourceHasItsOwnPrefix() {
        String accu = cacheableOf(
                io.saiden.economyhelper.weather.adapter.out.accu.AccuWeatherClient.class).key();
        // ⚠️ 클래스를 손으로 적는 목록이다. 새 출처를 여기 안 더하면 접두사가 겹쳐도
        //    이 그물이 아예 안 본다 — 그때는 남의 답이 우리 이름으로 나간다
        String kma = cacheableOf(
                io.saiden.economyhelper.weather.adapter.out.kma.KmaWeatherClient.class).key();

        assertThat(Arrays.asList(accu, kma,
                        cacheableOf(OpenMeteoForecastClient.class).key(),
                        cacheableOf(OpenMeteoArchiveClient.class).key()))
                .as("넷이 한 캐시를 나눠 쓰므로 접두사가 모두 달라야 한다")
                .doesNotHaveDuplicates();
        assertThat(accu).startsWith("'accu:'");
        assertThat(kma).startsWith("'kma:'");
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
