package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.DATE_TIME;
import static io.saiden.economyhelper.telegram.MessageLayout.SEOUL;
import static io.saiden.economyhelper.telegram.MessageLayout.SHORT_DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.appendChangeLine;
import static io.saiden.economyhelper.telegram.MessageLayout.empty;
import static io.saiden.economyhelper.telegram.MessageLayout.head;
import static io.saiden.economyhelper.telegram.MessageLayout.krw;
import static io.saiden.economyhelper.telegram.MessageLayout.money;
import static io.saiden.economyhelper.telegram.MessageLayout.sources;
import static io.saiden.economyhelper.telegram.MessageLayout.title;

import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.StockOutlook;
import java.util.Map;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 증시 통 — <b>브리핑도 {@code /stock} 한 건도 이것 하나를 쓴다.</b>
 *
 * <p><b>무리를 지역으로 가른다.</b> 예전에는 {@code realtime}으로 갈랐는데 그건 국내가 전일
 * 종가뿐이던 시절에만 맞는 가정이었다 — 국내에 실시간 출처가 붙으면 삼성전자가 「미국」에 찍힌다.
 *
 * <p><b>조회처와 기준은 무리마다 그 무리 끝에 단다.</b> 둘을 통 맨 아래에 모으면 어느 무리
 * 것인지 밝히려고 {@code 국내}·{@code 미국}을 접두사로 네 번 반복해야 한다.
 */
public final class StockFormatter {

    /**
     * 이름표를 단 블록의 머리 — <b>앞과는 빈 줄로 벌리고 값은 바로 아랫줄에 붙인다.</b>
     *
     * <pre>
     * 🔵 -0.33%      ← 앞 무리
     *                ← 빈 줄이 블록을 가른다
     * 목표           ← 이름표
     * 212.40 USD     ← 값은 바로 아랫줄
     * 296,585 KRW
     * </pre>
     *
     * <p>이 통의 규칙이 「빈 줄은 블록 사이, 한 줄은 블록 안」이다. 전망은 <b>이름표와 값이
     * 한 블록</b>이므로 그 안은 한 줄이고, 블록 앞에만 빈 줄이 온다 — 시세 블록이
     * 「이름 / 값 / 환산 / 등락률」로 붙어 있는 것과 같은 모양이다.
     */
    private static String labelled(String name) {
        return "\n\n" + name + "\n";
    }

    private StockFormatter() {
    }

    public static String notFound(String query) {
        return head(Command.STOCK)
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
     * <p><b>조회처와 기준은 무리마다 그 무리 끝에 단다</b>(한국투자증권·금융위원회·
     * Financial Modeling Prep — 1순위가 KIS이고 무리마다 폴백이 갈릴 수 있다).
     * 다른 통과 같은 순서 — 값 다음에 출처, 한 줄 띄고 시각이다. 둘을 통 맨 아래에 모으면
     * 어느 무리 것인지 밝히려고 {@code 국내}·{@code 미국}을 접두사로 네 번 반복해야 한다.
     *
     * <p>종목코드·거래소는 여전히 적지 않는다 — 이름이 이미 그 종목을 가리킨다. 환산에 쓴
     * 환율도 적지 않는다 — 환율은 {@code /fx}와 브리핑 환율 통이 따로 있다.
     */
    public static String format(List<StockQuote> quotes, FxRate fx) {
        return format(quotes, fx, Map.of());
    }

    /**
     * 전망까지 붙인 것 — 목표주가·투자의견이 있는 종목에만 줄이 는다.
     *
     * <p><b>왜 시세 목록과 따로 받나.</b> {@link StockQuote}에는 종목코드가 없고 넣을 수도
     * 없다 — 시세는 1분 캐시이고 전망은 12시간이라 한 항목으로 묶으면 하루에 한 번 바뀌는
     * 값을 1분마다 다시 받는다. 그래서 {@code StockService}가 둘을 따로 들고 와 여기서 만난다.
     *
     * <p>맵의 열쇠가 시세 자체인 것은 레코드가 값 동등성을 갖기 때문이다 — 이름으로 잇지
     * 않는다(같은 이름의 지수와 종목이 있을 수 있다).
     *
     * @param outlooks 시세 → 전망. 없는 종목은 아예 담기지 않는다
     */
    public static String format(List<StockQuote> quotes, FxRate fx,
                                Map<StockQuote, StockOutlook> outlooks) {
        if (quotes.isEmpty()) {
            return empty(Command.STOCK);
        }
        // ⚠️ 지역으로 가른다. 예전에는 realtime으로 갈랐는데, 국내가 전일 종가뿐이던 시절에만
        // 맞는 가정이었다 — 국내에 실시간 출처가 붙으면 삼성전자가 「미국」에 찍힌다
        StringBuilder message = new StringBuilder(title(Command.STOCK));
        for (StockQuote.Market market : StockQuote.Market.values()) {
            appendGroup(message, market, quotes.stream()
                    .filter(quote -> quote.market() == market).toList(), fx, outlooks);
        }

        // 환율 줄을 붙이지 않는다. 브리핑은 환율 통을 이 통 바로 앞에 보내므로 중복이다
        return message.toString();
    }

    /**
     * 무리의 조회처 — 꼬리를 <b>무리마다</b> 단다.
     *
     * <p><b>무리와 조회처는 1:1이 아니다.</b> 한국투자증권이 국내와 미국을 둘 다 맡으면서
     * 깨진 전제다 — 한 무리에 실시간(KIS)과 전일 종가(폴백)가 섞일 수 있고, 그때 출처가
     * 둘이 된다({@link #appendGroup}의 주석이 같은 사실을 적는다). 그래서 여럿을 받아
     * 세로로 쌓는다 — 모양은 {@link #sourcesOf}가 정한다.
     */
    private static String sourcesOf(List<StockQuote> quotes) {
        return sources(quotes.stream().map(StockQuote::source)
                .distinct().sorted().map(StockSource::displayName));
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
                                    List<StockQuote> quotes, FxRate fx,
                                    Map<StockQuote, StockOutlook> outlooks) {
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
            appendOutlook(message, quote, outlooks.get(quote), fx);
        }
        // 무리 하나가 통 하나처럼 끝맺는다 — 값 다음에 출처, 한 줄 띄고 기준.
        // 두 무리 것을 맨 아래에 모으면 "국내"·"미국"을 접두사로 네 번 반복해야 한다
        message.append("\n\n").append(sourcesOf(quotes))
                .append("\n\n").append(basisLines(live, closing));
    }

