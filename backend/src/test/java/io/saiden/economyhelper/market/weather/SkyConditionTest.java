package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 두 출처의 어휘가 <b>같은 한국어로 모이는지</b>를 고정한다.
 *
 * <p>여기가 깨지면 폴백이 일어난 날에만 표기가 달라져, 값이 아니라 우리 사정이 화면에 드러난다.
 */
class SkyConditionTest {

    @Test
    @DisplayName("맑음·흐림은 두 출처가 같은 말로 나온다 — 폴백이 티나면 안 된다")
    void bothProvidersAgreeOnTheCommonWords() {
        assertThat(SkyCondition.ofAccuWeatherIcon(1)).isEqualTo(SkyCondition.ofWmoCode(0));
        assertThat(SkyCondition.ofAccuWeatherIcon(7)).isEqualTo(SkyCondition.ofWmoCode(3));
        assertThat(SkyCondition.ofAccuWeatherIcon(18)).isEqualTo(SkyCondition.ofWmoCode(61));
        assertThat(SkyCondition.ofAccuWeatherIcon(22)).isEqualTo(SkyCondition.ofWmoCode(71));
        assertThat(SkyCondition.ofAccuWeatherIcon(11)).isEqualTo(SkyCondition.ofWmoCode(45));
        assertThat(SkyCondition.ofAccuWeatherIcon(12)).isEqualTo(SkyCondition.ofWmoCode(80));
        assertThat(SkyCondition.ofAccuWeatherIcon(15)).isEqualTo(SkyCondition.ofWmoCode(95));
    }

    @Test
    @DisplayName("낮과 밤 아이콘이 같은 하늘이다 — 하루치 요약에 그 구분은 의미가 없다")
    void dayAndNightIconsFoldTogether() {
        assertThat(SkyCondition.ofAccuWeatherIcon(33)).isEqualTo(SkyCondition.CLEAR);
        assertThat(SkyCondition.ofAccuWeatherIcon(34)).isEqualTo(SkyCondition.MOSTLY_CLEAR);
        assertThat(SkyCondition.ofAccuWeatherIcon(35)).isEqualTo(SkyCondition.PARTLY_CLOUDY);
        assertThat(SkyCondition.ofAccuWeatherIcon(38)).isEqualTo(SkyCondition.CLOUDY);
        assertThat(SkyCondition.ofAccuWeatherIcon(44)).isEqualTo(SkyCondition.SNOW);
    }

    @Test
    @DisplayName("구름이 끼어도 소나기는 소나기다 — 중요한 것은 구름이 아니라 비다")
    void cloudCoverDoesNotHideThePrecipitation() {
        assertThat(SkyCondition.ofAccuWeatherIcon(13)).isEqualTo(SkyCondition.SHOWERS);
        assertThat(SkyCondition.ofAccuWeatherIcon(14)).isEqualTo(SkyCondition.SHOWERS);
        assertThat(SkyCondition.ofAccuWeatherIcon(40)).isEqualTo(SkyCondition.SHOWERS);
        assertThat(SkyCondition.ofAccuWeatherIcon(16)).isEqualTo(SkyCondition.THUNDERSTORM);
        assertThat(SkyCondition.ofAccuWeatherIcon(42)).isEqualTo(SkyCondition.THUNDERSTORM);
        assertThat(SkyCondition.ofAccuWeatherIcon(20)).isEqualTo(SkyCondition.SNOW_SHOWERS);
    }

    @Test
    @DisplayName("실측으로 본 아이콘이 제 이름으로 나온다 — 2026-08-18 미금역")
    void mapsTheIconsSeenInProduction() {
        // 4 Intermittent clouds, 12 Showers, 6 Mostly cloudy — 그날 닷새치에 실제로 온 셋이다
        assertThat(SkyCondition.ofAccuWeatherIcon(4)).isEqualTo(SkyCondition.PARTLY_CLOUDY);
        assertThat(SkyCondition.ofAccuWeatherIcon(12)).isEqualTo(SkyCondition.SHOWERS);
        assertThat(SkyCondition.ofAccuWeatherIcon(6)).isEqualTo(SkyCondition.CLOUDY);
    }

    @Test
    @DisplayName("체감·모르는 값은 하늘 상태가 아니다 — 그 줄만 빠지고 날짜는 살아남는다")
    void treatsNonSkyIconsAsUnknown() {
        // 30 Hot · 31 Cold · 32 Windy는 하늘이 아니라 체감이다. 지어내지 않는다
        assertThat(SkyCondition.ofAccuWeatherIcon(30)).isEqualTo(SkyCondition.UNKNOWN);
        assertThat(SkyCondition.ofAccuWeatherIcon(31)).isEqualTo(SkyCondition.UNKNOWN);
        assertThat(SkyCondition.ofAccuWeatherIcon(32)).isEqualTo(SkyCondition.UNKNOWN);
        assertThat(SkyCondition.ofAccuWeatherIcon(null).known()).isFalse();
        assertThat(SkyCondition.ofAccuWeatherIcon(99)).isEqualTo(SkyCondition.UNKNOWN);
        assertThat(SkyCondition.ofWmoCode(null).known()).isFalse();
    }

    @Test
    @DisplayName("아는 값에는 반드시 한국어가 있다 — 이모지는 쓰지 않는다")
    void everyKnownConditionCarriesKorean() {
        for (SkyCondition condition : SkyCondition.values()) {
            if (condition.known()) {
                assertThat(condition.label()).isNotBlank();
            }
        }
        assertThat(SkyCondition.CLOUDY.label()).isEqualTo("흐림");
        assertThat(SkyCondition.CLEAR.label()).isEqualTo("맑음");
    }
}
