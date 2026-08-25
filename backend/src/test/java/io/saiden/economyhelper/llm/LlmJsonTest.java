package io.saiden.economyhelper.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 해석의 공통 관문 — <b>여기가 던지면 {@code /stock}·{@code /crypto}·{@code /weather}가
 * 통째로 죽는다.</b> 「LLM이 죽어도 답이 나간다」의 근거가 이 클래스인데 테스트가 없었다.
 *
 * <p>해석기 셋({@code StockResolver}·{@code CryptoResolver}·{@code WeatherResolver})이 전부
 * 이 위에 서 있으므로, 여기서 예외가 새면 세 명령이 함께 빈손이 된다.
 */
class LlmJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Parsed(String code) {}

    /** {@code generate}만 갈아 끼운다 — HTTP는 타지 않는다. {@code null}이면 던지는 LLM이다. */
    private static GeminiApi answering(String body) {
        return new GeminiApi(RestClient.builder(), "https://example.invalid", "", "test-model") {
            @Override
            public String generate(String prompt) {
                if (body == null) {
                    throw new IllegalStateException("LLM 죽었다");
                }
                return body;
            }
        };
    }

    @Test
    @DisplayName("읽히면 그 값을 준다")
    void parsesUsableJson() {
        assertThat(LlmJson.ask(answering("{\"code\":\"005930\"}"), MAPPER, "p", Parsed.class,
                "stock", "삼성전자", parsed -> parsed.code() != null))
                .contains(new Parsed("005930"));
    }

    @Test
    @DisplayName("LLM이 던져도 빈 값이다 — 예외를 올리면 그 명령이 통째로 빈손이 된다")
    void swallowsTheCallFailure() {
        assertThat(LlmJson.ask(answering(null), MAPPER, "p", Parsed.class,
                "stock", "삼성전자", parsed -> true)).isEmpty();
    }

    @Test
    @DisplayName("JSON이 아니어도 빈 값이다 — LLM은 아무 문자열이나 낼 수 있다")
    void swallowsUnparseableOutput() {
        assertThat(LlmJson.ask(answering("죄송합니다, 잘 모르겠습니다"), MAPPER, "p", Parsed.class,
                "stock", "삼성전자", parsed -> true)).isEmpty();
    }

    @Test
    @DisplayName("usable이 거부하면 빈 값이다 — 「특정하지 못했다」와 「고장」을 가른다")
    void rejectsWhatTheCallerCannotUse() {
        assertThat(LlmJson.ask(answering("{\"code\":null}"), MAPPER, "p", Parsed.class,
                "stock", "없는종목", parsed -> parsed.code() != null)).isEmpty();
    }

    @Test
    @DisplayName("본문이 null 리터럴이면 빈 값이다 — usable에 null을 넘기지 않는다")
    void handlesANullDocument() {
        assertThat(LlmJson.ask(answering("null"), MAPPER, "p", Parsed.class,
                "stock", "삼성전자", parsed -> parsed.code() != null)).isEmpty();
    }
}
