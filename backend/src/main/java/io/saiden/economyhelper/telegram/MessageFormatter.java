package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService.StockMatch;
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
 * <p><b>{@code parse_mode=HTML}로 보낸다.</b> 예전에는 평문이었다 — 기사 제목의
 * {@code *}·{@code _}·{@code [}가 Markdown 파싱 오류를 내 발송이 실패했기 때문이다.
 * HTML에서는 그 문자들이 무해하고 {@code & < >} 세 자만 처리하면 된다({@link Html} 참조).
 * 덕분에 시세를 <b>모노스페이스 표</b>로 세워 자릿수를 맞출 수 있다.
 *
 * <p><b>바깥에서 온 문자열은 예외 없이 {@link Html#escape}를 통과한다.</b>
 * 하나라도 빠뜨리면 그 메시지는 발송 자체가 실패한다 — 평문일 때는 없던 위험이다.
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

    /** 표의 열 폭(표시 폭 기준). 이름은 왼쪽, 숫자는 오른쪽으로 맞춘다. */
    private static final int NAME_COLUMNS = 11;
    private static final int VALUE_COLUMNS = 15;
    private static final int KRW_COLUMNS = 15;
    /** 거래소 이름 칸. {@code 바이낸스}가 8칸이라 한 칸 띄우면 값이 세로로 떨어진다. */
    private static final int EXCHANGE_COLUMNS = 9;

    private MessageFormatter() {
    }

    // --- 뉴스 ---------------------------------------------------------------

    /**
     * 기사 한 건. <b>제목이 곧 링크</b>가 되어 토막난 URL 줄이 사라진다.
     *
     * <p>발행 일시를 매체명 옆에 붙인다 — 없으면 어제 기사인지 방금 것인지 알 수 없고,
     * 아침 브리핑처럼 여러 건이 묶일 때 특히 그렇다.
     *
     * <p>{@code publishedAt}에 {@code null} 방어를 두지 않는다. {@code Article}이 생성 시점에
     * 강제하고({@code "publishedAt is required"}) {@code RssFeedClient}는 날짜 없는 항목을
     * 아예 버리므로, 여기까지 온 값에는 날짜가 반드시 있다.
     */
    public static String format(NewsItem item) {
        StringBuilder message = new StringBuilder();
        message.append("<b>").append(Html.escape(item.sourceName())).append("</b>")
                .append(" · ").append(DATE_TIME.format(item.publishedAt().atZone(SEOUL))).append("\n")
                .append("<a href=\"").append(Html.escape(item.link())).append("\">")
                .append(Html.escape(item.title())).append("</a>");

        if (!item.body().isBlank()) {
            // 인용 블록으로 감싸면 제목과 본문이 갈려 다섯 건이 훨씬 잘 읽힌다
            message.append("\n<blockquote>").append(Html.escape(item.body())).append("</blockquote>");
        }
        if (!item.translated()) {
            // 왜 영문인지 밝히지 않으면 고장으로 보인다
            message.append("\n<i>번역이 일시적으로 불가해 원문 그대로 보냅니다.</i>");
        }
        return message.toString();
    }

    /** 아침 브리핑의 뉴스 통 — 매체별 1건을 한 메시지에 묶는다. */
    public static String formatDigest(List<NewsItem> items) {
        if (items.isEmpty()) {
            return "📰 <b>뉴스</b>\n\n지금은 가져올 수 있는 뉴스가 없습니다.";
        }
        StringBuilder message = new StringBuilder("📰 <b>뉴스</b>");
        for (NewsItem item : items) {
            message.append("\n\n").append(format(item));
        }
        return message.toString();
    }

    public static String noResults(String query) {
        return "'" + Html.escape(query) + "'에 해당하는 뉴스를 찾지 못했습니다.";
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
        return "💱 <b>환율</b>\n\n"
                + "<b>1 USD = " + money(rate.rate()) + " KRW</b>\n\n"
                + Html.escape(rate.source().displayName()) + " · " + basisOf(rate);
    }

    public static String fxUnavailable() {
        return "환율을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    /** 환산 근거 한 줄. <b>출처와 날짜까지</b> 붙여야 며칠 전 고시가로 환산된 것이 드러난다. */
    private static String fxLine(FxRate rate) {
        return "💱 1 USD = " + money(rate.rate()) + " KRW · "
                + Html.escape(rate.source().displayName()) + " · " + basisOf(rate);
    }

    private static String basisOf(FxRate rate) {
        return rate.source().intraday()
                ? DATE_TIME.format(rate.asOf().atZone(SEOUL))
                : DATE.format(rate.asOf().atZone(SEOUL)) + " (고시)";
    }

    // --- 주식·지수 -----------------------------------------------------------

    /**
     * 종목·지수 하나.
     *
     * <p><b>기준을 반드시 밝힌다.</b> 국내는 전일 종가라 날짜를 숨기면 현재가로 오해하고,
     * 미국은 현재가라 시각까지 드러내야 한다.
     *
     * <p>함께 걸린 다른 후보를 덧붙인다 — 텔레그램에서 되묻으면 대화가 두 번 오간다.
     */
    public static String formatStock(StockMatch match, FxRate fx) {
        StockQuote quote = match.quote();
        StringBuilder message = new StringBuilder(quote.index() ? "📊 <b>" : "📈 <b>")
                .append(Html.escape(quote.name())).append("</b>\n\n")
                .append("<code>").append(priceOf(quote)).append("</code>");

        if (convertible(quote, fx)) {
            message.append("\n<code>약 ").append(money(krw(quote.price(), fx))).append(" KRW</code>");
        }

        message.append("\n");
        String identity = identityOf(quote);
        if (!identity.isEmpty()) {
            message.append("\n").append(identity);
        }
        message.append("\n").append(sourceOf(quote));
        if (convertible(quote, fx)) {
            message.append("\n").append(fxLine(fx));
        }
        if (!match.alternatives().isEmpty()) {
            message.append("\n함께 검색된 종목: ")
                    .append(Html.escape(String.join(", ", match.alternatives())));
        }
        return message.toString();
    }

    /** {@code AAPL · NASDAQ} 또는 {@code 005930 · KOSPI}. 국내 지수는 코드가 없다. */
    private static String identityOf(StockQuote quote) {
        StringBuilder id = new StringBuilder();
        if (quote.code() != null && !quote.code().isBlank()) {
            id.append(Html.escape(quote.code()));
        }
        if (quote.market() != null && !quote.market().isBlank()) {
            id.append(id.isEmpty() ? "" : " · ").append(Html.escape(quote.market()));
        }
        return id.toString();
    }

    private static String sourceOf(StockQuote quote) {
        return quote.realtime()
                ? "FMP · " + DATE_TIME.format(quote.at().atZone(SEOUL))
                : "공공데이터포털 · " + DATE.format(quote.at().atZone(SEOUL)) + " (종가)";
    }

    public static String stockNotFound(String query) {
        return "'" + Html.escape(query) + "'에 해당하는 종목을 찾지 못했습니다.\n\n"
                + "국내(코스피·코스닥)와 미국(나스닥·뉴욕) 종목·지수를 조회할 수 있습니다.\n"
                + "예) <code>/stock 삼성전자</code> · <code>/stock 애플</code> · "
                + "<code>/stock 코스피</code> · <code>/stock 나스닥</code>";
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

        StringBuilder message = new StringBuilder("📈 <b>증시</b>");
        appendGroup(message, "국내", closing, fx);
        appendGroup(message, "미국", live, fx);

        if (live.stream().anyMatch(quote -> convertible(quote, fx))) {
            message.append("\n\n").append(fxLine(fx));
        }
        return message.toString();
    }

    /**
     * 무리 하나를 표로 붙인다. 비어 있으면 제목도 남기지 않는다.
     *
     * <p>기준을 <b>무리 제목에 한 번만</b> 쓴다 — 줄마다 붙이면 같은 값이 반복된다.
     * 무리 안에서 기준이 어긋난 줄에만 따로 표시한다.
     *
     * <p>원화 환산은 <b>세 번째 열</b>이다. 값 뒤에 괄호로 붙이면 모처럼 맞춘 정렬이 그 줄에서 깨진다.
     */
    private static void appendGroup(StringBuilder message, String title,
                                    List<StockQuote> quotes, FxRate fx) {
        if (quotes.isEmpty()) {
            return;
        }
        Instant basis = quotes.stream().map(StockQuote::at).max(Comparator.naturalOrder()).orElseThrow();
        boolean realtime = quotes.get(0).realtime();

        message.append("\n\n<b>").append(title).append("</b>  ")
                .append(realtime ? DATE_TIME.format(basis.atZone(SEOUL))
                        : DATE.format(basis.atZone(SEOUL)) + " (종가)")
                .append("\n<pre>");

        boolean first = true;
        for (StockQuote quote : quotes) {
            if (!first) {
                message.append("\n");
            }
            first = false;
            message.append(Html.pad(Html.escape(quote.name()), NAME_COLUMNS))
                    .append(Html.padLeft(priceOf(quote), VALUE_COLUMNS));
            if (convertible(quote, fx)) {
                message.append(Html.padLeft(money(krw(quote.price(), fx)) + " KRW", KRW_COLUMNS));
            }
            if (!quote.at().equals(basis)) {
                message.append("  ").append(DATE.format(quote.at().atZone(SEOUL)));
            }
        }
        message.append("</pre>");
    }

    // --- 코인 ---------------------------------------------------------------

    /**
     * 코인 현재가.
     *
     * <p>등락률을 넣지 않는다 — {@code CLAUDE.md}가 요구하는 것은 "현재 가격"이고,
     * 명령마다 정보 밀도가 달라지는 것도 피한다.
     */
    public static String formatCrypto(CryptoQuote quote, BigDecimal usdtKrw) {
        return "🪙 <b>" + Html.escape(quote.koreanName()) + "</b>\n"
                + "<pre>" + exchangeLines(quote, usdtKrw) + "</pre>\n"
                + Html.escape(quote.market()) + "\n"
                + DATE_TIME.format(quote.at().atZone(SEOUL));
    }

    /** 아침 브리핑의 코인 통. 24시간 거래되므로 기준일이 아니라 시각을 쓴다. */
    public static String formatCryptoDigest(List<CryptoQuote> quotes, BigDecimal usdtKrw) {
        StringBuilder message = new StringBuilder("🪙 <b>코인</b>");
        quotes.stream().findFirst().ifPresent(first ->
                message.append("  ").append(DATE_TIME.format(first.at().atZone(SEOUL))));
        message.append("\n<pre>");

        boolean first = true;
        for (CryptoQuote quote : quotes) {
            if (!first) {
                message.append("\n");
            }
            first = false;
            message.append(Html.escape(quote.koreanName())).append("\n")
                    .append(exchangeLines(quote, usdtKrw));
        }
        return message.append("</pre>").toString();
    }

    /**
     * 거래소별 한 줄씩 — <b>업비트 먼저, 바이낸스 다음.</b>
     *
     * <p>이름을 위에 두고 거래소를 들여쓰는 이유는 폭이다. 한 줄에 이름·거래소·값·환산을 다 넣으면
     * 50칸을 넘어 휴대폰에서 가로로 밀린다. 이렇게 두면 주식 통과 같은 42칸 안에 들어온다.
     *
     * <p>바이낸스 값이 없으면 그 줄을 <b>아예 뺀다</b> — {@code 0}이나 {@code -}로 채우면
     * "시세가 0"과 "모른다"가 구분되지 않는다. {@code usdtKrw}가 없으면 USDT만 적는다.
     */
    private static String exchangeLines(CryptoQuote quote, BigDecimal usdtKrw) {
        StringBuilder lines = new StringBuilder(" ")
                .append(Html.pad("업비트", EXCHANGE_COLUMNS))
                .append(Html.padLeft(money(quote.price()) + " KRW", VALUE_COLUMNS));
        if (quote.binanceUsdt() == null) {
            return lines.toString();
        }
        lines.append("\n ").append(Html.pad("바이낸스", EXCHANGE_COLUMNS))
                .append(Html.padLeft(money(quote.binanceUsdt()) + " USDT", VALUE_COLUMNS));
        if (usdtKrw != null) {
            BigDecimal krw = quote.binanceUsdt().multiply(usdtKrw).setScale(0, RoundingMode.HALF_UP);
            lines.append(Html.padLeft(money(krw) + " KRW", KRW_COLUMNS));
        }
        return lines.toString();
    }

    public static String cryptoNotFound(String query) {
        return "'" + Html.escape(query) + "'에 해당하는 코인을 찾지 못했습니다.\n\n"
                + "업비트 원화 마켓에 있는 이름이나 심볼로 입력해 주세요.\n"
                + "예) <code>/crypto 비트코인</code> · <code>/crypto BTC</code>";
    }

    // --- 공통 ---------------------------------------------------------------

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

    static String timestamp(Instant at) {
        return DATE_TIME.format(at.atZone(SEOUL));
    }

    /** 인자가 필요한 명령을 인자 없이 보냈을 때. 명령마다 예시가 다르다. */
    public static String usage(Command command) {
        return "검색어를 함께 입력해 주세요.\n예) <code>"
                + Html.escape(command.example()) + "</code>";
    }

    /**
     * {@code /}로 시작하는 모르는 명령에만 띄운다.
     *
     * <p>일반 대화에는 반응하지 않는다 — 그룹 채팅이 오염된다.
     */
    public static String unknownCommand() {
        return "모르는 명령입니다.\n\n" + help();
    }

    public static String help() {
        StringBuilder message = new StringBuilder("<b>사용할 수 있는 명령</b>");
        for (Command command : Command.values()) {
            message.append("\n\n<code>").append(Html.escape(command.example())).append("</code>\n")
                    .append(describe(command));
        }
        return message.toString();
    }

    private static String describe(Command command) {
        return switch (command) {
            case NEWS -> "검색어에 해당하는 뉴스 1건";
            case FX -> "원/달러 환율";
            case STOCK -> "국내·미국 주식과 지수의 현재가";
            case CRYPTO -> "코인 현재가";
            case HELP -> "이 도움말";
        };
    }
}
