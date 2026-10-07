package io.saiden.economyhelper.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.testsupport.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM 해석의 공통 관문 — <b>여기가 던지면 {@code /stock}·{@code /crypto}·{@code /weather}가
 * 통째로 죽는다.</b> 해석기 셋({@code StockResolver}·{@code CryptoResolver}·{@code WeatherResolver})이
 * 전부 이 위에 서 있어, 여기서 예외가 새면 세 명령이 함께 빈손이 된다.
 */
class LlmJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Parsed(String code) {}

    /** {@code generate}만 갈아 끼운다 — HTTP는 타지 않는다. {@code null}이면 던지는 LLM이다. */
    private static GeminiApi answering(String body) {
        return new GeminiApi(RestClient.builder(), TestProperties.offline()) {
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
    @DisplayName("객체 하나를 배열로 감싸 와도 읽는다 — Gemini가 가끔 [{…}]로 준다")
    void unwrapsASingleObjectArray() {
        // 실물 감사(2026-09-29): '다음 주말 부산'이 MismatchedInputException으로 빈손이 됐고 같은 입력을
        // 다시 물으면 멀쩡했다 — 한 번씩 다른 모양이 온다. 배열 감싸기가 그 흔한 모양이다
        assertThat(LlmJson.ask(answering("[{\"code\":\"005930\"}]"), MAPPER, "p", Parsed.class,
                "stock", "삼성전자", parsed -> parsed.code() != null))
                .contains(new Parsed("005930"));
        assertThat(LlmJson.ask(answering("[{\"code\":\"1\"},{\"code\":\"2\"}]"), MAPPER, "p", Parsed.class,
                "stock", "삼성전자", parsed -> true))
                .as("둘 이상이면 어느 것인지 모른다 — 고르지 않는다").isEmpty();
    }

    @Test
    @DisplayName("실패 로그에는 받은 글의 모양만 적는다 — 모델이 사용자 입력(연락처 등)을 되풀이할 수 있다")
    void describesTheReplyWithoutItsText() {
        String shape = LlmJson.shapeOf(new ObjectMapper(), "{\"query\":\"서울\",\"days\":\"연락처 010-1234-5678\"}");

        assertThat(shape).doesNotContain("010").doesNotContain("서울").contains("object");
        assertThat(LlmJson.shapeOf(new ObjectMapper(), "{\"days\":\"x\",\"연락처 010-1234-5678\":null}"))
                .as("키 이름도 모델이 낸 글이다").doesNotContain("010").doesNotContain("연락처");
        assertThat(LlmJson.shapeOf(new ObjectMapper(), "죄송합니다 010-1234-5678")).doesNotContain("010");
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
