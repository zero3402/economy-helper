package io.saiden.economyhelper.testsupport;

import io.saiden.economyhelper.infrastructure.llm.GeminiApi;
import org.springframework.web.client.RestClient;

/**
 * 해석기 테스트가 쓰는 <b>가짜 Gemini</b> — 여러 컨텍스트가 부르므로 {@code testsupport/}에 둔다.
 */
public final class TestGemini {

    private TestGemini() {
    }

    /** 무엇을 물어도 이 본문을 준다. */
    public static GeminiApi answering(String response) {
        return new Fixed(response);
    }

    /** 답을 주면서 <b>프롬프트를 붙잡는다</b> — 프롬프트 자체가 단언 대상일 때 쓴다. */
    public static Recording recording(String response) {
        return new Recording(response);
    }

    /** 죽은 Gemini. {@link Failing#called}로 「불렸는가」를 본다. */
    public static Failing failing() {
        return new Failing();
    }

    private static class Fixed extends GeminiApi {

        private final String response;

        private Fixed(String response) {
            super(RestClient.builder(), "https://example.invalid", "key", "model");
            this.response = response;
        }

        @Override
        public String generate(String prompt) {
            return response;
        }
    }

    /** 마지막으로 받은 프롬프트를 든다. */
    public static final class Recording extends Fixed {

        private String prompt;

        private Recording(String response) {
            super(response);
        }

        @Override
        public String generate(String prompt) {
            this.prompt = prompt;
            return super.generate(prompt);
        }

        public String prompt() {
            return prompt;
        }
    }

    /** 불렸는지까지 알려 준다 — 「빈 검색어에는 LLM을 안 부른다」가 그것으로 갈린다. */
    public static final class Failing extends GeminiApi {

        private boolean called;

        private Failing() {
            super(RestClient.builder(), "https://example.invalid", "key", "model");
        }

        @Override
        public String generate(String prompt) {
            called = true;
            throw new IllegalStateException("Gemini 호출 실패");
        }

        public boolean called() {
            return called;
        }
    }
}
