package io.saiden.economyhelper.telegram.adapter.in.web;

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
        return split(text).flatMap(parts -> Command.of(parts.commandToken())
                .map(command -> new ParsedCommand(command, parts.argument())));
    }

    /**
     * {@code /}로 시작하지만 우리가 모르는 명령인가.
     *
     * <p>오타({@code /fx}를 {@code /exchange}로)에만 안내를 띄우기 위해 필요하다.
     * 일반 대화는 여기서 걸러지므로 그룹 채팅은 조용하다.
     */
    public static boolean isUnknownCommand(String text) {
        return split(text).map(parts -> Command.of(parts.commandToken()).isEmpty()).orElse(false);
    }

    /**
     * 첫 토큰의 {@code @봇이름} — 그룹에서 어느 봇을 불렀는지.
     *
     * <p>명령이 아니거나 이름이 안 붙었으면 {@link Optional#empty()}다. 다른 봇을 부른 명령에 우리가 답하면
     * 그 봇과 답이 겹치므로, 호출부가 우리 이름과 대 본다.
     */
    public static Optional<String> mentionOf(String text) {
        return split(text).map(Parts::mention).filter(mention -> !mention.isEmpty());
    }

    /**
     * 메시지를 첫 토큰과 인자로 가른다 — 세 질문(무슨 명령·모르는 명령인가·어느 봇)이 <b>같은 자르기</b>를 쓴다.
     *
     * @return {@code /}로 시작하지 않으면(일반 대화) {@link Optional#empty()}
     */
    private static Optional<Parts> split(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = normalizeSpaces(text).strip();
        if (!trimmed.startsWith("/")) {
            return Optional.empty();
        }
        int firstSpace = indexOfWhitespace(trimmed);
        return Optional.of(firstSpace < 0
                ? new Parts(trimmed, "")
                : new Parts(trimmed.substring(0, firstSpace), trimmed.substring(firstSpace).strip()));
    }

    /**
     * @param head     첫 토큰 그대로({@code /news@economy_helper_bot})
     * @param argument 나머지. 없으면 빈 문자열
     */
    private record Parts(String head, String argument) {

        /** {@code @봇이름}을 떼고 소문자로 맞춘 명령 토큰({@code /news}). */
        String commandToken() {
            int at = head.indexOf('@');
            return (at < 0 ? head : head.substring(0, at)).toLowerCase(Locale.ROOT);
        }

        /** 첫 {@code @} 뒤의 봇 이름. 안 붙었으면 빈 문자열. */
        String mention() {
            int at = head.indexOf('@');
            return at < 0 ? "" : head.substring(at + 1);
        }
    }

    /**
     * NBSP 같은 공백 글자를 보통 공백으로 바꾼다.
     *
     * <p>{@link Character#isWhitespace}와 {@link String#strip}은 NBSP를 공백으로 보지 않는다 —
     * 웹에서 붙여넣은 {@code /s 삼성전자}가 모르는 명령이 되고, 인자 안에 남으면 뒤 단계의 공백 규칙도 비켜 간다.
     */
    private static String normalizeSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isSpaceChar(c) && !Character.isWhitespace(c) ? ' ' : c);
        }
        return out.toString();
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
