package io.saiden.economyhelper.telegram;

/**
 * 파싱된 명령 한 건.
 *
 * @param command  알아본 명령
 * @param argument 명령 뒤에 붙은 인자. 없으면 빈 문자열이다 — {@code null}을 쓰지 않는 이유는
 *                 호출부가 {@link #hasArgument()}만 보면 되게 하기 위해서다
 */
public record ParsedCommand(Command command, String argument) {

    public boolean hasArgument() {
        return !argument.isEmpty();
    }

    /** 인자가 필요한 명령인데 인자가 없다 — 사용법을 띄워야 하는 상태. */
    public boolean missingRequiredArgument() {
        return command.requiresArgument() && !hasArgument();
    }
}
