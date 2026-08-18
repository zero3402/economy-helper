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

    /** {@code /news 금리} — 검색어에 해당하는 오늘 기사 중 점수 상위 3건. */
    NEWS("/news", "뉴스", true, "/news 금리"),

    /** {@code /fx} — 원/달러 환율. 인자가 없다. */
    FX("/fx", "환율", false, "/fx"),

    /** {@code /stock 삼성전자} — 현재 주가. */
    STOCK("/stock", "증시", true, "/stock 삼성전자"),

    /** {@code /crypto 비트코인} — 현재 코인 시세. */
    CRYPTO("/crypto", "코인", true, "/crypto 비트코인"),

    /**
     * {@code /weather 내일 성남} — 일일 날씨. 전 세계를 다룬다.
     *
     * <p>인자에 지역과 기간이 함께 들어온다({@code CommandParser}가 첫 공백 뒤를 통째로 넘긴다).
     * 둘을 가르는 일은 LLM({@code WeatherResolver})이 한다.
     */
    WEATHER("/weather", "날씨", true, "/weather 내일 성남"),

    /** {@code /help} — 명령 목록. */
    HELP("/help", "사용할 수 있는 명령", false, "/help");

    private final String token;
    private final String section;
    private final boolean requiresArgument;
    private final String example;

    Command(String token, String section, boolean requiresArgument, String example) {
        this.token = token;
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
