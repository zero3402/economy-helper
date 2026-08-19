package io.saiden.economyhelper.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * <p><b>이 클래스는 번역기가 아니다.</b> 그래서 {@code translate}에서 나왔다 — 여섯 호출자가
 * 나눠 쓰는 <b>전송 계층</b>이고(번역기 둘, 해석기 넷), {@code @RateLimiter("gemini")}를 이고
 * 있는 자리다. 예전에는 {@code market}·{@code market.weather}·{@code news.rank}가 전부
 * {@code translate}를 import해서 "종목 해석은 번역의 관심사"처럼 읽혔다.
 * {@code ARCHITECTURE.md} 4-3의 "리미터는 앱키 단위"는 <b>계정</b>의 성질이지 번역의 성질이 아니다.
 *
 * Gemini {@code generateContent} 호출 한 군데.
 *
 * <p><b>레이트리미터와 서킷브레이커가 여기 걸려 있다.</b> 기사 번역({@link GeminiTranslator})과
 * 검색어 번역({@link QueryTranslator})이 같은 무료 티어 할당량을 쓰므로 제한도 한 곳에서 공유해야
 * 한다. 각자 걸면 둘이 합쳐 한도를 넘길 수 있다.
 *
 * <p>Spring AI를 쓰지 않는다 — 2.0.0이 Boot 4.0.x에서 의존성을 끌어올리는 알려진 이슈가 있고,
 * 필요한 건 HTTP 호출 한 번뿐이다.
 *
 * <p>실패를 삼키지 않고 던진다. 폴백 판단은 호출자의 몫이다 — 기사 번역은 원문으로 강등하고
 * 검색어 번역은 원문 토큰으로 검색한다. 서로 다른 판단이라 여기서 정할 수 없다.
 */
@Component
public class GeminiApi {

    private final RestClient restClient;
    private final String model;

    public GeminiApi(RestClient.Builder builder,
                     @Value("${economy-helper.translation.gemini.base-url}") String baseUrl,
                     @Value("${economy-helper.translation.gemini.api-key:}") String apiKey,
                     @Value("${economy-helper.translation.gemini.model}") String model) {
        // 키를 쿼리 파라미터가 아니라 헤더로 보낸다 — URL은 로그·프록시에 그대로 남는다.
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("x-goog-api-key", apiKey).build();
        this.model = model;
    }

    /**
     * @return 모델이 낸 텍스트. JSON 모드로 요청하므로 호출자는 이걸 그대로 파싱하면 된다
     */
    @RateLimiter(name = "gemini")
    @CircuitBreaker(name = "translation")
    public String generate(String prompt) {
        GenerateContentResponse response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent", model)
                .body(GenerateContentRequest.of(prompt))
                .retrieve()
                .body(GenerateContentResponse.class);

        return extractText(response);
    }

    /** 응답 구조가 어긋나면 호출자가 폴백할 수 있도록 예외를 던진다. */
    private static String extractText(GenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new IllegalStateException("Gemini 응답에 candidates가 없습니다");
        }
        Candidate candidate = response.candidates().get(0);
        if (candidate == null || candidate.content() == null
                || candidate.content().parts() == null || candidate.content().parts().isEmpty()) {
            throw new IllegalStateException("Gemini 응답에 content.parts가 없습니다");
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini 응답 본문이 비어 있습니다");
        }
        return text;
    }

    // --- generateContent 스키마 (필요한 필드만) ---

    record GenerateContentRequest(List<Content> contents, GenerationConfig generationConfig) {

        static GenerateContentRequest of(String prompt) {
            return new GenerateContentRequest(
                    List.of(new Content(List.of(new Part(prompt)))),
                    // JSON 모드로 받아야 따옴표·머리말이 섞이지 않는다
                    new GenerationConfig("application/json"));
        }
    }

    record GenerationConfig(String responseMimeType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateContentResponse(List<Candidate> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content) {}
}
