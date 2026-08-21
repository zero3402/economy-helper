package io.saiden.economyhelper.telegram;

import java.util.Locale;
import java.util.Optional;

/**
 * 봇이 아는 명령.
 *
 * <p>토큰과 "인자가 필요한가"를 각 상수가 직접 들고 있다. 분기문으로 흩어 두면 명령을
 * 하나 더할 때마다 여러 곳을 고쳐야 하고, 그중 하나를 빠뜨리면 그 명령만 조용히 동작하지 않는다.
 *
 * <p><b>줄임말도 여기 있다.</b> 여섯 명령이 모두 첫 글자 하나를 든다 —
 * {@code /n}·{@code /f}·{@code /s}·{@code /c}·{@code /w}·{@code /h}. 파서가 아니라 상수가
 * 드는 이유는 {@link HelpFormatter}가 열거형을 훑는 것만으로 도움말에 함께 따라 나오게
 * 하기 위해서다. 목록을 파서에 두면 도움말이 그것을 모르는 <b>두 번째 표</b>가 되고,
 * 이 파일이 이미 한 번 그 함정으로 어긋난 적이 있다(「주식」 대 「증시」).
 *
 * <p>⚠️ <b>첫 글자가 겹치는 명령을 새로 더할 때 조용히 넘어가지 않는다.</b> 지금은 여섯이
 * 서로 다른 글자로 나뉘어 남는 글자가 있지만, 겹치는 것을 더하면 둘 중 하나가 먼저 걸려
 * 나머지가 죽는다 — {@code CommandParserTest}가 줄임말이 서로 겹치지 않는지 본다.
 */
public enum Command {

    /** {@code /news 금리} — 검색어에 해당하는 오늘 기사 중 점수 상위 3건. */
    NEWS("/news", "/n", "뉴스", true, "/news 금리"),

    /** {@code /fx} — 원/달러 환율. 인자가 없다. */
    FX("/fx", "/f", "환율", false, "/fx"),

    /** {@code /stock 삼성전자} — 현재 주가. */
    STOCK("/stock", "/s", "증시", true, "/stock 삼성전자"),

    /** {@code /crypto 비트코인} — 현재 코인 시세. */
    CRYPTO("/crypto", "/c", "코인", true, "/crypto 비트코인"),

    /**
     * {@code /weather 내일 성남} — 일일 날씨. 전 세계를 다룬다.
     *
     * <p>인자에 지역과 기간이 함께 들어온다({@code CommandParser}가 첫 공백 뒤를 통째로 넘긴다).
     * 둘을 가르는 일은 LLM({@code WeatherResolver})이 한다.
     */
    WEATHER("/weather", "/w", "날씨", true, "/weather 내일 성남"),

    /** {@code /help} — 명령 목록. */
    HELP("/help", "/h", "사용할 수 있는 명령", false, "/help");

    private final String token;
    private final String shortToken;
    private final String section;
    private final boolean requiresArgument;
    private final String example;

    Command(String token, String shortToken, String section, boolean requiresArgument, String example) {
        this.token = token;
        this.shortToken = shortToken;
        this.section = section;
        this.requiresArgument = requiresArgument;
        this.example = example;
    }

    /**
     * 이 명령이 답하는 통의 이름 — <b>메시지 맨 위에 굵게 찍히는 그 제목이다.</b>
     *
     * <p>성공 답과 실패 답이 같은 제목을 이고 있어야 한다. 실패했을 때만 제목 없이
     * 맨몸 문장이 오면 무엇에 대한 답인지 알 수 없고, 그룹 채팅에서는 특히 그렇다.
     *
     * <p>분기문이 아니라 상수가 직접 들고 있는 이유는 {@link #example}과 같다 —
     * 명령을 하나 더할 때 빠뜨릴 자리를 남기지 않는다.
     */
    public String section() {
        return section;
    }

    /** 참이면 인자 없이 온 호출에 사용법을 띄워야 한다 — 무엇을 찾을지 알 수 없기 때문이다. */
    public boolean requiresArgument() {
        return requiresArgument;
    }

    /** 사용법 안내에 쓸 예시. */
    public String example() {
        return example;
    }

    /**
     * 줄임말. 없으면 {@code null}이다.
     *
     * <p>화면에 적기 위해 공개한다 — 도움말이 이것을 읽어 「또는 /c」를 붙인다.
     * 사용자가 모르는 줄임말은 없는 것과 같다.
     */
    public String shortToken() {
        return shortToken;
    }

    /**
     * 토큰으로 명령을 찾는다 — <b>정식 이름과 줄임말을 함께 본다.</b>
     *
     * <p>{@code CommandParser}는 안 고친다. 이미 이 메서드만 부르므로 줄임말이 여기서 걸리면
     * 인자 자르기·{@code @botname} 떼기·대소문자 같은 나머지 규칙이 그대로 적용된다.
     */
    static Optional<Command> of(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        for (Command command : values()) {
            if (command.token.equals(normalized) || normalized.equals(command.shortToken)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
