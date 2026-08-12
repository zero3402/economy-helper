package io.saiden.economyhelper.telegram;

import java.util.Locale;
import java.util.Optional;

/**
 * 봇이 아는 명령.
 *
 * <p>토큰과 "인자가 필요한가"를 각 상수가 직접 들고 있다. 분기문으로 흩어 두면 명령을
 * 하나 더할 때마다 여러 곳을 고쳐야 하고, 그중 하나를 빠뜨리면 그 명령만 조용히 동작하지 않는다.
 */
public enum Command {

    /** {@code /news 금리} — 검색어에 해당하는 1위 기사. */
    NEWS("/news", true, "/news 금리"),

    /** {@code /fx} — 원/달러 환율. 인자가 없다. */
    FX("/fx", false, "/fx"),

    /** {@code /stock 삼성전자} — 현재 주가. */
    STOCK("/stock", true, "/stock 삼성전자"),

    /** {@code /crypto 비트코인} — 현재 코인 시세. */
    CRYPTO("/crypto", true, "/crypto 비트코인"),

    /** {@code /help} — 명령 목록. */
    HELP("/help", false, "/help");

    private final String token;
    private final boolean requiresArgument;
    private final String example;

    Command(String token, boolean requiresArgument, String example) {
        this.token = token;
        this.requiresArgument = requiresArgument;
        this.example = example;
    }

    public String token() {
        return token;
    }

    /** 참이면 인자 없이 온 호출에 사용법을 띄워야 한다 — 무엇을 찾을지 알 수 없기 때문이다. */
    public boolean requiresArgument() {
        return requiresArgument;
    }

    /** 사용법 안내에 쓸 예시. */
    public String example() {
        return example;
    }

    static Optional<Command> of(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        for (Command command : values()) {
            if (command.token.equals(normalized)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
