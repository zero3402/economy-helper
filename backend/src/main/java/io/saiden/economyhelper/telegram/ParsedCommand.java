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

    /**
     * 인자가 <b>반드시 필요한</b> 명령인데 인자가 없다 — 사용법을 띄워야 하는 상태.
     *
     * <p>{@link Command.Argument#OPTIONAL}은 여기서 걸리지 않는다. {@code /news}가 검색어
     * 없이 와도 그 명령의 기본 답이 있으므로 통과시키고, 갈래는 호출부가
     * {@link #hasArgument()}로 고른다.
     */
    public boolean missingRequiredArgument() {
        return command.argument() == Command.Argument.REQUIRED && !hasArgument();
    }
}
