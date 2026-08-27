package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.head;
import static io.saiden.economyhelper.telegram.MessageLayout.title;

import java.util.ArrayList;
import java.util.List;


/**
 * 도움말과 사용법 안내.
 *
 * <p><b>명령 목록을 {@link Command}에서 만든다.</b> 예전에는 여기가 {@code switch}로 여섯 갈래를
 * 따로 들고 있었는데, 그건 {@link Command#section()}과 <b>같은 사실을 담은 두 번째 표</b>였고
 * 이미 어긋나 있었다 — 도움말은 {@code /stock}을 「주식」이라 적고 정작 답은 「증시」로 나갔다.
 */
public final class HelpFormatter {

    private HelpFormatter() {
    }

    /**
     * 인자가 필요한 명령을 인자 없이 보냈을 때. 명령마다 예시가 다르다.
     *
     * <p>⚠️ <b>세 갈래 전부가 도달하지 않아도 참이어야 한다.</b> 인자를 안 받는 명령
     * ({@code /fx}·{@code /help})과 없어도 되는 명령({@code /news})은
     * {@code missingRequiredArgument()}가 막아 여기까지 오지 않지만, 그 자리에서
     * "검색어를 함께 입력해 주세요"가 나오던 것은 <b>글자 그대로 틀린 안내</b>였다 —
     * 도달 불가라는 이유로 틀린 문장을 남겨 두면 다음에 누가 막는 걸 잊었을 때 그게 나간다.
     * 게다가 이 문구는 {@code RenderedOutputTest}가 명령 전부를 훑어 골든에 굳힌다.
     *
     * <p>{@code default} 없는 switch 식이라 <b>{@link Command.Argument}에 상태를 더하면
     * 컴파일이 깨진다</b> — 문구를 빠뜨려 조용히 틀린 안내가 나가는 일을 컴파일러가 막는다.
     */
    public static String usage(Command command) {
        String hint = switch (command.argument()) {
            case REQUIRED -> "검색어를 함께 입력해 주세요.";
            case OPTIONAL -> "검색어는 있어도 되고 없어도 됩니다.";
            case NONE -> "이 명령은 검색어 없이 씁니다.";
        };
        return head(command) + hint + "\n\n예) " + Html.escape(command.example());
    }

    /**
     * {@code /}로 시작하는 모르는 명령에만 띄운다.
     *
     * <p>일반 대화에는 반응하지 않는다 — 그룹 채팅이 오염된다.
     *
     * <p>{@link #help()}를 통째로 붙이지 않는다. 그러면 굵은 제목이 둘 연달아 찍혀
     * 무엇이 이 메시지의 제목인지 흐려진다 — 목록 본문만 빌린다.
     */
    public static String unknownCommand() {
        return "<b>모르는 명령</b>\n\n입력하신 명령을 찾지 못했습니다." + commandList();
    }

    /**
     * 도움말.
     *
     * <p><b>방·토픽 번호를 적지 않는다.</b> 사용자가 볼 화면에 내부 배관을 늘어놓을 이유가
     * 없다. 설정에 넣을 그 값은 명령을 받을 때마다 {@code TelegramWebhookController}가 INFO
     * 로그로 남긴다 — 설정하는 사람은 로그를 보고, 쓰는 사람은 안 봐도 된다.
     */
    public static String help() {
        return title(Command.HELP) + commandList();
    }

    /**
     * 명령 목록 본문. 도움말과 "모르는 명령"이 나눠 쓴다.
     *
     * <p><b>줄임말을 예시 뒤에 붙인다.</b> 줄을 따로 만들지 않는 이유는 목록이 이미
     * 「예시 / 설명」 두 줄 짜임이라, 세 줄이 되면 명령 여섯 개가 화면 한 통을 다 먹는다.
     * <p>⚠️ <b>지금은 여섯 명령이 모두 줄임말을 든다</b>({@code Command}) — 그래서 아래 분기는
     * 언제나 참이다. 지우지 않는 이유는 <b>줄임말 없는 명령을 더할 여지</b>를 남기기 위해서다.
     * 예전에 이 자리가 「{@code /news}에는 줄임말이 없어 그 줄이 그대로다」라고 적고 있었는데,
     * {@code /n}이 붙은 뒤로 <b>글자 그대로 틀린 문장</b>이었다 — 주석이 설계 문서인 저장소에서
     * 거짓 주석은 다음 사람을 틀린 결론으로 데려간다.
     */
    private static String commandList() {
        StringBuilder list = new StringBuilder();
        for (Command command : Command.values()) {
            list.append("\n\n").append(Html.escape(command.example()));
            String notes = notesOf(command);
            if (!notes.isEmpty()) {
                list.append(" (").append(notes).append(")");
            }
            list.append("\n").append(describe(command));
        }
        return list.toString();
    }

    /**
     * 예시 뒤 괄호에 들어가는 것들 — 줄임말, 그리고 <b>검색어를 생략할 수 있다는 사실</b>.
     *
     * <p>후자를 적는 이유는 <b>안 적으면 도움말이 새 기능을 모르기 때문</b>이다.
     * {@code /news}는 검색어 없이도 답하는데, 목록이 {@code /news 금리}만 보여 주면
     * 사용자가 그걸 알 길이 없다 — "사용자가 모르는 줄임말은 없는 것과 같다"와 같은 이유다.
     *
     * <p><b>열거형을 읽어 짚는다.</b> 명령 이름으로 분기하면 이 파일이 또 하나의
     * 「두 번째 표」가 된다 — 이 클래스 주석이 적어 둔 바로 그 함정이다.
     *
     * <p>한 줄에 이어 붙여 목록의 <b>두 줄 짜임</b>을 지킨다. 세 줄이 되면 명령 여섯 개가
     * 화면 한 통을 다 먹는다.
     */
    private static String notesOf(Command command) {
        List<String> notes = new ArrayList<>(2);
        if (command.shortToken() != null) {
            notes.add("또는 " + Html.escape(command.shortToken()));
        }
        if (command.argument() == Command.Argument.OPTIONAL) {
            notes.add("검색어 생략 가능");
        }
        return String.join(" · ", notes);
    }

    /**
     * 목록에 적는 한 줄 설명 — <b>그 명령이 답하는 통의 이름이다.</b>
     *
     * <p>예전에는 여기가 {@code switch}로 여섯 갈래를 따로 들고 있었는데, 그건
     * {@link Command#section()}과 <b>같은 사실을 담은 두 번째 표</b>였고 이미 어긋나 있었다 —
     * 도움말은 {@code /stock}을 「주식」이라 적고 정작 답은 「증시」로 나갔다. {@code Command}가
     * "분기문이 아니라 상수가 직접 들고 있는 이유"로 적어 둔 바로 그 함정이다.
     *
     * <p>{@code HELP}만 예외다. 그 제목({@code 사용할 수 있는 명령})은 이 <b>목록 자체의
     * 제목</b>이라 목록 안에 그대로 적으면 한 통에서 같은 말을 두 번 하게 된다.
     */
    private static String describe(Command command) {
        return command == Command.HELP ? "도움말" : command.section();
    }
}