    /**
     * 전망 줄 — <b>있는 것만 적는다.</b>
     *
     * <p>둘이 따로 논다. 목표주가는 두 시장 다 있지만 실적발표일은 <b>미국에만</b> 있다
     * (국내에 무료 출처가 없다). <b>없는 것을 {@code 0}이나 「-」로 찍지 않는다</b> —
     * 「목표가 0원」은 모른다는 뜻이 아니라 <b>값</b>이다.
     *
     * <p><b>이름표가 블록의 머리다</b>({@link #labelled}). 시세 줄들은 이름표가 없어
     * (「311.30 USD」·「🔵 -1.75%」) 줄만 바꿔도 무엇인지 읽히지만, 전망은 <b>이름표를 달아야
     * 뜻이 서는 값</b>이다. 그 이름표를 앞줄에 바싹 붙이면 위 숫자 무리에 딸려 붙어 읽히므로
     * <b>앞에 빈 줄을 둔다</b>. 값은 이름표 바로 아랫줄이다 — 한 블록 안이기 때문이다.
     *
     * <p>대가는 줄 수다 — 종목마다 최대 여섯 줄이 는다. 텔레그램 한 통 상한(4,096자)에는
     * 한참 못 미치므로(<b>실측 415자</b> — 지수 넷·종목 셋에 전망 셋을 다 붙인 브리핑 증시 통)
     * 문제가 되지 않는다.
     *
     * <p>⚠️ <b>투자의견 줄은 없다.</b> 목표가 아래에 「매수 (111곳)」이 있었는데 요구가
     * 걷어내는 쪽으로 바뀌었다. 화면만 지우지 않고 {@code StockOutlook}의 필드와 FMP
     * {@code grades-consensus} 호출까지 함께 지웠다 — 화면에서만 빼면 심볼당 하루 한 번을
     * 아무도 안 보는 값에 쓴다.
     *
     * @param fx 목표가의 원화 환산에 쓴다. <b>값 줄이 쓰는 그 환율이어야 한다</b> —
     *           둘이 다른 고시를 쓰면 같은 통에서 「311.30 USD = 434,684 KRW」와
     *           「340.72 USD = 다른 환율의 원화」가 함께 찍혀 어느 쪽도 못 믿게 된다.
     *           {@code null}이면 환산 줄만 빠지고 달러 목표가는 그대로 나간다
     */
    private static void appendOutlook(StringBuilder message, StockQuote quote,
                                      StockOutlook outlook, FxRate fx) {
        if (outlook == null) {
            return;
        }
        if (outlook.targetPrice() != null) {
            // ⚠️ 값 줄과 같은 모양으로 단위를 붙인다. 바로 위가 「239,500 KRW」인데 여기가
            //    「목표 466,667」이면 무슨 단위인지 읽는 사람이 짐작해야 한다 — 목표주가의
            //    통화는 그 종목의 통화라고 StockOutlook이 적어 뒀으므로 그것을 그대로 쓴다
            message.append(labelled("목표")).append(unitOf(quote, outlook.targetPrice()));
            // 값 줄과 같은 규칙으로 환산한다 — 달러 목표가만 붙고, 국내는 이미 원화라 없다.
            // 이 줄이 없던 동안 읽는 사람이 340.72에 환율을 손으로 곱해야 했다
            if (convertible(quote, fx)) {
                message.append("\n").append(money(krw(outlook.targetPrice(), fx))).append(" KRW");
            }
        }
        if (outlook.earningsDate() != null) {
            message.append(labelled("실적발표"))
                    .append(SHORT_DATE.format(outlook.earningsDate()));
        }
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
     * <p>출처가 여럿이면 한 줄에 하나씩 쌓는 것과 같은 규칙이다({@link #sourcesOf}).
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

    /** 통화 코드까지 붙인 값. 지수는 통화가 없어 숫자만 나간다. */
    private static String priceOf(StockQuote quote) {
        return unitOf(quote, quote.price());
    }

    /**
     * 그 종목의 통화를 붙인 숫자 — 시세와 목표주가가 <b>같은 모양</b>이어야 한다.
     *
     * <p>둘이 갈리면 한 블록 안에서 「239,500 KRW」 아래에 「466,667」이 서고, 읽는 사람이
     * 단위를 짐작하게 된다.
     */
    private static String unitOf(StockQuote quote, java.math.BigDecimal amount) {
        return switch (quote.currency()) {
            case NONE -> money(amount);
            case KRW -> money(amount) + " KRW";
            case USD -> money(amount) + " USD";
        };
    }

    /** 환율이 없으면 달러만 보낸다 — 환산을 못 한다고 시세를 빼는 것은 과하다. */
    private static boolean convertible(StockQuote quote, FxRate fx) {
        return quote.currency().convertible() && fx != null;
    }
}
