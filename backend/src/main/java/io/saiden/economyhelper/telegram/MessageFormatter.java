package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.news.NewsItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
 * <p><b>모든 덩어리가 같은 모양이다: 굵은 제목 / 값 / 출처 / 시각.</b> 덩어리 사이는 빈 줄로
 * 가르고 시각은 언제나 맨 아래 단독이다. 출처가 없는 통(주식·코인)은 그 자리를 비운다.
 * 뉴스의 매체명은 환율의 {@code 유럽중앙은행}과 같은 <b>출처</b> 자리이며, 굵은 것은
 * 언제나 사용자가 읽으러 온 것 — 기사 제목이다.
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
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");

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
    public static String format(NewsItem item) {
        StringBuilder message = new StringBuilder();
        message.append("<a href=\"").append(Html.escape(item.link())).append("\"><b>")
                .append(Html.escape(item.title())).append("</b></a>");

        if (!item.body().isBlank()) {
            // 인용 블록으로 감싸면 제목과 본문이 갈려 다섯 건이 훨씬 잘 읽힌다
            message.append("\n\n<blockquote>").append(Html.escape(item.body())).append("</blockquote>");
        }
        if (!item.translated()) {
            // 왜 영문인지 밝히지 않으면 고장으로 보인다
            message.append("\n<i>번역이 일시적으로 불가해 원문 그대로 보냅니다.</i>");
        }
        // 출처와 시각 — 환율 통과 같은 자리, 같은 모양이다
        return message.append("\n\n").append(Html.escape(item.sourceName()))
                .append("\n\n").append(DATE_TIME.format(item.publishedAt().atZone(SEOUL)))
                .toString();
    }

    /**
     * 기사 여러 건을 한 통에 묶는다 — <b>브리핑과 검색이 같은 메서드를 쓴다.</b>
     *
     * <p>둘 다 오늘 발행분 중 점수 상위 몇 건이다. 담기는 기준이 같고 보여 주는 모양도
     * 같아야 하므로 여기를 나누지 않는다.
     *
     * <p><b>두 건 이상이면 앞에 번호를 붙인다.</b> 기사끼리는 빈 줄로만 갈라 두면 제목·요약·출처·시각이
     * 줄줄이 이어져 어디까지가 한 건인지 흐려진다. 번호가 그 경계를 못 박는다. 한 건뿐이면
     * 외로운 "1."이 오히려 어색하므로 붙이지 않는다.
     */
    public static String formatNews(List<NewsItem> items) {
        if (items.isEmpty()) {
            return section(Command.NEWS) + "지금은 가져올 수 있는 뉴스가 없습니다.";
        }
        StringBuilder message = new StringBuilder(title(Command.NEWS));
        boolean numbered = items.size() > 1;
        for (int i = 0; i < items.size(); i++) {
            message.append("\n\n");
            if (numbered) {
                message.append(i + 1).append(". ");
            }
            message.append(format(items.get(i)));
        }
        return message.toString();
    }

    public static String noResults(String query) {
        return section(Command.NEWS) + "'" + Html.escape(query) + "'에 해당하는 뉴스를 찾지 못했습니다.";
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

    /**
     * 종목·지수 하나 — <b>이름 / 값 / 근거</b> 세 덩어리다. 코인·환율과 같은 뼈대다.
     *
     * <p><b>{@code (종가)}는 남긴다.</b> 국내는 전일 종가라 그 표시가 없으면 현재가로 읽힌다 —
     * 장식이 아니라 값의 성격이고, 낡은 값을 숨기면 거짓말이 된다.
     *
     * <p>반대로 <b>조회처 이름(FMP·공공데이터포털)은 적지 않는다.</b> 사용자에게 의미 없는
     * 벤더명이고 코인도 적지 않는다. 종목코드·거래소도 같은 이유로 뺐다 — 이름이 이미 그 종목을
     * 가리킨다.
     *
     * <p>환산에 쓴 환율도 적지 않는다. 환율은 {@code /fx}와 브리핑 환율 통이 따로 있고,
     * 브리핑 증시 통에서는 이미 같은 이유로 뺐다.
     */
    public static String formatStock(StockQuote quote, FxRate fx) {
        StringBuilder message = new StringBuilder("<b>")
                .append(Html.escape(quote.name())).append("</b>\n\n")
                .append(priceOf(quote));

        // 값 → 원화 환산 → 등락률 순으로 각각 제 줄에. 환산값과 등락률을 한 줄에 붙이면 엉킨다
        if (convertible(quote, fx)) {
            message.append("\n약 ").append(money(krw(quote.price(), fx))).append(" KRW");
        }
        String change = change(quote.changePercent());
        if (!change.isEmpty()) {
            message.append("\n").append(change);
        }
        return message.append("\n\n").append(basisOf(quote)).toString();
    }

    /** 값의 시각. 전일 종가면 그 사실을 함께 적는다. */
    private static String basisOf(StockQuote quote) {
        return quote.realtime()
                ? DATE_TIME.format(quote.at().atZone(SEOUL))
                : DATE.format(quote.at().atZone(SEOUL)) + " (종가)";
    }

    public static String stockNotFound(String query) {
        return section(Command.STOCK)
                + "'" + Html.escape(query) + "'에 해당하는 종목을 찾지 못했습니다.";
    }

    /**
     * 아침 브리핑의 증시 통.
     *
     * <p>국내(전일 종가)와 미국(현재가)은 <b>신선도가 다르다.</b> 한 덩어리로 붙이면
     * 어느 것이 종가인지 알 수 없으므로 무리를 갈라 각각 기준을 밝힌다.
     */
    public static String formatStockDigest(List<StockQuote> quotes, FxRate fx) {
        List<StockQuote> closing = quotes.stream().filter(q -> !q.realtime()).toList();
        List<StockQuote> live = quotes.stream().filter(StockQuote::realtime).toList();

        StringBuilder message = new StringBuilder(title(Command.STOCK));
        appendGroup(message, "국내", closing, fx);
        appendGroup(message, "미국", live, fx);

        // 시각은 맨 밑에 단독으로 — 모든 통이 같은 자리에 둔다. 국내는 전일 종가고 미국은
        // 현재가라 기준이 서로 다르므로 무리 이름을 붙여 두 줄로 적는다
        StringBuilder basis = new StringBuilder();
        appendBasis(basis, "국내", closing);
        appendBasis(basis, "미국", live);
        if (!basis.isEmpty()) {
            message.append("\n\n").append(basis);
        }

        // 환율 줄을 붙이지 않는다. 브리핑은 환율 통을 이 통 바로 앞에 보내므로 중복이다
        return message.toString();
    }

    /**
     * 무리 하나. 비어 있으면 제목도 남기지 않는다.
     *
     * <p>굵게는 <b>제목에만</b> 쓴다 — 값까지 굵으면 무엇이 계층인지 드러나지 않는다.
     */
    private static void appendGroup(StringBuilder message, String title,
                                    List<StockQuote> quotes, FxRate fx) {
        if (quotes.isEmpty()) {
            return;
        }
        Instant basis = basisOf(quotes);
        message.append("\n\n<b>").append(title).append("</b>");

        for (StockQuote quote : quotes) {
            message.append("\n").append(Html.escape(quote.name())).append(" ")
                    .append(priceOf(quote));
            // 무리 기준과 어긋난 줄에만 표시한다. 맨 밑 기준 줄이 그 값까지 대표하는 것처럼
            // 보이면 거짓말이 된다. 값의 시각이라 값 줄에 함께 둔다
            if (!quote.at().equals(basis)) {
                message.append(" · ").append(DATE.format(quote.at().atZone(SEOUL)));
            }
            // 값 → 원화 환산 → 등락률 순으로 각각 제 줄에. 환산값과 등락률을 한 줄에 붙이면 엉킨다
            if (convertible(quote, fx)) {
                message.append("\n").append(money(krw(quote.price(), fx))).append(" KRW");
            }
            String change = change(quote.changePercent());
            if (!change.isEmpty()) {
                message.append("\n").append(change);
            }
        }
    }

    private static void appendBasis(StringBuilder basis, String title, List<StockQuote> quotes) {
        if (quotes.isEmpty()) {
            return;
        }
        basis.append(basis.isEmpty() ? "" : "\n")
                .append(title).append(" ").append(basisOf(quotes.get(0), basisOf(quotes)));
    }

    /** 무리의 기준 시각 — 가장 최근 값이다. */
    private static Instant basisOf(List<StockQuote> quotes) {
        return quotes.stream().map(StockQuote::at).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static String basisOf(StockQuote sample, Instant basis) {
        return sample.realtime()
                ? DATE_TIME.format(basis.atZone(SEOUL))
                : DATE.format(basis.atZone(SEOUL)) + " (종가)";
    }

    // --- 코인 ---------------------------------------------------------------

    /**
     * 코인 현재가.
     *
     * <p>등락률을 넣지 않는다 — {@code CLAUDE.md}가 요구하는 것은 "현재 가격"이고,
     * 명령마다 정보 밀도가 달라지는 것도 피한다.
     */
    public static String formatCrypto(CryptoQuote quote, BigDecimal usdtKrw) {
        // 제목은 이름이 아니라 티커(BTC)다 — 코인은 사람들이 티커로 부른다. 마켓 코드(KRW-BTC)나
        // 바이낸스 심볼을 따로 적지 않는 건 그대로다. 거래소는 값 줄에 이름으로 적혀 있다
        return "<b>" + Html.escape(quote.ticker()) + "</b>\n\n"
                + exchangeLines(quote, usdtKrw, true) + "\n\n"
                + DATE_TIME.format(quote.at().atZone(SEOUL));
    }

    /**
     * 아침 브리핑의 코인 통. 24시간 거래되므로 기준일이 아니라 시각을 쓴다.
     *
     * <p><b>값이 없는 거래소 줄은 뺀다.</b> 단건 {@code /crypto}와 갈리는 지점이다 —
     * 브리핑은 매일 같은 코인이 나가므로 {@code KRW-USDT}의 "바이낸스 미상장"은 첫날 이후
     * 정보가 아니라 소음이다. 사용자가 방금 물은 것이 아니라 우리가 매일 밀어 넣는 것이다.
     */
    public static String formatCryptoDigest(List<CryptoQuote> quotes, BigDecimal usdtKrw) {
        // 양쪽 다 값이 없으면 이름만 굵게 찍히고 아래가 빈다. 그런 코인은 통째로 뺀다
        List<CryptoQuote> shown = quotes.stream()
                .filter(quote -> quote.upbit().hasPrice() || quote.binance().hasPrice())
                .toList();

        StringBuilder message = new StringBuilder(title(Command.CRYPTO));
        for (CryptoQuote quote : shown) {
            message.append("\n\n<b>").append(Html.escape(quote.ticker())).append("</b>\n")
                    .append(exchangeLines(quote, usdtKrw, false));
        }
        // 시각은 맨 밑에 단독으로 — 모든 통이 같은 자리에 둔다
        shown.stream().findFirst().ifPresent(first ->
                message.append("\n\n").append(DATE_TIME.format(first.at().atZone(SEOUL))));
        return message.toString();
    }

    /**
     * 거래소마다 <b>이름 한 줄, 그 아래 값 줄</b> — <b>업비트 먼저, 바이낸스 다음.</b>
     *
     * <p>{@code single}(단건 {@code /crypto})이면 값이 없는 쪽도 이유를 적는다. 사용자가 방금 그
     * 코인을 물었을 때는 줄을 빼 버리면 그 거래소를 조회하지 않은 것처럼 보이고, 무엇보다 다시
     * 시도해야 할지 알 수 없다. 그래서 이유를 갈라 쓴다 — {@code 미상장}은 영영 안 나오는 것이고
     * {@code 조회 실패}는 잠시 뒤 다시 치면 되는 것이다. 브리핑에서는 그 줄을 아예 뺀다.
     *
     * <p><b>한 거래소 안에서 값·원화 환산·등락률을 각각 제 줄에 둔다.</b> 한 줄에 {@code ·}로 붙이면
     * USDT와 KRW 두 값이 엉킨다. 순서는 값 → 원화 환산 → 등락률이다.
     *
     * <p>{@code usdtKrw}가 없으면 USDT만 적는다 — 환산을 못 한다고 시세를 빼는 것은 과하다.
     */
    private static String exchangeLines(CryptoQuote quote, BigDecimal usdtKrw, boolean single) {
        StringBuilder lines = new StringBuilder();
        // 업비트는 원화가 기본이라 환산 줄이 없다 — 이름 한 줄, 값 한 줄, 등락률 한 줄
        if (quote.upbit().hasPrice()) {
            lines.append("업비트\n").append(money(quote.upbit().price())).append(" KRW");
            appendChangeLine(lines, quote.upbit().changePercent());
        } else if (single) {
            lines.append("업비트 ").append(reasonOf(quote.upbit()));
        }

        if (!quote.binance().hasPrice()) {
            if (single) {
                lines.append(exchangeGap(lines, single)).append("바이낸스 ").append(reasonOf(quote.binance()));
            }
            return lines.toString();
        }
        // 바이낸스: 이름 한 줄, 그 아래 값(USDT) → 원화 환산 → 등락률, 각각 제 줄에
        lines.append(exchangeGap(lines, single))
                .append("바이낸스\n").append(money(quote.binance().price())).append(" USDT");
        if (usdtKrw != null) {
            BigDecimal krw = quote.binance().price().multiply(usdtKrw).setScale(0, RoundingMode.HALF_UP);
            lines.append("\n").append(money(krw)).append(" KRW");
        }
        appendChangeLine(lines, quote.binance().changePercent());
        return lines.toString();
    }

    /** 등락률을 제 줄에 붙인다 — 값과 원화 환산 다음이다. 없으면 붙이지 않는다. */
    private static void appendChangeLine(StringBuilder lines, BigDecimal percent) {
        String change = change(percent);
        if (!change.isEmpty()) {
            lines.append("\n").append(change);
        }
    }

    /**
     * 거래소 블록 사이 간격 — 앞 블록이 있을 때만 띄운다(브리핑에서 업비트 줄을 뺐을 때 빈 줄이
     * 앞서지 않게 한다). 단건은 빈 줄로 띄워 두 거래소를 또렷이 가르고, 브리핑은 한 줄로 촘촘히
     * 둔다 — 브리핑에서 빈 줄은 코인 사이 경계라 거래소 사이에도 쓰면 계층이 흐려진다.
     */
    private static String exchangeGap(StringBuilder lines, boolean single) {
        if (lines.isEmpty()) {
            return "";
        }
        return single ? "\n\n" : "\n";
    }

    /** 값이 없는 이유. 사용자가 다시 시도해야 하는지가 여기서 갈린다. */
    private static String reasonOf(CryptoQuote.Quote quote) {
        return switch (quote.state()) {
            case NOT_LISTED -> "미상장";
            case FAILED -> "조회 실패";
            case OK -> "";
        };
    }

    public static String cryptoNotFound(String query) {
        return section(Command.CRYPTO)
                + "'" + Html.escape(query) + "'에 해당하는 코인을 찾지 못했습니다.\n\n"
                + "업비트 또는 바이낸스에 상장된 이름이나 심볼로 입력해 주세요.\n\n"
                + "예) /crypto 비트코인 · /crypto BTC";
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
     * 제목 줄만. 아래에 무리를 바로 붙이는 통(증시·코인·뉴스 브리핑)이 쓴다.
     *
     * <p>제목 문자열이 {@link Command}에만 있으므로 검색 답과 브리핑 답의 제목이 갈릴 수 없다.
     */
    private static String title(Command command) {
        return "<b>" + Html.escape(command.section()) + "</b>";
    }

    /**
     * 등락률 한 조각 — <b>상승 🔴 / 하락 🔵 / 보합 무표시</b>.
     *
     * <p>부호({@code +}·{@code -})는 붙이지 않는다. 원이 이미 방향이라 겹친다.
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
        return (direction > 0 ? "🔴 " : "🔵 ") + rounded.abs().toPlainString() + "%";
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

    private static String describe(Command command) {
        return switch (command) {
            case NEWS -> "검색어에 해당하는 뉴스";
            case FX -> "원/달러 환율";
            case STOCK -> "국내·미국 주식과 지수의 현재가";
            case CRYPTO -> "코인 현재가";
            case HELP -> "이 도움말";
        };
    }
}
