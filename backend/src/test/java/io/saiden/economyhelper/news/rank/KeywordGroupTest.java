package io.saiden.economyhelper.news.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 개념 하나가 여러 표현을 갖는다는 규칙을 고정한다. */
class KeywordGroupTest {

    @Test
    @DisplayName("소문자로 맞추고 공백을 떼고 중복을 지운다")
    void normalizesTerms() {
        KeywordGroup group = KeywordGroup.of("  Bitcoin ", "BITCOIN", "btc");

        assertThat(group.terms()).containsExactly("bitcoin", "btc");
    }

    @Test
    @DisplayName("빈 값과 null은 걸러낸다 — 번역 결과에 섞여 들어올 수 있다")
    void dropsBlankAndNullTerms() {
        assertThat(KeywordGroup.of("bitcoin", "", "  ", null).terms()).containsExactly("bitcoin");
        assertThat(new KeywordGroup(null).isEmpty()).isTrue();
        assertThat(new KeywordGroup(Arrays.asList(null, " ")).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("표현 하나라도 걸리면 그 개념이 걸린 것이다")
    void matchesWhenAnyTermIsPresent() {
        KeywordGroup group = KeywordGroup.of("반도체", "semiconductor", "chip");

        assertThat(group.matches("nvidia chip demand surges")).isTrue();
        assertThat(group.matches("semiconductor exports fall")).isTrue();
        assertThat(group.matches("oil prices climb")).isFalse();
    }

    @Test
    @DisplayName("여러 단어로 된 표현도 그대로 찾는다 — interest rate 같은 구다")
    void matchesMultiWordTerms() {
        assertThat(KeywordGroup.of("interest rate").matches("fed lifts interest rates again"))
                .isTrue();
    }

    @Test
    @DisplayName("빈 묶음은 아무것도 매칭하지 않는다")
    void emptyGroupMatchesNothing() {
        assertThat(KeywordGroup.of().matches("anything")).isFalse();
        assertThat(new KeywordGroup(List.of()).isEmpty()).isTrue();
    }
}
