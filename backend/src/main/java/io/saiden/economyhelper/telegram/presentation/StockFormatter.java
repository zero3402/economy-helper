package io.saiden.economyhelper.telegram.presentation;

import static io.saiden.economyhelper.telegram.presentation.MessageLayout.DATE;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.SEOUL;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.appendChangeLine;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.bold;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.date;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.dateTime;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.empty;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.inKrw;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.krwAmount;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.money;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.sources;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.title;

import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.stock.domain.StockOutlook;
import io.saiden.economyhelper.stock.domain.StockQuote;
import io.saiden.economyhelper.stock.domain.StockSource;
import io.saiden.economyhelper.telegram.adapter.in.web.Command;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 증시 통 — <b>브리핑도 {@code /stock} 한 건도 이것 하나를 쓴다.</b>
 *
 * <p><b>무리를 지역으로 가른다</b>({@code realtime}이 아니다 — 국내에도 실시간 출처가 있다).
 *
 * <p><b>조회처와 기준은 무리마다 그 무리 끝에 단다.</b> 둘을 통 맨 아래에 모으면 어느 무리
 * 것인지 밝히려고 {@code 국내}·{@code 미국}을 접두사로 네 번 반복해야 한다.
 *
 * <p>화면 규칙의 경위와 실측은 ADR-0006.
 */
public final class StockFormatter {

    /**
     * 이름표를 단 블록의 머리 — <b>앞과는 빈 줄로 벌리고 값은 바로 아랫줄에 붙인다.</b>
     *
     * <pre>
     * 🔵 -0.33%      ← 앞 무리
     *                ← 빈 줄이 블록을 가른다
     * 목표가         ← 이름표
     * 212.40 USD     ← 값은 바로 아랫줄
     * 296,585 KRW
     * </pre>
     *
     * <p>이 통의 규칙이 「빈 줄은 블록 사이, 한 줄은 블록 안」이다. 전망은 <b>이름표와 값이
     * 한 블록</b>이므로 그 안은 한 줄이고, 블록 앞에만 빈 줄이 온다 — 시세 블록이
     * 「이름 / 값 / 환산 / 등락률」로 붙어 있는 것과 같은 모양이다.
     *
     * <p><b>값의 성격도 이름표가 든다</b>({@code 실적발표일(미국)}, {@link #calendarTag}) —
     * 값 줄에는 꼬리표를 붙이지 않으므로({@link #basisLines}) 블록의 머리가 그 자리다.
     */
    private static String labelled(String name) {
        return "\n\n" + name + "\n";
    }

    private StockFormatter() {
    }

