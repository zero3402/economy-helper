package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import io.saiden.economyhelper.news.NewsItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 값들을 텔레그램 메시지로 옮긴다.
 *
 * <p><b>{@code parse_mode=HTML}로 보낸다.</b> 기사 제목에 흔한 {@code *}·{@code _}·{@code [}가
 * HTML에서는 무해하다(Markdown에서는 파싱 오류를 내고 그러면 발송 자체가 실패한다).
 * 대신 {@code & < >} 세 자를 처리해야 하며 그것은 {@link Html}이 맡는다.
 *
 * <p><b>마크업 규칙은 하나다 — 굵게는 제목·소제목뿐이고 값과 설명은 평문이다.</b>
 * 값까지 굵으면 무엇이 계층인지 드러나지 않는다.
 *
 * <p><b>{@code <pre>}·{@code <code>}를 쓰지 않는다.</b> 모노스페이스면 자릿수를 세로로 맞출 수
 * 있지만 텔레그램이 그 블록에 복사 버튼을 띄운다 — 읽으라고 보낸 글에 붙을 이유가 없다.
 * 명령 예시도 평문으로 적는다.
 *
 * <p>예외는 둘뿐이다. {@code <blockquote>}는 뉴스 요약에 쓴다(복사 버튼이 안 붙고 제목과
 * 본문을 가른다). {@code <i>}는 번역 실패 경고 한 곳에만 쓴다 — 그건 값이 아니라 시스템이
 * 붙인 주석이고, 굵게 쓰면 제목으로 오해된다.
 *
 * <p><b>이모지를 쓰지 않는다 — 등락률 한 곳만 빼고.</b> 상승이 빨강, 하락이 파랑인 것은
 * 국내 시세 화면의 관습이라 색 자체가 정보인데, <b>텔레그램은 글자에 색을 못 입힌다</b>
 * (HTML 모드가 허용하는 태그가 {@code b·i·u·s·a·code·pre·blockquote·tg-spoiler}뿐이고
 * {@code tg-emoji}는 Fragment에서 산 사용자명이 있어야 한다). 색을 내는 유일한 수단이
 * 이모지라서 여기만 남긴다 — 장식이 아니라 값의 일부다.
 *
 * <p><b>모든 통이 같은 순서로 끝맺는다: 굵은 제목 / 값 / 출처 / 시각.</b> 출처와 시각은 각각
 * 제 블록이라 사이가 빈 줄이고, 시각은 언제나 마지막이다.
 *
 * <p><b>무리가 있는 통은 그 꼬리를 무리마다 단다.</b> 증시의 {@code 국내}·{@code 미국}은 조회처도
 * 기준도 다른데, 둘을 통 맨 아래에 모으면 어느 무리 것인지 밝히려고 무리 이름을 접두사로 네 번
 * 반복해야 한다. 무리 하나가 통 하나처럼 끝맺으면 그 접두사가 통째로 사라진다.
 *
 * <p>코인만 출처 자리가 빈다 — 거래소가 곧 출처인데 코인마다 둘씩이라 값 줄에 이름으로 적혀 있다.
 * 굵은 것은 언제나 사용자가 읽으러 온 것 — 기사 제목이다.
 *
 * <p><b>줄 간격에 규칙이 하나뿐이다 — 빈 줄은 블록 사이, 한 줄은 블록 안.</b>
 * 블록은 {@code 이름 / 값 / 환산 / 등락률} 한 덩어리다: 종목 하나, 거래소 하나, 김프 하나.
 * 굵은 제목(통 제목·무리 제목·티커) 다음도 빈 줄이다. 붙여 놓으면 업비트·바이낸스·김프가
 * 구분 없이 이어져 어디까지가 한 덩어리인지 읽는 사람이 셀 수 없다.
 *
 * <p>그래서 <b>코인끼리의 경계는 굵은 티커가 진다</b> — 증시에서 무리 경계를 굵은
 * {@code 국내}·{@code 미국}이 지는 것과 같다. 간격으로 계층을 두 단계 표현하려 들면
 * 빈 줄이 두 겹 되어 오히려 읽기 어려워진다.
 *
 * <p><b>검색 답과 브리핑이 같은 함수를 쓴다.</b> {@code /stock}·{@code /crypto}·{@code /news}는
 * 브리핑 통에 항목이 하나뿐인 경우일 뿐이므로 포매터를 나누지 않는다 — 나누면 같은 값이
 * 두 모양으로 그려지고, 실제로 그렇게 갈려 있었다(제목이 한쪽은 {@code 증시}, 다른 쪽은
 * 종목명이었고 거래소 간격도 서로 달랐다).
 *
 * <p><b>성공하든 실패하든 굵은 제목으로 시작한다</b>({@link #section}). 그룹 채팅에서는
 * 답이 여러 대화 사이에 끼므로 제목이 없으면 무엇에 대한 답인지 알 수 없다. 제목 문자열은
 * {@link Command#section()} 한 곳에만 둔다.
 *
 * <p><b>바깥에서 온 문자열은 예외 없이 {@link Html#escape}를 통과한다.</b>
 * 하나라도 빠뜨리면 텔레그램이 파싱에 실패해 그 메시지는 발송 자체가 안 된다.
 *
 * <p>표기 규칙은 전 메시지 공통이다.
 * <ul>
 *   <li><b>날짜·시각</b>: {@code 2026년 8월 13일 07:00:00}. 시각을 모르는 값에는 날짜까지만
 *       쓰고 성격을 괄호로 밝힌다 — {@code 2026년 8월 12일 (종가)}·{@code (고시)}.
 *       없는 시각을 {@code 00:00:00}으로 채우면 그 시각에 체결된 것처럼 읽힌다
 *   <li><b>통화</b>: 값 뒤에 ISO 코드. {@code 239,500 KRW}·{@code 302.25 USD}.
 *       {@code $}가 어느 나라 달러인지 모호한 문제도 같이 사라진다
 *   <li><b>지수에는 통화도 원화 환산도 붙이지 않는다</b> — 코스피 6,345.53은 KRW가 아니다
 *   <li><b>환산에 쓴 환율은 출처와 날짜를 함께 쓴다</b> — 주말이면 며칠 전 고시가로
 *       환산되는데 값만 보여주면 오늘 환율인 줄 안다
 * </ul>
 */
public final class MessageFormatter {

    /** 시세는 "언제 값인지"가 값 자체만큼 중요하다. 사용자는 한국에 있으므로 KST로 보여준다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /**
     * 날짜에는 <b>언제나 요일을 붙인다</b> — {@code 2026년 8월 17일(월)}.
     *
     * <p>값이 언제 것인지를 사람이 실제로 판단하는 단위가 요일이다. "8월 15일 종가"만 보면
     * 그게 금요일 종가인지 주말에 멈춘 값인지 세어 봐야 알고, 일주일치 날씨에서 찾는 것도
     * 날짜가 아니라 "이번 주말"이다. 환율의 {@code (고시)}, 증시의 {@code (종가)}처럼
     * <b>값의 성격을 밝히는 일</b>의 연장이다.
     *
     * <p>⚠️ {@link Locale#KOREAN}을 명시하지 않으면 서버 로케일에 따라 {@code Mon}으로 나온다.
     */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일(E) HH:mm:ss", Locale.KOREAN);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일(E)", Locale.KOREAN);

    /**
     * 연도를 되풀이하지 않는 자리 — 범위의 끝({@code ~ 8월 24일(월)})과 날짜별 블록 제목.
     *
     * <p>둘이 같은 모양이라 상수도 하나다. 범위의 시작에 이미 연도가 적혀 있고, 날짜별 블록은
     * 맨 아래 기준 줄이 연도를 이고 있다.
     */
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN);

    private MessageFormatter() {
    }

    // --- 뉴스 ---------------------------------------------------------------

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
        message.append("<a href=\"").append(Html.escape(item.link())).append("\"><b>")
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
    public static List<String> formatNews(List<NewsItem> items) {
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
        return section(Command.NEWS)
                + "'" + Html.escape(query) + "'에 해당하는 최근 " + window.toHours() + "시간 뉴스를 찾지 못했습니다.";
    }

    // --- 환율 ---------------------------------------------------------------

    /**
     * 원/달러 환율.
     *
     * <p><b>{@code 1 USD = 1,412.17 KRW}로 쓴다.</b> 숫자만 두면 어느 쪽이 기준인지 드러나지 않는다.
     *
     * <p><b>출처와 기준일을 반드시 밝힌다.</b> 1순위가 죽어 수출입은행으로 폴백하면
     * 주말엔 며칠 전 값이 나가는데, 그걸 숨기면 고장이 아니라 거짓말이 된다.
     */
    public static String formatFx(FxRate rate) {
        String change = change(rate.changePercent());
        return section(Command.FX)
                + "1 USD = " + money(rate.rate()) + " KRW"
                + (change.isEmpty() ? "" : "\n" + change) + "\n\n"
                + Html.escape(rate.source().displayName()) + "\n\n"
                + basisOf(rate);
    }

    public static String fxUnavailable() {
        return section(Command.FX) + "환율을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    private static String basisOf(FxRate rate) {
        return rate.source().intraday()
                ? DATE_TIME.format(rate.asOf().atZone(SEOUL))
                : DATE.format(rate.asOf().atZone(SEOUL)) + " (고시)";
    }

    // --- 주식·지수 -----------------------------------------------------------

    public static String stockNotFound(String query) {
        return section(Command.STOCK)
                + "'" + Html.escape(query) + "'에 해당하는 종목을 찾지 못했습니다.";
    }

    /**
     * 증시 통 — <b>브리핑도 {@code /stock} 한 건도 이것 하나를 쓴다.</b>
     *
     * <p>국내(전일 종가)와 미국(현재가)은 <b>신선도가 다르다.</b> 한 덩어리로 붙이면
     * 어느 것이 종가인지 알 수 없으므로 무리를 갈라 각각 기준을 밝힌다. 종목이 하나뿐이면
     * 그 무리 하나만 남는다.
     *
     * <p><b>{@code (종가)}는 남긴다.</b> 국내는 전일 종가라 그 표시가 없으면 현재가로 읽힌다 —
     * 장식이 아니라 값의 성격이고, 낡은 값을 숨기면 거짓말이 된다.
     *
     * <p><b>조회처와 기준은 무리마다 그 무리 끝에 단다</b>(금융위원회·Financial Modeling Prep).
     * 다른 통과 같은 순서 — 값 다음에 출처, 한 줄 띄고 시각이다. 둘을 통 맨 아래에 모으면
     * 어느 무리 것인지 밝히려고 {@code 국내}·{@code 미국}을 접두사로 네 번 반복해야 한다.
     *
     * <p>종목코드·거래소는 여전히 적지 않는다 — 이름이 이미 그 종목을 가리킨다. 환산에 쓴
     * 환율도 적지 않는다 — 환율은 {@code /fx}와 브리핑 환율 통이 따로 있다.
     */
    public static String formatStock(List<StockQuote> quotes, FxRate fx) {
        if (quotes.isEmpty()) {
            return empty(Command.STOCK);
        }
        // ⚠️ 지역으로 가른다. 예전에는 realtime으로 갈랐는데, 국내가 전일 종가뿐이던 시절에만
        // 맞는 가정이었다 — 국내에 실시간 출처가 붙으면 삼성전자가 「미국」에 찍힌다
        StringBuilder message = new StringBuilder(title(Command.STOCK));
        for (StockQuote.Market market : StockQuote.Market.values()) {
            appendGroup(message, market, quotes.stream()
                    .filter(quote -> quote.market() == market).toList(), fx);
        }

        // 환율 줄을 붙이지 않는다. 브리핑은 환율 통을 이 통 바로 앞에 보내므로 중복이다
        return message.toString();
    }

    /**
     * 무리의 조회처 — 꼬리를 <b>무리마다</b> 단다.
     *
     * <p>지금은 무리와 조회처가 1:1이다({@code realtime=false}면 공공데이터포털, 아니면 FMP).
     * 그래도 여럿을 받아 두는 이유는 국내에 현재가 출처를 하나 더 붙이는 날 이 줄이 조용히
     * 거짓말이 되지 않게 하기 위해서다 — 그날 모양은 {@link #sourceLines}가 정한다.
     */
    private static String sourcesOf(List<StockQuote> quotes) {
        return sourceLines(quotes.stream().map(StockQuote::source)
                .distinct().sorted().map(StockSource::displayName));
    }

    /**
     * 출처 줄 — <b>둘 이상이면 한 줄에 하나씩 내려 적는다.</b>
     *
     * <p>{@code A · B}로 잇지 않는다. 그렇다고 사이를 빈 줄로 벌리지도 않는다 —
     * <b>출처는 여럿이어도 블록 하나</b>이기 때문이다. 이 통의 규칙이 그대로 걸린다:
     * 빈 줄은 블록 사이, 한 줄은 블록 안. 그래서 출처 덩어리와 기준 시각 사이만 빈 줄이다.
     *
     * <p><b>증시와 날씨가 이 하나를 함께 쓴다</b> — 통마다 규칙이 갈리지 않게 하려고 뽑았다.
     * 제네릭 소거 탓에 {@code List}를 받는 오버로드가 성립하지 않아, 타입을 벗긴 표시 이름만 받는다.
     *
     * <p>이스케이프는 <b>여기서 이름마다</b> 한다. 이어붙인 뒤 통째로 하면 구분자까지 대상이
     * 되는 모양이라 의도가 흐리고, 호출부가 한 번 더 하면 {@code S&P 500}이 두 번 이스케이프된다.
     */
    private static String sourceLines(Stream<String> displayNames) {
        return displayNames.map(Html::escape).collect(Collectors.joining("\n"));
    }

    /**
     * 무리 하나. 비어 있으면 제목도 남기지 않는다.
     *
     * <p>굵게는 <b>제목에만</b> 쓴다 — 값까지 굵으면 무엇이 계층인지 드러나지 않는다.
     *
     * <p><b>종목 하나가 블록 하나다</b> — 이름을 제 줄에 올리고 블록끼리는 빈 줄로 가른다.
     * 코인 통이 거래소마다 그렇게 하는 것과 같은 규칙이다. 예전에는 {@code 삼성전자 82,000 KRW}가
     * 한 줄이고 종목 사이도 한 줄이라, 이름·값·환산·등락률이 줄줄이 이어져 어디까지가 한 종목인지
     * 읽는 사람이 셀 수 없었다.
     */
    private static void appendGroup(StringBuilder message, StockQuote.Market market,
                                    List<StockQuote> quotes, FxRate fx) {
        if (quotes.isEmpty()) {
            return;
        }
        // 성격마다 제 기준이 있다 — 한 무리에 실시간(KIS)과 전일 종가(폴백)가 섞일 수 있다.
        // 브리핑은 지수와 종목을 따로 조회하므로 하나만 폴백하는 일이 실제로 난다
        Instant live = basisOf(quotes, true);
        Instant closing = basisOf(quotes, false);
        message.append("\n\n<b>").append(market.title()).append("</b>");

        for (StockQuote quote : quotes) {
            // 블록 사이는 빈 줄 — 굵은 무리 제목 다음도 마찬가지다
            message.append("\n\n").append(Html.escape(quote.name()))
                    .append("\n").append(priceOf(quote));
            // 무리 기준과 어긋난 줄에만 표시한다. 맨 밑 기준 줄이 그 값까지 대표하는 것처럼
            // 보이면 거짓말이 된다. 값의 날짜라 값 줄에 함께 둔다.
            //
            // ⚠️ 시각이 아니라 <b>날짜</b>로 비교한다. Instant로 비교하면 미국 무리가 통째로 걸렸다 —
            // at이 심볼마다 제 FMP 체결 초라(FmpStockClient) 넷이 같은 초일 리가 없어서,
            // 가장 최근 것 하나만 빼고 전부 날짜가 붙었다. 그런데 찍히는 값은 맨 밑 기준 줄과
            // 같은 날짜라 알려 주는 것이 없었고, /stock은 한 건이라 이 줄에 닿지 못해
            // 같은 값이 알람과 검색에서 두 모양으로 나갔다.
            //
            // 날짜로 비교하면 남는 경우가 "진짜 다른 날"뿐이다 — 국내 무리가 그렇다.
            // 공공데이터포털로 내려앉았을 때 지수와 종목이 각자 날짜를 뒤로 감아 찾으므로
            // 하루가 어긋날 수 있고, 그때는 반드시 밝혀야 한다.
            //
            // ⚠️ <b>제 성격의 기준</b>과 견준다. 무리 기준(가장 최근)과 견주면, 실시간과 종가가
            // 섞인 무리에서 종가 줄마다 날짜가 붙어 값 줄이 지저분해진다 — 그 줄은 낡은 것이
            // 아니라 성격이 다른 것이고, 성격은 아래 꼬리가 밝힌다.
            if (!sameDay(quote.at(), quote.realtime() ? live : closing)) {
                message.append(" · ").append(DATE.format(quote.at().atZone(SEOUL)));
            }
            // 값 → 원화 환산 → 등락률 순으로 각각 제 줄에. 환산값과 등락률을 한 줄에 붙이면 엉킨다
            if (convertible(quote, fx)) {
                message.append("\n").append(money(krw(quote.price(), fx))).append(" KRW");
            }
            appendChangeLine(message, quote.changePercent());
        }
        // 무리 하나가 통 하나처럼 끝맺는다 — 값 다음에 출처, 한 줄 띄고 기준.
        // 두 무리 것을 맨 아래에 모으면 "국내"·"미국"을 접두사로 네 번 반복해야 한다
        message.append("\n\n").append(sourcesOf(quotes))
                .append("\n\n").append(basisLines(live, closing));
    }

    /** 두 시각이 KST 같은 날인가 — 값의 신선도를 가르는 단위는 초가 아니라 하루다. */
    private static boolean sameDay(Instant left, Instant right) {
        return left.atZone(SEOUL).toLocalDate().equals(right.atZone(SEOUL).toLocalDate());
    }

    /**
     * 그 성격의 기준 시각 — <b>가장 최근 것</b>이다. 그 성격이 없으면 {@code null}.
     *
     * <p>첫 줄이 아니라 가장 최근 것을 고른다. 미국 무리는 심볼마다 제 체결 초가 와서
     * 넷이 같은 초일 리가 없다.
     */
    private static Instant basisOf(List<StockQuote> quotes, boolean realtime) {
        return quotes.stream().filter(quote -> quote.realtime() == realtime)
                .map(StockQuote::at).max(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * 꼬리의 기준 — <b>성격마다 한 줄</b>이고 실시간이 위다.
     *
     * <p>출처가 여럿이면 한 줄에 하나씩 쌓는 것과 같은 규칙이다({@link #sourceLines}).
     * 값 줄에 붙이지 않는 이유도 같다 — 넷 중 하나가 폴백했을 뿐인데 값마다 꼬리표를 달면
     * 읽는 줄이 지저분해진다. <b>대신 어느 값이 낡았는지를 이름으로 짚지는 않는다</b>:
     * 출처 줄이 이미 같은 맞바꿈을 하고 있고, 날씨에서도 같은 판단을 했다.
     *
     * <p>성격이 하나뿐인 평상시에는 한 줄이므로 화면이 예전과 한 글자도 다르지 않다.
     */
    private static String basisLines(Instant live, Instant closing) {
        StringBuilder lines = new StringBuilder();
        if (live != null) {
            lines.append(DATE_TIME.format(live.atZone(SEOUL)));
        }
        if (closing != null) {
            lines.append(live == null ? "" : "\n")
                    .append(DATE.format(closing.atZone(SEOUL))).append(" (종가)");
        }
        return lines.toString();
    }

    // --- 코인 ---------------------------------------------------------------

    /**
     * 코인 통 — <b>브리핑도 {@code /crypto} 한 건도 이것 하나를 쓴다.</b>
     * 24시간 거래되므로 기준일이 아니라 시각을 쓴다.
     *
     * <p>코인 제목은 <b>굵은 티커 + 한글 이름</b>이다({@code <b>BTC</b> 비트코인} — {@link #coinTitle}).
     * 마켓 코드({@code KRW-BTC})나 바이낸스 심볼은 적지 않는다. 거래소는 값 줄에 이름으로 적혀 있다.
     *
     * <p><b>값이 없는 코인도 빼지 않는다.</b> 예전에는 브리핑에서만 뺐는데, 그 근거였던
     * "{@code KRW-USDT}의 바이낸스 미상장은 매일 나가는 소음"이 사라졌다 — 테더도
     * {@code USDTUSD}로 값이 나온다. 남는 것은 진짜 장애뿐이고 그건 알려야 한다.
     */
    public static String formatCrypto(List<CryptoQuote> quotes, FxRate fx) {
        if (quotes.isEmpty()) {
            return empty(Command.CRYPTO);
        }
        StringBuilder message = new StringBuilder(section(Command.CRYPTO));
        boolean first = true;
        for (CryptoQuote quote : quotes) {
            message.append(first ? "" : "\n\n")
                    .append(coinTitle(quote)).append("\n\n")
                    .append(exchangeLines(quote, fx));
            first = false;
        }
        // 출처 자리는 비운다 — 코인은 출처가 거래소이고 그건 코인마다 둘씩이라 값 줄에
        // 이름으로 이미 적혀 있다. 맨 아래에 남는 것은 기준 시각뿐이다.
        //
        // ⚠️ 첫 코인의 시각이 아니라 <b>가장 최근 값</b>을 쓴다(증시의 basisOf와 같은 규칙).
        // at은 코인마다 제 업비트 체결 시각이고 미상장 코인은 Instant.now()로 채워지는데,
        // 첫 코인이 그런 경우면 조회 시각이 나머지 전부의 체결 시각인 척 맨 아래에 앉는다.
        quotes.stream().map(CryptoQuote::at).max(Comparator.naturalOrder()).ifPresent(basis ->
                message.append("\n\n").append(DATE_TIME.format(basis.atZone(SEOUL))));
        return message.toString();
    }

    /**
     * 코인 하나의 제목 — <b>굵은 티커에 한글 이름을 곁들인다</b>({@code <b>BTC</b> 비트코인}).
     *
     * <p>굵은 것은 티커다 — 코인은 사람들이 티커로 부르고, 값을 찾을 때 눈이 먼저 닿는 것도
     * 그쪽이다. 다만 티커만으로는 무엇인지 모르는 코인이 많아 한글 이름을 평문으로 뒤에 단다.
     * 무리 제목이 조회처를 뒤에 다는 것과 같은 모양이다.
     *
     * <p><b>이름이 티커와 같으면 붙이지 않는다.</b> 업비트에 없는 코인은 한글 이름을 확인할 곳이
     * 없어 {@code name}에 티커가 그대로 들어온다({@code BNB}) — 그대로 두면 {@code BNB BNB}가 된다.
     */
    private static String coinTitle(CryptoQuote quote) {
        String ticker = quote.ticker();
        String title = "<b>" + Html.escape(ticker) + "</b>";
        return quote.name() == null || quote.name().equalsIgnoreCase(ticker)
                ? title
                : title + " " + Html.escape(quote.name());
    }

    /**
     * 블록마다 <b>이름 한 줄, 그 아래 값 줄</b> — <b>업비트 먼저, 바이낸스 다음, 김프 마지막.</b>
     * 블록 사이는 빈 줄로 가른다.
     *
     * <p><b>김프도 블록이다</b> — 라벨을 윗줄에 올리고 값을 아랫줄에 둔다. 예전에는
     * {@code 김프 🔴 +2.63%}처럼 한 줄에 붙어 있어 거래소 블록들과 모양이 갈렸다.
     *
     * <p><b>값이 없는 쪽도 이유를 적는다.</b> 줄을 빼 버리면 그 거래소를 조회하지 않은 것처럼
     * 보이고, 무엇보다 다시 시도해야 할지 알 수 없다. 그래서 이유를 갈라 쓴다 — {@code 미상장}은
     * 영영 안 나오는 것이고 {@code 조회 실패}는 잠시 뒤 다시 치면 되는 것이다.
     *
     * <p><b>바이낸스 값의 단위는 코인이 안다</b>({@link CryptoQuote#binanceUnit()}) — 테더만
     * {@code USD}로 갈린다. 원화 환산은 어느 쪽이든 <b>환율(USD/KRW)</b>을 곱한다.
     *
     * <p>{@code fx}가 없으면 원화 환산과 김프가 함께 빠지고 USDT/USD 값만 나간다 —
     * 환산을 못 한다고 시세를 빼는 것은 과하다.
     */
    private static String exchangeLines(CryptoQuote quote, FxRate fx) {
        StringBuilder lines = new StringBuilder();
        // 업비트는 원화가 기본이라 환산 줄이 없다 — 이름 한 줄, 값 한 줄, 등락률 한 줄
        if (quote.upbit().hasPrice()) {
            lines.append("업비트\n").append(money(quote.upbit().price())).append(" KRW");
            appendChangeLine(lines, quote.upbit().changePercent());
        } else {
            lines.append("업비트 ").append(reasonOf(quote.upbit()));
        }

        lines.append("\n\n");
        if (!quote.binance().hasPrice()) {
            return lines.append("바이낸스 ").append(reasonOf(quote.binance())).toString();
        }
        // 바이낸스: 이름 한 줄, 그 아래 값 → 원화 환산 → 등락률, 각각 제 줄에
        lines.append("바이낸스\n").append(money(quote.binance().price()))
                .append(" ").append(quote.binanceUnit());
        if (fx != null) {
            lines.append("\n").append(money(krw(quote.binance().price(), fx))).append(" KRW");
        }
        appendChangeLine(lines, quote.binance().changePercent());

        BigDecimal premium = premium(quote, fx);
        if (premium != null) {
            lines.append("\n\n김프\n").append(change(premium));
        }
        return lines.toString();
    }

    /**
     * 김치 프리미엄 — <b>국내 값이 글로벌 값보다 얼마나 비싼가.</b>
     *
     * <pre>업비트(KRW) ÷ (바이낸스 가격 × 환율) − 1</pre>
     *
     * <p><b>분모는 환율(USD/KRW)이어야 한다.</b> 업비트 {@code KRW-USDT} 실거래가로 나누면
     * 테더 자신의 프리미엄이 상쇄돼 김프가 실제보다 작게 나온다. 화면의 두 원화값(업비트 값과
     * 바이낸스 환산값)이 같은 환율 위에 서 있어야 그 차이가 곧 이 숫자가 되어 검산도 된다.
     *
     * <p>보통 코인은 바이낸스 호가가 USDT지만 1 USDT를 1 USD로 보고 곱한다 — 김치 프리미엄의
     * 통용 정의이고, 테더는 애초에 USD 호가라 그대로 맞다.
     *
     * @return 퍼센트. 두 거래소 값이나 환율이 없으면 {@code null} — 그때는 줄 자체가 없다
     */
    private static BigDecimal premium(CryptoQuote quote, FxRate fx) {
        if (fx == null || !quote.upbit().hasPrice() || !quote.binance().hasPrice()) {
            return null;
        }
        BigDecimal global = quote.binance().price().multiply(fx.rate());
        if (global.signum() == 0) {
            return null;
        }
        return quote.upbit().price().divide(global, 6, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100));
    }

    /** 등락률을 제 줄에 붙인다 — 값과 원화 환산 다음이다. 없으면 붙이지 않는다. */
    private static void appendChangeLine(StringBuilder lines, BigDecimal percent) {
        String change = change(percent);
        if (!change.isEmpty()) {
            lines.append("\n").append(change);
        }
    }

    /** 값이 없는 이유. 사용자가 다시 시도해야 하는지가 여기서 갈린다. */
    private static String reasonOf(CryptoQuote.Quote quote) {
        return switch (quote.state()) {
            case NOT_LISTED -> "미상장";
            case FAILED -> "조회 실패";
            // 도달 불가다 — 호출부가 hasPrice()의 else이고 Quote.of는 값이 없으면 FAILED를 준다.
            // enum switch 완전성 때문에 남기지만, 빈 문자열을 돌려주면 "업비트 " 하나가
            // 꼬리에 공백을 달고 나간다. 여기 오면 버그이므로 그렇다고 적는다
            case OK -> throw new IllegalStateException("값이 있는 시세를 결측 사유로 물었습니다");
        };
    }

    public static String cryptoNotFound(String query) {
        return section(Command.CRYPTO)
                + "'" + Html.escape(query) + "'에 해당하는 코인을 찾지 못했습니다.\n\n"
                + "업비트 또는 바이낸스에 상장된 이름이나 심볼로 입력해 주세요.\n\n"
                + "예) /crypto 비트코인 · /crypto BTC";
    }

    // --- 날씨 ---------------------------------------------------------------

    /**
     * 날씨 통 — <b>오전 6시 알람도 {@code /weather} 한 지역도 이것 하나를 쓴다.</b>
     *
     * <p>{@code /stock}·{@code /crypto}와 같은 규칙이다 — 검색 답은 알람 통에 지역이 하나뿐인
     * 경우일 뿐이므로 포매터를 나누지 않는다. 나누면 같은 값이 두 모양으로 그려진다.
     *
     * <p><b>답은 언제나 일일 예보다.</b> 현재 기온을 적지 않는다 — 현재값과 일일값을 섞으면
     * "지금 21°C인데 최고가 29°C"처럼 두 시간축이 한 화면에 서고, 6시 알람에서 그 시각 기온은
     * 하루를 계획하는 데 쓸모도 없다.
     *
     * <p><b>계층이 세 겹으로 겹치지 않는다.</b> 알람은 지역 넷 × 하루, 검색은 지역 하나 × 여러 날이라
     * 「지역 → 날짜 → 값」이 동시에 서는 경우가 없다. 그래서 하루짜리 답은 날짜를 블록 제목으로
     * 올리지 않고 맨 아래 기준 줄에만 둔다.
     */
    public static String formatWeather(List<Weather> places) {
        if (places.isEmpty()) {
            return empty(Command.WEATHER);
        }
        StringBuilder message = new StringBuilder(title(Command.WEATHER));
        for (Weather place : places) {
            message.append("\n\n<b>").append(Html.escape(place.place().displayName())).append("</b>");
            appendDays(message, place.days());
        }
        // 출처와 기준은 통 하나처럼 끝맺는다 — 값 다음 빈 줄, 출처, 빈 줄, 기준.
        // 출처가 갈리면 하단에 각자 제 블록으로 쌓인다. 범위는 한 번에 조회하므로 같다
        places.stream().findFirst().ifPresent(head ->
                message.append("\n\n").append(weatherSourcesOf(places))
                        .append("\n\n").append(basisOf(head)));
        return message.toString();
    }

    /**
     * 하루면 값만, 여러 날이면 <b>하루가 블록 하나</b>다.
     *
     * <p>하루짜리에 날짜 제목을 붙이지 않는 이유는 그 날짜가 이미 맨 아래 기준 줄에 있기
     * 때문이다 — 같은 사실을 두 번 적으면 어느 쪽이 계층인지 흐려진다.
     */
    private static void appendDays(StringBuilder message, List<Weather.Daily> days) {
        boolean single = days.size() == 1;
        for (Weather.Daily day : days) {
            message.append("\n\n");
            if (!single) {
                message.append(SHORT_DATE.format(day.date())).append("\n");
            }
            if (day.sky().known()) {
                message.append(day.sky().label()).append("\n");
            }
            message.append(oneDecimal(day.low())).append("°C / ")
                    .append(oneDecimal(day.high())).append("°C");
            appendRain(message, day);
        }
    }

    /**
     * 강수 — <b>출처가 주는 것을 제 이름으로 적는다.</b>
     *
     * <p>확률은 예보만 준다 — 지나간 날은 확률이라는 개념 자체가 없고, 예보 응답에서 확률이
     * 빠지면 강수량으로 떨어진다. <b>강수량을 확률이라 부르지 않는다</b> — 값을 다른 것인 척
     * 하지 않는다는 규칙이 {@code (종가)}·{@code (고시)}와 같은 자리에서 여기에도 걸린다.
     */
    private static void appendRain(StringBuilder message, Weather.Daily day) {
        if (day.rainChance() != null) {
            message.append("\n강수확률 ").append(day.rainChance()).append("%");
        } else if (day.rainAmount() != null) {
            message.append("\n강수량 ").append(oneDecimal(day.rainAmount())).append("mm");
        }
    }

    /**
     * 조회처. 지역마다 폴백이 갈릴 수 있으므로 <b>하단에 모은다</b> — 모양은 {@link #sourceLines}가
     * 정한다(증시와 같은 규칙이다).
     *
     * <p><b>지역 블록에는 출처를 달지 않는다.</b> 넷 중 하나가 폴백했을 뿐인데 지역마다 달면
     * 같은 이름이 다섯 번 찍힌다. 대신 <b>어느 지역이 폴백했는지를 이름으로 밝히지 않는다</b> —
     * 그래도 증상은 본문에 남는다. 그 지역만 강수확률이 아니라 강수량으로 바뀐다({@code appendRain}).
     *
     * <p><b>선언 순으로 정렬한다.</b> 등장 순이면 첫 지역이 폴백했을 때 2순위가 위로 올라오는데,
     * 세로로 쌓이면 그 순서가 눈에 보인다. {@code WeatherSource}의 선언 순이 곧 이중화
     * 순서({@code WeatherService})라 1순위가 언제나 위다.
     *
     * <p>이름이 {@code sourcesOf}가 아닌 이유는 제네릭 소거 때문이다 — 증시 쪽과 인자 목록이
     * 같아져 오버로드가 성립하지 않는다.
     */
    private static String weatherSourcesOf(List<Weather> places) {
        return sourceLines(places.stream().map(Weather::source)
                .distinct().sorted().map(WeatherSource::displayName));
    }

    /**
     * 기준 줄 — 날짜와 <b>그 값의 성격</b>이다.
     *
     * <p>하루치 값에 시각을 붙이면 그 시각의 값인 것처럼 읽힌다. 그래서 {@code (종가)}·
     * {@code (고시)}와 같은 규칙으로 {@code (예보)}·{@code (실측)}을 붙인다 — 지나간 날은
     * 예보가 아니라 실제로 그랬던 값이다.
     */
    private static String basisOf(Weather weather) {
        String kind = weather.source().forecast() ? " (예보)" : " (실측)";
        return weather.from().equals(weather.to())
                ? DATE.format(weather.from()) + kind
                : DATE.format(weather.from()) + " ~ " + SHORT_DATE.format(weather.to()) + kind;
    }

    public static String weatherNotFound(String query) {
        return section(Command.WEATHER)
                + "'" + Html.escape(query) + "'에 해당하는 지역을 찾지 못했습니다.\n\n"
                + "도시나 지역 이름으로 입력해 주세요.\n\n"
                + "예) /weather 서울 · /weather 내일 성남 · /weather 일주일치 파리";
    }

    /**
     * 지역을 안 적었다 — <b>우리가 골라 주지 않고 묻는다.</b>
     *
     * <p>사용자가 고르지 않은 지역으로 답하면 그 값이 맞는지 사용자가 알 수 없다. 인자 없이
     * {@code /stock}을 쳤을 때 사용법을 띄우는 것과 같은 자리다.
     *
     * <p>못 찾은 것({@link #weatherNotFound})과 다른 답이다 — 이미 지역을 적은 사람에게
     * 적으라고 하면 안 된다.
     */
    public static String weatherNeedsPlace() {
        return section(Command.WEATHER)
                + "어느 지역인지 함께 적어 주세요.\n\n"
                + "예) /weather 서울 · /weather 내일 성남 · /weather 일주일치 파리";
    }

    /**
     * 날짜를 적었는데 펼 수 없었다 — <b>조용히 오늘로 만들지 않는다.</b>
     *
     * <p>{@code 2025년 8월}처럼 일자 없이 연·월만 적으면 펼 날이 없다. 그때 오늘 날씨를
     * 답하면 사용자는 자기가 적은 날짜가 무시된 줄 모른다 — 그럴듯한 숫자가 나와서 더 나쁘다.
     */
    public static String weatherUnreadableDate() {
        return section(Command.WEATHER)
                + "날짜를 읽지 못했습니다. 하루를 짚어 적어 주세요.\n\n"
                + "예) /weather 16일 서울 · /weather 8월 16일 서울 · /weather 2025년 8월 19일 서울";
    }

    /** 예보가 닿지 않는 날. <b>며칠까지 되는지를 함께 말한다</b> — 빈손만 주면 고장으로 보인다. */
    public static String weatherTooFarAhead() {
        return section(Command.WEATHER)
                + "날씨 예보는 오늘부터 " + WeatherPeriod.MAX_FORECAST_DAYS + "일까지만 볼 수 있습니다.";
    }

    public static String weatherUnavailable() {
        return section(Command.WEATHER) + "날씨를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    // --- 공통 ---------------------------------------------------------------

    /**
     * 굵은 제목 한 줄과 그 아래 빈 줄 — <b>모든 메시지가 이것으로 시작한다.</b>
     *
     * <p>실패·안내 답에도 붙인다. 같은 명령의 답인데 성공했을 때만 제목이 있으면 모양이
     * 갈리고, 그룹 채팅에서 맨몸 문장 하나만 튀어나오면 무엇에 대한 답인지 알 수 없다.
     */
    private static String section(Command command) {
        return title(command) + "\n\n";
    }

    /**
     * 보여줄 값이 하나도 없을 때 — <b>네 통이 같은 문장으로 답한다.</b>
     *
     * <p>예전에는 포매터마다 달랐다: 증시·날씨는 굵은 제목 한 마디만 내보내고(값도 출처도
     * 시각도 없이), 코인은 거기에 빈 줄이 붙고, 뉴스만 사실을 말했다. 지금은 호출자가 다
     * 막고 있어 도달하지 않지만, <b>넷이 제각각인 것 자체가 다음에 누가 막는 걸 잊었을 때
     * 무슨 일이 벌어질지 알 수 없게 만든다.</b>
     */
    private static String empty(Command command) {
        return section(command) + "지금은 가져올 수 있는 값이 없습니다.";
    }


    /**
     * 제목 줄만. 아래에 무리를 바로 붙이는 통(증시·코인·뉴스 브리핑)이 쓴다.
     *
     * <p>제목 문자열이 {@link Command}에만 있으므로 검색 답과 브리핑 답의 제목이 갈릴 수 없다.
     *
     * <p><b>검색어를 붙이지 않는다.</b> 붙이던 때가 있었는데({@code 증시 '삼성전자'}) 답이
     * <b>답글로</b> 나가서 텔레그램이 원 명령을 바로 위에 인용해 그린다 — 같은 말을 두 번 하는
     * 것이었다({@code TelegramClient}의 {@code replyToMessageId} 참조). 덕분에 검색 답과 알람이
     * 글자 그대로 같은 제목을 쓴다.
     */
    private static String title(Command command) {
        return "<b>" + Html.escape(command.section()) + "</b>";
    }

    /**
     * 등락률 한 조각 — <b>상승 🔴 +1.20% / 하락 🔵 -1.20% / 보합 0.00%</b>.
     *
     * <p><b>부호를 숫자 옆에 붙인다.</b> 원과 겹친다고 생략했었는데, 이모지는 기기·글꼴에 따라
     * 색이 흐릿하거나 아예 안 뜨는 곳이 있어 그때는 방향이 통째로 사라진다. 부호는 어디서나
     * 같은 글자다. 보합은 방향이 없으므로 부호도 붙이지 않는다.
     *
     * <p><b>{@code null}이면 빈 문자열이다.</b> "못 구했다"와 "보합(0%)"은 다른 말이므로
     * 못 구한 값을 {@code 0.00%}로 찍어서는 안 된다. 그때는 시세만 나간다.
     *
     * <p>소수 둘째 자리로 맞춘다. 출처마다 자릿수가 제각각이라
     * ({@code 0.99586}·{@code 4.89}·{@code -1.451}) 그대로 두면 한 화면에서 정밀도가 들쭉날쭉해진다.
     */
    private static String change(BigDecimal percent) {
        if (percent == null) {
            return "";
        }
        BigDecimal rounded = percent.setScale(2, RoundingMode.HALF_UP);
        int direction = rounded.signum();
        if (direction == 0) {
            return "0.00%";
        }
        return (direction > 0 ? "🔴 +" : "🔵 -") + rounded.abs().toPlainString() + "%";
    }

    /** 통화 코드까지 붙인 값. 지수는 통화가 없어 숫자만 나간다. */
    private static String priceOf(StockQuote quote) {
        return switch (quote.currency()) {
            case NONE -> money(quote.price());
            case KRW -> money(quote.price()) + " KRW";
            case USD -> money(quote.price()) + " USD";
        };
    }

    /** 환율이 없으면 달러만 보낸다 — 환산을 못 한다고 시세를 빼는 것은 과하다. */
    private static boolean convertible(StockQuote quote, FxRate fx) {
        return quote.currency().convertible() && fx != null;
    }

    /** 원 단위로 반올림한다 — 소수점 아래 원화는 읽는 사람에게 의미가 없다. */
    private static BigDecimal krw(BigDecimal usd, FxRate fx) {
        return usd.multiply(fx.rate()).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 천 단위로 끊는다. 코인 가격은 자릿수 폭이 커서(89,848,000 ~ 0.5) 구분이 없으면 읽기 어렵다.
     *
     * <p>소수점 이하는 있을 때만 남긴다 — {@code 89848000.00000000}을 그대로 내보내면 안 된다.
     */
    static String money(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        BigDecimal trimmed = amount.stripTrailingZeros();
        int scale = Math.max(trimmed.scale(), 0);
        NumberFormat format = NumberFormat.getNumberInstance(Locale.KOREA);
        format.setMinimumFractionDigits(scale);
        format.setMaximumFractionDigits(scale);
        return format.format(trimmed);
    }

    /**
     * 온도·강수량처럼 <b>자릿수가 정해진 값</b> — 소수 한 자리로 맞춘다.
     *
     * <p><b>{@link #money}를 쓰면 안 된다.</b> 그쪽은 뒤 0을 떼므로 {@code 21.0}이 {@code 21}이
     * 되어 바로 옆 {@code 26.4}와 자릿수가 갈린다. 실제로 그 상태였고 테스트에도
     * {@code 22°C / 30.5°C}로 굳어 있었다 — 한 줄 안에서 정밀도가 들쭉날쭉했다.
     *
     * <p>둘을 나누는 기준은 <b>값의 폭</b>이다. 온도와 강수량은 폭이 좁아 자릿수를 고정해도
     * 잃는 것이 없지만, 가격은 89,848,000부터 0.5까지라 고정하면 어느 한쪽이 망가진다.
     * {@code change()}가 등락률을 둘째 자리로 맞추는 것과 같은 판단이다.
     */
    private static String oneDecimal(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.KOREA);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(amount);
    }

    /** 인자가 필요한 명령을 인자 없이 보냈을 때. 명령마다 예시가 다르다. */
    public static String usage(Command command) {
        return section(command)
                + "검색어를 함께 입력해 주세요.\n\n"
                + "예) " + Html.escape(command.example());
    }

    /**
     * {@code /}로 시작하는 모르는 명령에만 띄운다.
     *
     * <p>일반 대화에는 반응하지 않는다 — 그룹 채팅이 오염된다.
     *
     * <p>{@link #help()}를 통째로 붙이지 않는다. 그러면 굵은 제목이 둘 연달아 찍혀
     * 무엇이 이 메시지의 제목인지 흐려진다 — 목록 본문만 빌린다.
     */
    public static String unknownCommand() {
        return "<b>모르는 명령</b>\n\n입력하신 명령을 찾지 못했습니다." + commandList();
    }

    /**
     * 도움말.
     *
     * <p><b>방·토픽 번호를 적지 않는다.</b> 사용자가 볼 화면에 내부 배관을 늘어놓을 이유가
     * 없다. 설정에 넣을 그 값은 명령을 받을 때마다 {@code TelegramWebhookController}가 INFO
     * 로그로 남긴다 — 설정하는 사람은 로그를 보고, 쓰는 사람은 안 봐도 된다.
     */
    public static String help() {
        return title(Command.HELP) + commandList();
    }

    /** 명령 목록 본문. 도움말과 "모르는 명령"이 나눠 쓴다. */
    private static String commandList() {
        StringBuilder list = new StringBuilder();
        for (Command command : Command.values()) {
            list.append("\n\n").append(Html.escape(command.example())).append("\n")
                    .append(describe(command));
        }
        return list.toString();
    }

    /**
     * 목록에 적는 한 줄 설명 — <b>그 명령이 답하는 통의 이름이다.</b>
     *
     * <p>예전에는 여기가 {@code switch}로 여섯 갈래를 따로 들고 있었는데, 그건
     * {@link Command#section()}과 <b>같은 사실을 담은 두 번째 표</b>였고 이미 어긋나 있었다 —
     * 도움말은 {@code /stock}을 「주식」이라 적고 정작 답은 「증시」로 나갔다. {@code Command}가
     * "분기문이 아니라 상수가 직접 들고 있는 이유"로 적어 둔 바로 그 함정이다.
     *
     * <p>{@code HELP}만 예외다. 그 제목({@code 사용할 수 있는 명령})은 이 <b>목록 자체의
     * 제목</b>이라 목록 안에 그대로 적으면 한 통에서 같은 말을 두 번 하게 된다.
     */
    private static String describe(Command command) {
        return command == Command.HELP ? "도움말" : command.section();
    }
}
