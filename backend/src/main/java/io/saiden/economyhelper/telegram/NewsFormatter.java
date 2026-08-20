package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.DATE_TIME;
import static io.saiden.economyhelper.telegram.MessageLayout.SEOUL;
import static io.saiden.economyhelper.telegram.MessageLayout.empty;
import static io.saiden.economyhelper.telegram.MessageLayout.head;
import static io.saiden.economyhelper.telegram.MessageLayout.title;

import io.saiden.economyhelper.news.NewsItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 뉴스 통 — <b>기사마다 한 통이다.</b>
 *
 * <p>텔레그램이 미리보기 카드를 메시지 맨 아래에 하나만 붙여서, 세 건을 묶으면 첫 기사 카드가
 * 셋째 기사 것처럼 보인다 — 실제로 그렇게 나갔다. 통을 쪼개면 통마다 링크가 하나뿐이라 카드가
 * 어느 기사 것인지 확정된다.
 *
 * <p><b>검색과 브리핑이 이 하나를 함께 쓴다.</b> 두 경로가 다른 모양으로 나가지 않게 하려면
 * 조립이 한 군데에 있어야 한다.
 */
public final class NewsFormatter {

    private NewsFormatter() {
    }

    /**
     * 기사 한 건 — 다른 통과 같은 뼈대다: <b>굵은 제목 / 값 / 출처 / 시각</b>.
     *
     * <p>제목이 곧 링크다. 텔레그램은 {@code <a>} 안에 {@code <b>}를 허용하므로 굵기와 링크를
     * 겹쳐 쓸 수 있고, 그래서 토막난 URL을 따로 한 줄 적을 필요가 없다.
     *
     * <p>{@code publishedAt}에 {@code null} 방어를 두지 않는다. {@code Article}이 생성 시점에
     * 강제하고({@code "publishedAt is required"}) {@code RssFeedClient}는 날짜 없는 항목을
     * 아예 버리므로, 여기까지 온 값에는 날짜가 반드시 있다.
     */
    static String format(NewsItem item) {
        StringBuilder message = new StringBuilder();
        message.append("<a href=\"").append(Html.escapeAttribute(item.link())).append("\"><b>")
                .append(Html.escape(item.title())).append("</b></a>");

        if (!item.body().isBlank()) {
            // 인용 블록으로 감싸면 제목과 본문이 갈려 훨씬 잘 읽힌다
            message.append("\n\n<blockquote>").append(Html.escape(item.body())).append("</blockquote>");
        }
        if (!item.translated()) {
            // 왜 영문인지 밝히지 않으면 고장으로 보인다
            message.append("\n<i>번역이 일시적으로 불가해 원문 그대로 보냅니다.</i>");
        }
        // 매체와 발행 시각 — 환율·증시와 같은 자리, 같은 모양이다
        return message.append("\n\n").append(unlinkable(Html.escape(item.sourceName())))
                .append("\n\n").append(DATE_TIME.format(item.publishedAt().atZone(SEOUL)))
                .toString();
    }

    /**
     * 기사 여러 건 — <b>기사마다 한 통이다.</b> 브리핑과 검색이 같은 메서드를 쓴다.
     *
     * <p><b>왜 묶지 않고 쪼개는가.</b> 텔레그램은 한 메시지에 미리보기 카드를 하나만 붙이고,
     * 그 카드를 <b>메시지 맨 아래</b>에 그린다. 세 건을 한 통에 묶으면 첫 기사의 카드가 셋째
     * 기사 밑에 붙어 <b>셋째 기사의 카드처럼 보인다</b> — 실제로 그렇게 나갔다. 통을 쪼개면
     * 통마다 링크가 하나뿐이라 카드가 어느 기사 것인지 확정된다.
     *
     * <p>통마다 제목을 다시 단다({@code 뉴스 1/3}). 모든 메시지가 굵은 제목으로 시작한다는
     * 규칙을 지키면서, 번호가 몇 번째 기사인지와 전부 몇 건인지를 함께 알려 준다.
     * 한 건뿐이면 외로운 {@code 1/1}이 어색하므로 제목만 쓴다.
     *
     * @return 통 목록. 호출자가 순서대로, 텔레그램 권고대로 사이를 띄워 보낸다
     */
    public static List<String> formatAll(List<NewsItem> items) {
        if (items.isEmpty()) {
            return List.of(empty(Command.NEWS));
        }
        List<String> messages = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            messages.add(newsTitle(i, items.size()) + "\n\n" + format(items.get(i)));
        }
        return List.copyOf(messages);
    }

    /** 한 건이면 번호를 붙이지 않는다 — {@code 1/1}은 알려 주는 것이 없다. */
    private static String newsTitle(int index, int total) {
        return total == 1
                ? title(Command.NEWS)
                : "<b>" + Html.escape(Command.NEWS.section())
                        + " " + (index + 1) + "/" + total + "</b>";
    }

    /**
     * 텔레그램이 <b>스스로 링크로 만들지 못하게</b> 막는다.
     *
     * <p><b>왜 필요한가.</b> 출처 줄은 평문인데 {@code Investing.com}처럼 표기에 TLD가 들어 있으면
     * 텔레그램이 그걸 주소로 알아보고 <b>매체 홈페이지로 가는 링크를 만든다.</b> 그러면 한 통에
     * 링크가 둘이 되고, 사용자가 기사인 줄 알고 누르면 홈페이지가 열린다. 피드가 준 것이 아니라
     * (RSS item에는 {@code <link>}가 하나뿐이다) 화면에서 생겨나는 링크다.
     *
     * <p><b>점 앞에 폭 없는 문자(U+2060 WORD JOINER)를 끼운다.</b> 눈에 보이지 않고 줄바꿈도
     * 일으키지 않아 <b>화면 모양이 그대로다</b> — 링크만 사라진다. {@code <code>}로 감싸는 방법도
     * 있지만 이 파일이 모노스페이스를 쓰지 않기로 했고(복사 버튼이 붙는다), {@code <a>}로 감싸면
     * "값과 설명은 평문"이라는 규칙이 깨진다.
     *
     * <p><b>매체 이름을 지목하지 않는다.</b> 표기에 TLD가 있으면 무엇이든 끊는다 —
     * {@code .com}이 든 매체가 늘어도 따라온다. 매체 쪽({@code NewsSource.displayName})은
     * 손대지 않는다: REST 응답과 로그에는 깨끗한 이름이 나가야 한다.
     */
    private static String unlinkable(String escaped) {
        return TLD.matcher(escaped).replaceAll("⁠$0");
    }

    /**
     * 표기 안의 TLD 꼬리 — {@code Investing.com}의 {@code .com}이 이것이다.
     *
     * <p>점 뒤에 글자 둘 이상이 이어지고 그 뒤가 낱말 경계일 때만 문다. 문장 끝 마침표나
     * 소수점은 걸리지 않는다.
     */
    private static final java.util.regex.Pattern TLD =
            java.util.regex.Pattern.compile("\\.[a-zA-Z]{2,}\\b");

    /**
     * <b>왜 없는지 함께 말한다.</b> "찾지 못했습니다"만 있으면 사용자는 봇 고장으로 읽는다 —
     * 실제로는 그 주제 기사가 창 안에 없었을 뿐이다. 창을 인자로 받는 이유는 설정을 바꿨을 때
     * 문구가 따라오지 않으면 그 자체로 거짓말이 되기 때문이다.
     */
    public static String noResults(String query, Duration window) {
        return head(Command.NEWS)
                + "'" + Html.escape(query) + "'에 해당하는 최근 " + window.toHours() + "시간 뉴스를 찾지 못했습니다.";
    }
}
