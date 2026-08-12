package io.saiden.economyhelper.telegram;

import java.util.Locale;
import java.util.Optional;

/**
 * 텔레그램 메시지에서 명령과 인자를 뽑아낸다.
 *
 * <p>그룹 채팅에서는 봇 이름이 붙어 {@code /news@economy_helper_bot 금리}로 온다 —
 * 그대로 두면 인자에 봇 이름이 섞인다.
 *
 * <p><b>{@code /}로 시작하지 않는 메시지는 명령이 아니다.</b> 그룹 채팅의 일반 대화에까지
 * 봇이 반응하면 채팅방이 오염된다.
 */
public final class CommandParser {

    private CommandParser() {
    }

    /**
     * @return 아는 명령이면 명령과 인자. 명령이 아니거나 모르는 명령이면 {@link Optional#empty()}.
     *         인자가 없는 것과 명령이 아닌 것은 다르므로, 인자 유무는
     *         {@link ParsedCommand#hasArgument()}로 구분한다
     */
    public static Optional<ParsedCommand> parse(String text) {
        if (!looksLikeCommand(text)) {
            return Optional.empty();
        }

        String trimmed = text.strip();
        int firstSpace = indexOfWhitespace(trimmed);
        String argument = firstSpace < 0 ? "" : trimmed.substring(firstSpace).strip();

        return Command.of(commandTokenOf(trimmed, firstSpace))
                .map(command -> new ParsedCommand(command, argument));
    }

    /**
     * {@code /}로 시작하지만 우리가 모르는 명령인가.
     *
     * <p>오타({@code /fx}를 {@code /exchange}로)에만 안내를 띄우기 위해 필요하다.
     * 일반 대화는 여기서 걸러지므로 그룹 채팅은 조용하다.
     */
    public static boolean isUnknownCommand(String text) {
        if (!looksLikeCommand(text)) {
            return false;
        }
        String trimmed = text.strip();
        return Command.of(commandTokenOf(trimmed, indexOfWhitespace(trimmed))).isEmpty();
    }

    private static boolean looksLikeCommand(String text) {
        return text != null && !text.isBlank() && text.strip().startsWith("/");
    }

    /** 첫 토큰에서 {@code @봇이름}을 떼고 소문자로 맞춘다. */
    private static String commandTokenOf(String trimmed, int firstSpace) {
        String token = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
        int at = token.indexOf('@');
        if (at >= 0) {
            token = token.substring(0, at);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    /** 텔레그램 클라이언트에 따라 개행이나 탭으로 인자를 넘기는 경우가 있다. */
    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