    public static String notFound(String query) {
        return MessageLayout.notFound(Command.STOCK, query, "종목");
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
     * <p>무리 끝은 다른 통과 같은 순서다 — 값 다음에 출처, 한 줄 띄고 시각.
     *
     * <p>종목코드·거래소는 여전히 적지 않는다 — 이름이 이미 그 종목을 가리킨다. 환산에 쓴
     * 환율도 적지 않는다 — 환율은 {@code /fx}와 브리핑 환율 통이 따로 있다.
     */
    public static String format(List<StockQuote> quotes, FxRate fx) {
        return format(quotes, fx, Map.of());
    }

    /**
     * 전망까지 붙인 것 — 목표주가·실적발표일·배당이 있는 종목에만 줄이 붙는다.
     *
     * <p>시세와 전망은 수명이 달라 따로 온다(이유는 {@code StockService.Answer}) — 여기서 만난다.
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
     * <p><b>무리와 조회처는 1:1이 아니다</b> — 한 무리에 실시간(KIS)과 전일 종가(폴백)가 섞이면
     * 출처가 둘이 되어 세로로 쌓는다.
     */
    private static String sourcesOf(List<StockQuote> quotes) {
        return sources(quotes.stream().map(StockQuote::source)
                .distinct().sorted().map(StockSource::displayName));
    }

    /**
     * 무리 하나. 비어 있으면 제목도 남기지 않는다.
     *
     * <p>굵게는 <b>제목과 이름에</b> 쓴다 — 값과 이름표({@code 목표가}·{@code 실적발표})는 맨
     * 글자다. 값까지 굵으면 무엇이 계층인지 드러나지 않는다.
     *
     * <p><b>종목 하나가 블록 하나다</b> — 이름을 제 줄에 올리고 블록끼리는 빈 줄로 가른다.
     * 코인 통이 거래소마다 그렇게 하는 것과 같은 규칙이다 — 안 그러면 어디까지가 한 종목인지
     * 읽는 사람이 셀 수 없다.
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
            // 블록 사이는 빈 줄. 이름을 굵게 쓴다 — 코인·날씨 통과 같은 자리의 표기다
            message.append("\n\n").append(bold(quote.name()))
                    .append("\n").append(priceOf(quote));
            // 무리 기준과 어긋난 줄에만 표시한다. 맨 밑 기준 줄이 그 값까지 대표하는 것처럼
            // 보이면 거짓말이 된다. 값의 날짜라 값 줄에 함께 둔다.
            //
            // ⚠️ 시각이 아니라 <b>날짜</b>로 비교한다. at이 심볼마다 제 체결 초라 Instant로
            // 견주면 미국 무리가 가장 최근 것 하나만 빼고 전부 날짜가 붙는다. 날짜로 견주면
            // 남는 것은 진짜 다른 날뿐이다(공공데이터포털 폴백에서 지수와 종목이 하루 어긋날 때).
            //
            // ⚠️ <b>제 성격의 기준</b>과 견준다. 무리 기준(가장 최근)과 견주면, 실시간과 종가가
            // 섞인 무리에서 종가 줄마다 날짜가 붙어 값 줄이 지저분해진다 — 그 줄은 낡은 것이
            // 아니라 성격이 다른 것이고, 성격은 아래 꼬리가 밝힌다.
            if (!sameDay(quote.at(), quote.realtime() ? live : closing)) {
                message.append(" · ").append(date(quote.at()));
            }
            // 값 → 원화 환산 → 등락률 순으로 각각 제 줄에. 환산값과 등락률을 한 줄에 붙이면 엉킨다
            if (convertible(quote, fx)) {
                message.append("\n").append(inKrw(quote.price().value(), fx));
            }
            appendChangeLine(message, quote.changePercent());
            appendOutlook(message, quote, outlooks.get(quote), fx);
        }
        // 무리 하나가 통 하나처럼 끝맺는다 — 값 다음에 출처, 한 줄 띄고 기준
        message.append("\n\n").append(sourcesOf(quotes))
                .append("\n\n").append(basisLines(live, closing));
    }

    /**
     * 전망 줄 — <b>있는 것만 적는다.</b>
     *
     * <p>셋이 따로 논다. 목표주가와 배당은 두 시장 다 있지만 실적발표일은 <b>미국에만</b> 있다
     * (국내에 무료 출처가 없다). <b>없는 것을 {@code 0}이나 「-」로 찍지 않는다</b> —
     * 「목표가 0원」은 모른다는 뜻이 아니라 <b>값</b>이다.
     *
     * <p><b>배당은 기준일 → 지급일 → 배당금 순</b>이다. 락일이 아니라 <b>기준일</b>인 이유는
     * {@code StockOutlook.Dividend}에 있다(두 출처가 기준일을 직접 주고, 락일은 휴장일 달력이 있어야
     * 맞다). 배당금은 목표가와 같은 규칙으로 단위를 붙이고 <b>같은 환율</b>로 환산한다.
     *
     * <p><b>이름표가 블록의 머리다</b>({@link #labelled}). 대가는 종목마다 최대 <b>열일곱 줄</b>인데
     * 한 통 상한(4,096자)에 한참 못 미친다 — 지수 넷·종목 셋에 전망을 다 붙인 브리핑 증시 통이
     * <b>실측 673자·89줄</b>이다.
     *
     * <p>⚠️ <b>실적발표일·배당 날짜는 그 시장 달력의 날짜다</b> — 미국이면 이름표가 {@code (미국)}을
     * 든다({@link #calendarTag}). KST로 환산하지 않는다: 발표가 미국 장 마감 뒤라 대개 다음 날
     * 새벽이 된다. 날짜 모양은 통의 다른 날짜와 같은 {@code DATE}다.
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
            appendAmount(message.append(labelled("목표가")), quote, outlook.targetPrice().value(), fx);
        }
        appendDate(message, "실적발표일" + calendarTag(quote), outlook.earningsDate());
        StockOutlook.Dividend dividend = outlook.dividend();
        if (dividend == null) {
            return;
        }
        // 필드마다 따로 본다 — 기준일은 지났고 지급일만 남은 분기, 기준일만 잡히고 배당금이 미정인 분기가 흔하다.
        // ⚠️ 지난 배당을 든 경우에는 이름표가 그렇게 말한다 — 「지난 것을 다음이라 부르지 않는다」
        String when = dividend.past() ? "지난 " : "";
        appendDate(message, when + "배당기준일" + calendarTag(quote), dividend.recordDate());
        appendDate(message, when + "배당지급일" + calendarTag(quote), dividend.payDate());
        if (dividend.amount() != null) {
            appendAmount(message.append(labelled(when + "배당금")), quote, dividend.amount(), fx);
        }
    }

    /** 이름표를 단 날짜 블록 하나. 날짜가 없으면 이름표도 안 적는다. */
    private static void appendDate(StringBuilder message, String label, LocalDate date) {
        if (date != null) {
            message.append(labelled(label)).append(DATE.format(date));
        }
    }

    /**
     * 그 종목의 단위를 붙인 금액과, 달러면 그 아랫줄에 원화 환산 — <b>값·목표가·배당금이 같은 모양이다.</b>
     *
     * <p>국내는 이미 원화라 환산 줄이 없고, 환율이 없으면 환산 줄만 빠진다({@link #convertible}).
     */
    private static void appendAmount(StringBuilder message, StockQuote quote, BigDecimal amount, FxRate fx) {
        message.append(withUnit(quote, amount));
        if (convertible(quote, fx)) {
            message.append("\n").append(inKrw(amount, fx));
        }
    }

    /**
     * 일 단위 날짜가 어느 달력의 것인지 — <b>미국이면 {@code (미국)}, 국내면 없다</b>(통의 다른
     * 날짜가 전부 KST다).
     *
     * <p>⚠️ <b>꼬리표는 그 자체로 읽혀야 하고, 그 이상은 군더더기다</b> — {@code (현지)}는 어디
     * 현지인지 말하지 않고, {@code (미국 현지)}는 넘친다. 괄호 앞 공백도 두지 않는다.
     */
    private static String calendarTag(StockQuote quote) {
        return quote.market() == StockQuote.Market.US ? "(미국)" : "";
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
     * 읽는 줄이 지저분해진다. <b>대신 어느 값이 낡았는지를 이름으로 짚지는 않는다.</b>
     */
    private static String basisLines(Instant live, Instant closing) {
        StringBuilder lines = new StringBuilder();
        if (live != null) {
            lines.append(dateTime(live));
        }
        if (closing != null) {
            lines.append(live == null ? "" : "\n")
                    .append(date(closing)).append(" (종가)");
        }
        return lines.toString();
    }

    /** 통화 코드까지 붙인 값. 지수는 통화가 없어 숫자만 나간다. */
    private static String priceOf(StockQuote quote) {
        return withUnit(quote, quote.price().value());
    }

    /**
     * 그 종목의 통화를 붙인 숫자 — 시세와 목표주가가 <b>같은 모양</b>이어야 한다.
     *
     * <p>둘이 갈리면 한 블록 안에서 「239,500 KRW」 아래에 「466,667」이 서고, 읽는 사람이
     * 단위를 짐작하게 된다.
     */
    private static String withUnit(StockQuote quote, BigDecimal amount) {
        return switch (quote.currency()) {
            case NONE -> money(amount);
            case KRW -> krwAmount(amount);
            case USD -> money(amount) + " USD";
        };
    }

    /** 환율이 없으면 달러만 보낸다 — 환산을 못 한다고 시세를 빼는 것은 과하다. */
    private static boolean convertible(StockQuote quote, FxRate fx) {
        return quote.currency().convertible() && fx != null;
    }
}
