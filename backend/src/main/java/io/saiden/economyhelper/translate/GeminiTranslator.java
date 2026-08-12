package io.saiden.economyhelper.translate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.news.Article;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 기사 번역 — 프롬프트와 파싱만 담당한다.
 *
 * <p>HTTP 호출과 레이트리미터·서킷브레이커는 {@link GeminiApi}에 있다.
 * 실패를 삼키지 않고 던지는 이유는 폴백 판단을 {@link TranslationService}가 하도록 하기 위해서다.
 */
@Component
public class GeminiTranslator implements Translator {

    /**
     * 원문에 없는 사실을 못 넣게 못박는다. 근거가 제목 + 1~2문장뿐이라 여지를 주면 지어낸다.
     *
     * <p>방향 규칙과 문체 규칙은 8단계 실측에서 나왔다. 그전 프롬프트로는
     * {@code "government bonds edged lower"}(채권 <b>가격</b> 하락 = 금리 상승)를
     * <i>"국채 금리는 하락했습니다"</i>로 뒤집었다 — 재테크 판단을 정반대로 이끄는 오역이라
     * 문장이 매끄러운 것보다 이쪽이 중요하다. 문체도 섞여 나와("~했습니다"/"~했다") 못박았다.
     */
    private static final String PROMPT = """
            다음 영문 경제 뉴스를 한국어로 번역하세요.

            규칙:
            - 원문에 없는 사실, 배경, 해설을 절대 추가하지 마세요.
            - 요약하거나 줄이지 말고 원문의 내용을 그대로 옮기세요.
            - 뉴스 기사체(~다)로 쓰세요. 존댓말을 쓰지 마세요.
            - 가격·금리·수익률이 오르내리는 방향을 원문 그대로 유지하세요.
              채권 가격과 금리는 서로 반대 방향입니다.
            - 경제·금융 용어는 한국 언론에서 통용되는 표현을 쓰세요.
            - 요약문이 비어 있으면 body도 빈 문자열로 두세요.
            - 다른 말 없이 JSON만 출력하세요: {"title": "...", "body": "..."}

            제목: %s
            요약문: %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public GeminiTranslator(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    @Override
    public Translation translate(Article article) {
        String prompt = PROMPT.formatted(
                article.title(),
                article.description() == null ? "" : article.description());

        return parse(api.generate(prompt));
    }

    private Translation parse(String json) {
        TranslatedText parsed = objectMapper.readValue(json, TranslatedText.class);
        if (parsed == null || parsed.title() == null || parsed.title().isBlank()) {
            throw new IllegalStateException("번역 결과에 title이 없습니다");
        }
        return Translation.of(parsed.title().trim(), parsed.body() == null ? "" : parsed.body().trim());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranslatedText(String title, String body) {}
}
