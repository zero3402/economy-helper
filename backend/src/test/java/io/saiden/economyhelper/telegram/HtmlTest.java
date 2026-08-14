package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이스케이프가 틀리면 <b>메시지 발송 자체가 실패한다</b> — 평문일 때는 없던 위험이라
 * 여기서 못 박는다.
 */
class HtmlTest {

    @Test
    @DisplayName("& 를 먼저 바꾼다 — 나중에 하면 &lt; 가 &amp;lt; 로 두 번 이스케이프된다")
    void escapesAmpersandFirst() {
        assertThat(Html.escape("A < B")).isEqualTo("A &lt; B");
        assertThat(Html.escape("AT&T")).isEqualTo("AT&amp;T");
        assertThat(Html.escape("a & b < c > d")).isEqualTo("a &amp; b &lt; c &gt; d");
    }

    @Test
    @DisplayName("Markdown을 깨뜨리던 문자들은 HTML에서 무해하다 — 평문을 고수할 이유가 없다")
    void leavesMarkdownSpecialsAlone() {
        String title = "*별표* _밑줄_ [대괄호] (100%) S&P 500";

        assertThat(Html.escape(title))
                .contains("*별표*").contains("_밑줄_").contains("[대괄호]").contains("(100%)")
                .as("&만 바뀐다").contains("S&amp;P 500");
    }

    @Test
    @DisplayName("null은 빈 문자열이다 — 메시지에 'null'이 찍히면 안 된다")
    void treatsNullAsEmpty() {
        assertThat(Html.escape(null)).isEmpty();
    }
}
