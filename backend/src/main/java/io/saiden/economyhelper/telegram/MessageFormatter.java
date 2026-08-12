package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.CryptoQuote;
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
