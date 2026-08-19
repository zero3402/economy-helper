package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.DATE_TIME;
import static io.saiden.economyhelper.telegram.MessageLayout.SEOUL;
import static io.saiden.economyhelper.telegram.MessageLayout.SHORT_DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.appendChangeLine;
import static io.saiden.economyhelper.telegram.MessageLayout.change;
import static io.saiden.economyhelper.telegram.MessageLayout.empty;
import static io.saiden.economyhelper.telegram.MessageLayout.head;
import static io.saiden.economyhelper.telegram.MessageLayout.krw;
import static io.saiden.economyhelper.telegram.MessageLayout.money;
import static io.saiden.economyhelper.telegram.MessageLayout.oneDecimal;
import static io.saiden.economyhelper.telegram.MessageLayout.sources;
import static io.saiden.economyhelper.telegram.MessageLayout.title;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.FxRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 코인 통 — <b>브리핑도 {@code /crypto} 한 건도 이것 하나를 쓴다.</b>
 * 24시간 거래되므로 기준일이 아니라 시각을 쓴다.
 *
 * <p>제목은 <b>굵은 티커 + 한글 이름</b>이다. 마켓 코드나 바이낸스 심볼은 적지 않는다 —
 * 거래소는 값 줄에 이름으로 적혀 있다.
 *
 * <p><b>값이 없는 코인도 빼지 않는다.</b> 없는 쪽을 {@code 미상장}·{@code 조회 실패}로 갈라
 * 적는다 — 사용자에게 전자는 "영영 없음", 후자는 "잠시 뒤 다시"다.
 */
public final class CryptoFormatter {

    private CryptoFormatter() {
    }

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
    public static String format(List<CryptoQuote> quotes, FxRate fx) {
        if (quotes.isEmpty()) {
            return empty(Command.CRYPTO);
        }
        StringBuilder message = new StringBuilder(head(Command.CRYPTO));
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

    public static String notFound(String query) {
        return head(Command.CRYPTO)
                + "'" + Html.escape(query) + "'에 해당하는 코인을 찾지 못했습니다.\n\n"
                + "업비트 또는 바이낸스에 상장된 이름이나 심볼로 입력해 주세요.\n\n"
                + "예) /crypto 비트코인 · /crypto BTC";
    }
}
