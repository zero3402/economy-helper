package io.saiden.economyhelper.telegram;

/**
 * 텔레그램 {@code parse_mode=HTML}을 위한 이스케이프.
 *
 * <p><b>왜 Markdown이 아니라 HTML인가.</b> 기사 제목에 흔한 {@code *}·{@code _}·{@code [}가
 * Markdown에서는 파싱 오류를 내 발송 자체를 실패시키는데, <b>HTML에서는 아무 의미가 없다.</b>
 * 실제 봇으로 확인한 결과다:
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

    /**
     * <b>속성값</b>에 넣을 문자열 — {@code href="…"} 안쪽이다.
     *
     * <p>{@link #escape}로는 부족하다. 세 글자만 바꾸면 되는 것은 <b>본문</b>일 때이고,
     * 속성값에서는 따옴표 하나가 그 자리에서 속성을 닫아 버린다 — 텔레그램은 그걸
     * {@code can't parse entities}로 거절하므로 그 기사 통이 통째로 안 나간다.
     *
     * <p>지금은 상류가 막고 있다({@code NewsSource.owns()}의 {@code URI.create}가 그런 링크를
     * 걸러낸다). 그 가드에 기대지 않는 이유는, 링크가 다른 길로 들어오는 날 <b>조용히</b>
     * 실패하기 때문이다.
     */
    static String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }
}
