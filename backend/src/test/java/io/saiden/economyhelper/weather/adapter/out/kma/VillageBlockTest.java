package io.saiden.economyhelper.weather.adapter.out.kma;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VillageBlockTest {

    @Test
    @DisplayName("강수량 글자 — 맨숫자·범위·미만·없음을 읽는다. 범위는 첫 숫자, 「강수없음」은 0, 못 읽으면 null")
    void readsEveryPrecipitationShape() {
        assertThat(VillageBlock.amountOf("2.5")).isEqualByComparingTo("2.5");
        assertThat(VillageBlock.amountOf("30.0~50.0mm")).isEqualByComparingTo("30.0");
        assertThat(VillageBlock.amountOf("1.0mm 미만")).as("상한을 집는다 — javadoc에 적은 대가")
                .isEqualByComparingTo("1.0");
        assertThat(VillageBlock.amountOf("50.0mm 이상")).isEqualByComparingTo("50.0");
        assertThat(VillageBlock.amountOf("강수없음")).isEqualByComparingTo("0");
        assertThat(VillageBlock.amountOf("적설없음")).isEqualByComparingTo("0");
        assertThat(VillageBlock.amountOf("")).as("「모른다」는 0이 아니다").isNull();
        assertThat(VillageBlock.amountOf("알수없음")).isNull();
    }
}
