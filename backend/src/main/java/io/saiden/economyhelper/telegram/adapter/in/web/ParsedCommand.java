package io.saiden.economyhelper.telegram.adapter.in.web;

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

    /**
     * 사용법을 띄워야 하는가 — 인자가 필요한데 없거나, <b>인자를 안 받는 명령에 인자가 왔거나.</b>
     *
     * <p>⚠️ 뒤엣것을 조용히 버리면 {@code /fx 엔}이 엔화를 물었는데 달러 환율이 나간다 — 물은 것과 다른 답이다.
     */
    public boolean needsUsage() {
        return missingRequiredArgument() || (command.argument() == Command.Argument.NONE && hasArgument());
    }
}
