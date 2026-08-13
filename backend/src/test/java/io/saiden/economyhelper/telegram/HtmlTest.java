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

    @Test
    @DisplayName("한글은 두 칸으로 센다 — 글자 수로 맞추면 모노스페이스에서 어긋난다")
    void countsHangulAsTwoColumns() {
        assertThat(Html.width("코스피")).isEqualTo(6);
        assertThat(Html.width("SK하이닉스")).isEqualTo(10);   // 영문 2 + 한글 4자
        assertThat(Html.width("AAPL")).isEqualTo(4);
    }

    @Test
    @DisplayName("폭을 맞춰 채운다 — 이름 길이가 달라도 값의 왼쪽 끝이 세로로 떨어진다")
    void padsToDisplayWidth() {
        assertThat(Html.width(Html.pad("코스피", 11))).isEqualTo(11);
        assertThat(Html.width(Html.pad("SK하이닉스", 11))).isEqualTo(11);
        assertThat(Html.width(Html.padLeft("6,345.53", 15))).isEqualTo(15);
    }

    @Test
    @DisplayName("칸보다 긴 이름은 자르지 않는다 — 값이 한 칸 밀릴 뿐 정보를 잃지 않는다")
    void neverTruncatesLongNames() {
        assertThat(Html.pad("삼성바이오로직스우선주", 11)).isEqualTo("삼성바이오로직스우선주");
    }
}
