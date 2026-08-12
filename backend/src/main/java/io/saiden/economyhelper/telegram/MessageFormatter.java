package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService.StockMatch;
import io.saiden.economyhelper.news.NewsItem;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * {@link NewsItem}을 텔레그램 메시지 텍스트로 옮긴다.
 *
 * <p>서식 없는 평문으로 보낸다. Markdown/HTML 모드를 쓰면 기사 제목의 {@code *}, {@code _},
 * {@code [} 같은 문자가 파싱 오류를 내 발송 자체가 실패한다 — 이스케이프 규칙을 따라다니느니
 * 평문이 안전하다.
 */
public final class MessageFormatter {

    /** 시세는 "언제 값인지"가 값 자체만큼 중요하다. 사용자는 한국에 있으므로 KST로 보여준다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 하루 한 번 고시하는 값(수출입은행)에는 시각을 붙이지 않는다. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd");

    private MessageFormatter() {
    }

    public static String format(NewsItem item) {
        StringBuilder message = new StringBuilder();
        message.append("📌 [").append(item.sourceName()).append("]\n");
        message.append(item.title()).append("\n");

        if (!item.body().isBlank()) {
            message.append("\n").append(item.body()).append("\n");
        }
        if (!item.translated()) {
            // 왜 영문인지 밝히지 않으면 고장으로 보인다
            message.append("\n⚠️ 번역이 일시적으로 불가해 원문 그대로 보냅니다.\n");
        }

        message.append("\n🔗 ").append(item.link());
        return message.toString();
    }

    /** 정기 발송 — 매체별 1건을 한 메시지에 묶는다. */
    public static String formatDigest(List<NewsItem> items) {
        if (items.isEmpty()) {
            return "지금은 가져올 수 있는 뉴스가 없습니다.";
        }
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                message.append("\n\n———\n\n");
            }
            message.append(format(items.get(i)));
        }
        return message.toString();
    }

    public static String noResults(String query) {
        return "'" + query + "'에 해당하는 뉴스를 찾지 못했습니다.";
    }

    /**
     * 원/달러 환율.
     *
     * <p><b>출처와 기준시각을 반드시 밝힌다.</b> 토스가 죽어 수출입은행으로 폴백하면
     * 주말엔 며칠 전 값이 나가는데, 그걸 숨기면 고장이 아니라 거짓말이 된다.
     * 하루 한 번 고시하는 값에 분 단위를 붙이면 실제보다 신선해 보이므로 표기도 달리한다.
     */
    public static String formatFx(FxRate rate) {
        String when = rate.source().intraday()
                ? timestamp(rate.asOf()) + " 기준"
                : DATE.format(rate.asOf().atZone(SEOUL)) + " 고시";
        return "💱 원/달러 환율\n"
                + money(rate.rate()) + "원\n"
                + "· " + rate.source().displayName() + " · " + when;
    }

    public static String fxUnavailable() {
        return "환율을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    /**
     * 주식 시세.
     *
     * <p><b>기준일을 반드시 밝힌다.</b> 공공데이터포털은 전일 종가를 주므로, 날짜를 숨기면
     * 장중에 물었을 때 실시간 현재가로 오해한다 — 환율에서 출처와 고시일을 밝히는 것과 같은 규칙이다.
     *
     * <p>함께 걸린 다른 후보를 한 줄로 덧붙인다. 되묻는 것보다 낫다 —
     * 텔레그램에서 "어느 것입니까"를 물으면 대화가 두 번 오간다.
     */
    public static String formatStock(StockMatch match) {
        StockQuote quote = match.quote();
        // 지수는 종목코드도 통화 단위도 없다 — "원"을 붙이면 틀린 값이 된다
        StringBuilder message = new StringBuilder(quote.isIndex() ? "📊 " : "📈 ")
                .append(quote.name());
        if (!quote.isIndex()) {
            message.append(" (").append(quote.code()).append(" · ").append(quote.market()).append(")");
        }
        message.append("\n")
                .append(money(quote.price())).append(quote.isIndex() ? "" : "원").append("\n")
                .append("· 공공데이터포털 · ").append(DATE.format(quote.basisDate())).append(" 종가");

        if (!match.alternatives().isEmpty()) {
            message.append("\n다른 결과: ").append(String.join(", ", match.alternatives()));
        }
        return message.toString();
    }

    public static String stockNotFound(String query) {
        return "'" + query + "'에 해당하는 종목을 찾지 못했습니다.\n"
                + "국내 상장 종목과 지수만 조회할 수 있습니다.\n"
                + "예) /stock 삼성전자, /stock 005930, /stock 코스피";
    }

    /**
     * 아침 브리핑의 주식 통.
     *
     * <p>기준일을 <b>제목에 한 번만</b> 쓴다 — 종목마다 붙이면 같은 날짜가 다섯 번 반복된다.
     * 전부 같은 조회에서 나오므로 날짜도 같다.
     */
    public static String formatStockDigest(List<StockQuote> quotes) {
        StringBuilder message = new StringBuilder("📈 주식");
        quotes.stream().findFirst().ifPresent(first ->
                message.append(" (").append(DATE.format(first.basisDate())).append(" 종가)"));
        message.append("\n");
        for (StockQuote quote : quotes) {
            message.append("\n").append(quote.name()).append("  ").append(money(quote.price())).append("원");
        }
        return message.toString();
    }

    /** 아침 브리핑의 코인 통. 24시간 거래되므로 기준일이 아니라 시각을 쓴다. */
    public static String formatCryptoDigest(List<CryptoQuote> quotes) {
        StringBuilder message = new StringBuilder("🪙 코인");
        quotes.stream().findFirst().ifPresent(first ->
                message.append(" (").append(timestamp(first.at())).append(" 기준)"));
        message.append("\n");
        for (CryptoQuote quote : quotes) {
            message.append("\n").append(quote.koreanName()).append("  ")
                    .append(money(quote.price())).append("원");
        }
        return message.toString();
    }

    /**
     * 코인 현재가.
     *
     * <p>등락률을 넣지 않는다 — 토스가 주식 등락률을 주지 않아, 코인에만 붙이면
     * 명령마다 정보 밀도가 달라진다. {@code CLAUDE.md}가 요구하는 것도 "현재 가격"이다.
     */
    public static String formatCrypto(CryptoQuote quote) {
        return "🪙 " + quote.koreanName() + " (" + quote.market() + ")\n"
                + money(quote.price()) + "원\n"
                + "· 업비트 · " + timestamp(quote.at());
    }

    public static String cryptoNotFound(String query) {
        return "'" + query + "'에 해당하는 코인을 찾지 못했습니다. 업비트 원화 마켓에 있는 이름이나 심볼로 입력해 주세요.\n"
                + "예) /crypto 비트코인, /crypto BTC";
    }

    /**
     * 천 단위로 끊는다. 코인 가격은 자릿수 폭이 커서(89,848,000원 ~ 0.5원) 구분이 없으면 읽기 어렵다.
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
        return TIMESTAMP.format(at.atZone(SEOUL));
    }

    /** 인자가 필요한 명령을 인자 없이 보냈을 때. 명령마다 예시가 다르다. */
    public static String usage(Command command) {
        return "검색어를 함께 입력해 주세요. 예) " + command.example();
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
        StringBuilder message = new StringBuilder("사용할 수 있는 명령입니다.\n");
        for (Command command : Command.values()) {
            message.append("\n").append(command.example()).append(" — ").append(describe(command));
        }
        return message.toString();
    }

    private static String describe(Command command) {
        return switch (command) {
            case NEWS -> "검색어에 해당하는 뉴스 1건";
            case FX -> "원/달러 환율";
            case STOCK -> "현재 주가";
            case CRYPTO -> "현재 코인 시세";
            case HELP -> "이 도움말";
        };
    }
}
