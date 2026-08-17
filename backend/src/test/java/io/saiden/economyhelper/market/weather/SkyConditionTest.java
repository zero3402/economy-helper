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
    void bothVendorsAgreeOnTheSameKorean() {
        assertThat(SkyCondition.ofWmoCode(0)).isEqualTo(SkyCondition.ofSymbolCode("clearsky_day"));
        assertThat(SkyCondition.ofWmoCode(3)).isEqualTo(SkyCondition.ofSymbolCode("cloudy"));
        assertThat(SkyCondition.ofWmoCode(61)).isEqualTo(SkyCondition.ofSymbolCode("rain"));
        assertThat(SkyCondition.ofWmoCode(71)).isEqualTo(SkyCondition.ofSymbolCode("snow"));
        assertThat(SkyCondition.ofWmoCode(45)).isEqualTo(SkyCondition.ofSymbolCode("fog"));
    }

    @Test
    @DisplayName("낮/밤 꼬리를 뗀다 — 하루치 요약에 낮밤 구분은 의미가 없다")
    void ignoresDayNightSuffixes() {
        assertThat(SkyCondition.ofSymbolCode("clearsky_day")).isEqualTo(SkyCondition.CLEAR);
        assertThat(SkyCondition.ofSymbolCode("clearsky_night")).isEqualTo(SkyCondition.CLEAR);
        assertThat(SkyCondition.ofSymbolCode("clearsky_polartwilight")).isEqualTo(SkyCondition.CLEAR);
    }

    @Test
    @DisplayName("강도 접두사도 뗀다 — WMO 쪽에서 61·63·65를 묶은 것과 같은 이유다")
    void ignoresIntensityPrefixes() {
        assertThat(SkyCondition.ofSymbolCode("lightrain")).isEqualTo(SkyCondition.RAIN);
        assertThat(SkyCondition.ofSymbolCode("heavyrain")).isEqualTo(SkyCondition.RAIN);
        assertThat(SkyCondition.ofWmoCode(61)).isEqualTo(SkyCondition.RAIN);
        assertThat(SkyCondition.ofWmoCode(65)).isEqualTo(SkyCondition.RAIN);
    }

    @Test
    @DisplayName("뇌우가 섞이면 뇌우로 본다 — 그때 중요한 건 비가 아니라 뇌우다")
    void thunderWinsOverWhateverItIsMixedWith() {
        assertThat(SkyCondition.ofSymbolCode("rainandthunder")).isEqualTo(SkyCondition.THUNDERSTORM);
        assertThat(SkyCondition.ofSymbolCode("heavyrainshowersandthunder_day"))
                .isEqualTo(SkyCondition.THUNDERSTORM);
        assertThat(SkyCondition.ofWmoCode(95)).isEqualTo(SkyCondition.THUNDERSTORM);
    }

    @Test
    @DisplayName("모르는 값은 지어내지 않는다 — 아무 날씨나 찍느니 그 줄을 비운다")
    void neverGuessesAnUnknownCode() {
        assertThat(SkyCondition.ofWmoCode(null).known()).isFalse();
        assertThat(SkyCondition.ofWmoCode(999).known()).isFalse();
        assertThat(SkyCondition.ofSymbolCode(null).known()).isFalse();
        assertThat(SkyCondition.ofSymbolCode("")).isEqualTo(SkyCondition.UNKNOWN);
        assertThat(SkyCondition.ofSymbolCode("meteorshower")).isEqualTo(SkyCondition.UNKNOWN);
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
