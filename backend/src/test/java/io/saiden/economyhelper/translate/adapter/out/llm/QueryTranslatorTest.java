package io.saiden.economyhelper.translate.adapter.out.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.infrastructure.llm.GeminiApi;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.testsupport.WireMockTest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 검색어 번역을 WireMock으로 세워 외부 네트워크와 API 키 없이 검증한다. */
@WireMockTest.WireMockOptions(http2PlainDisabled = true)
class QueryTranslatorTest extends WireMockTest {

    @Test
    @DisplayName("토큰 여럿을 한 번에 묻고 토큰별로 받는다 — 토큰마다 부르면 검색 한 번이 Gemini 한도를 토큰 수만큼 쓴다")
    void translatesEveryTokenInOneCall() {
        stub(200, geminiResponse(results(
                answer("비트코인", "bitcoin", "btc"),
                answer("금리", "interest rate", "rates"))));

        Map<String, List<String>> terms = translator().toEnglishTerms(List.of("비트코인", "금리"));

        assertThat(terms.get("비트코인")).containsExactly("bitcoin", "btc");
        assertThat(terms.get("금리")).containsExactly("interest rate", "rates");
        server.verify(1, postRequestedFor(anyUrl()));
        server.verify(postRequestedFor(anyUrl())
                .withRequestBody(containing("1. 비트코인"))
                .withRequestBody(containing("2. 금리")));
    }

    @Test
    @DisplayName("공백·중복을 정리하고 5개로 자른다 — 묶음이 커지면 엉뚱한 기사가 걸린다")
    void trimsDeduplicatesAndCaps() {
        stub(200, geminiResponse(results(answer("반도체", " chip ", "chip", "chips", "a", "b", "c", "d"))));

        assertThat(translator().toEnglishTerms(List.of("반도체")).get("반도체"))
                .containsExactly("chip", "chips", "a", "b", "c");
    }

    @Test
    @DisplayName("답이 하나 빠져도 되돌려 준 검색어로 짝을 찾는다 — 자리로 맞추면 짝이 밀린다")
    void pairsByTheEchoedTokenWhenAnAnswerIsMissing() {
        stub(200, geminiResponse(results(answer("금리", "interest rate"))));

        Map<String, List<String>> terms = translator().toEnglishTerms(List.of("비트코인", "금리"));

        assertThat(terms).containsOnlyKeys("금리");
        assertThat(terms.get("금리")).containsExactly("interest rate");
    }

    @Test
    @DisplayName("순서가 바뀌어 와도 되돌려 준 검색어로 짝을 찾는다")
    void pairsByTheEchoedTokenWhenTheOrderIsSwapped() {
        stub(200, geminiResponse(results(
                answer("금리", "interest rate"),
                answer("비트코인", "bitcoin"))));

        Map<String, List<String>> terms = translator().toEnglishTerms(List.of("비트코인", "금리"));

        assertThat(terms.get("비트코인")).containsExactly("bitcoin");
        assertThat(terms.get("금리")).containsExactly("interest rate");
    }

    @Test
    @DisplayName("묻지 않은 검색어의 답은 버린다 — 바뀐 짝이 30일 캐시에 굳으면 남의 기사를 찾는다")
    void dropsAnAnswerForATokenThatWasNotAsked() {
        stub(200, geminiResponse(results(
                answer("비트코인", "bitcoin"),
                answer("환율", "exchange rate"))));

        Map<String, List<String>> terms = translator().toEnglishTerms(List.of("비트코인", "금리"));

        assertThat(terms).containsOnlyKeys("비트코인");
    }

    @Test
    @DisplayName("같은 검색어의 답이 둘이면 고르지 않는다 — 그 검색어만 빠진다")
    void neverPicksBetweenTwoAnswersForOneToken() {
        stub(200, geminiResponse(results(
                answer("금리", "interest rate"),
                answer("금리", "yield"),
                answer("비트코인", "bitcoin"))));

        assertThat(translator().toEnglishTerms(List.of("비트코인", "금리"))).containsOnlyKeys("비트코인");
    }

    @Test
    @DisplayName("되돌려 준 검색어는 대소문자·앞뒤 공백만 봐준다")
    void toleratesCaseAndPaddingInTheEcho() {
        stub(200, geminiResponse(results(answer(" S&P500지수 ", "S&P 500"))));

        assertThat(translator().toEnglishTerms(List.of("s&p500지수"))).containsOnlyKeys("s&p500지수");
    }

    @Test
    @DisplayName("표현이 빈 토큰은 그 토큰만 빠진다 — 빈 목록으로 담지 않는다")
    void leavesOutATokenWithNoTerms() {
        stub(200, geminiResponse(results(answer("비트코인", "bitcoin"), answer("금리"))));

        assertThat(translator().toEnglishTerms(List.of("비트코인", "금리"))).containsOnlyKeys("비트코인");
    }

    @Test
    @DisplayName("하나도 못 옮겼으면 예외 — 폴백 판단은 부르는 쪽의 몫이다")
    void throwsWhenNothingWasTranslated() {
        stub(200, geminiResponse(results(answer("금리"))));

        assertThatThrownBy(() -> translator().toEnglishTerms(List.of("금리")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비어 있습니다");
    }

    @Test
    @DisplayName("results 필드가 없으면 예외")
    void throwsWhenResultsFieldMissing() {
        stub(200, geminiResponse("{\\\"other\\\": 1}"));

        assertThatThrownBy(() -> translator().toEnglishTerms(List.of("금리")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("results");
    }

    @Test
    @DisplayName("모델이 JSON이 아닌 걸 뱉으면 예외")
    void throwsWhenModelReturnsNonJson() {
        stub(200, geminiResponse("죄송합니다, 번역할 수 없습니다"));

        assertThatThrownBy(() -> translator().toEnglishTerms(List.of("금리")))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("429면 예외를 던진다")
    void throwsOnRateLimit() {
        stub(429, "{\"error\":{\"message\":\"Quota exceeded\"}}");

        assertThatThrownBy(() -> translator().toEnglishTerms(List.of("금리")))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("빈 목록에는 부르지 않는다")
    void neverCallsForNoTokens() {
        assertThat(translator().toEnglishTerms(List.of())).isEmpty();

        server.verify(0, postRequestedFor(anyUrl()));
    }

    /** Gemini가 text 칸에 담아 줄 JSON — 바깥 응답 JSON 안의 문자열이라 따옴표를 한 번 더 감싼다. */
    private static String results(String... answers) {
        return "{\\\"results\\\": [" + String.join(", ", answers) + "]}";
    }

    private static String answer(String source, String... terms) {
        String quoted = Arrays.stream(terms)
                .map(term -> "\\\"" + term + "\\\"")
                .collect(Collectors.joining(", "));
        return "{\\\"source\\\": \\\"" + source + "\\\", \\\"terms\\\": [" + quoted + "]}";
    }

    private void stub(int status, String body) {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String geminiResponse(String escapedText) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escapedText + "\"}]}}]}";
    }

    private QueryTranslator translator() {
        return new QueryTranslator(
                new GeminiApi(RestClient.builder(), TestProperties.builder()
                        .gemini(server.baseUrl(), "test-key", "test-model").build()),
                JsonMapper.builder().build());
    }
}
