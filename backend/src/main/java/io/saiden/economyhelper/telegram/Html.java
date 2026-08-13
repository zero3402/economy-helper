package io.saiden.economyhelper.telegram;

/**
 * 텔레그램 {@code parse_mode=HTML}을 위한 이스케이프와 폭 맞춤.
 *
 * <p><b>왜 Markdown이 아니라 HTML인가.</b> 예전에는 서식 없는 평문을 썼다 — 기사 제목의
 * {@code *}·{@code _}·{@code [}가 Markdown 파싱 오류를 내 발송 자체가 실패했기 때문이다.
 * 실제 봇으로 확인해 보니 <b>HTML에서는 그 문자들이 아무 의미가 없다</b>:
 *
 * <pre>
 * parse_mode=HTML, 이스케이프 없이 "A &lt; B"  → ok=false  can't parse entities
 * parse_mode=HTML, &amp; &lt; &gt; 만 이스케이프       → ok=true
 *                  같은 메시지의 *별표* _밑줄_ [대괄호] (100%) 전부 무해
 * </pre>
 *
 * HTML은 <b>세 글자만</b> 처리하면 되고 MarkdownV2는 열여덟 자다.
 */
final class Html {

    private Html() {
    }

    /**
     * 바깥에서 온 문자열을 안전하게 만든다 — 기사 제목·본문·매체명·종목명·검색어 <b>전부</b>.
     *
     * <p>하나라도 빠뜨리면 그 메시지는 발송 자체가 실패한다. 평문일 때는 없던 위험이고,
     * 기사 제목에 {@code &}는 흔하다({@code AT&T}, {@code S&P 500}).
     *
     * <p><b>{@code &}를 반드시 먼저 바꾼다.</b> 나중에 하면 앞서 만든 {@code &lt;}의
     * {@code &}가 다시 치환돼 {@code &amp;lt;}가 된다.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 이름 열 — 오른쪽에 공백을 채워 값의 왼쪽 끝을 맞춘다. */
    static String pad(String value, int columns) {
        int width = width(value);
        return width >= columns ? value : value + " ".repeat(columns - width);
    }

    /** 숫자 열 — 왼쪽에 채운다. 우측 정렬해야 자릿수가 세로로 맞는다. */
    static String padLeft(String value, int columns) {
        int width = width(value);
        return width >= columns ? value : " ".repeat(columns - width) + value;
    }

    /**
     * 표시 폭. <b>글자 수가 아니다.</b>
     *
     * <p>한글은 모노스페이스에서 두 칸을 차지해 {@code 코스피}(3자)와 {@code SK하이닉스}(6자)를
     * 글자 수로 맞추면 어긋난다.
     */
    static int width(String value) {
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            total += wide(value.charAt(i)) ? 2 : 1;
        }
        return total;
    }

    private static boolean wide(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3)      // 한글 음절
                || (c >= 0x1100 && c <= 0x11FF)  // 한글 자모
                || (c >= 0x3130 && c <= 0x318F)  // 호환 자모
                || (c >= 0x3040 && c <= 0x30FF)  // 가나
                || (c >= 0x4E00 && c <= 0x9FFF)  // 한자
                || (c >= 0xFF01 && c <= 0xFF60); // 전각
    }
}
