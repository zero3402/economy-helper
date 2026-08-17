package io.saiden.economyhelper.translate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.news.Article;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 기사 번역 — 프롬프트와 파싱만 담당한다.
 *
 * <p>HTTP 호출과 레이트리미터·서킷브레이커는 {@link GeminiApi}에 있다.
 * 실패를 삼키지 않고 던지는 이유는 폴백 판단을 {@link TranslationService}가 하도록 하기 위해서다.
 */
@Component
public class GeminiTranslator {

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
            - 경제·금융 용어는 한국 언론에서 통용되는 표현을 쓰세요.
            - 요약문이 비어 있으면 body도 빈 문자열로 두세요.
            - 다른 말 없이 JSON만 출력하세요: {"title": "...", "body": "..."}

            제목: %s
            요약문: %s
            """;

    /**
     * 여러 건을 한 번에 옮기는 프롬프트. 규칙은 {@link #PROMPT}와 <b>한 글자도 다르지 않아야
     * 한다</b> — 갈리면 같은 기사가 묶였는지 아닌지에 따라 다른 문체로 나온다.
     */
    private static final String BATCH_PROMPT = """
            다음 영문 경제 뉴스 %d건을 각각 한국어로 번역하세요.

            규칙:
            - 원문에 없는 사실, 배경, 해설을 절대 추가하지 마세요.
            - 요약하거나 줄이지 말고 원문의 내용을 그대로 옮기세요.
            - 뉴스 기사체(~다)로 쓰세요. 존댓말을 쓰지 마세요.
            - 가격·금리·수익률이 오르내리는 방향을 원문 그대로 유지하세요.
            - 경제·금융 용어는 한국 언론에서 통용되는 표현을 쓰세요.
            - 요약문이 비어 있으면 body도 빈 문자열로 두세요.
            - **입력 순서 그대로, 개수를 정확히 %<d개** 돌려주세요.
            - 다른 말 없이 JSON만: {"articles": [{"title": "...", "body": "..."}, ...]}

            %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public GeminiTranslator(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    public Translation translate(Article article) {
        String prompt = PROMPT.formatted(
                article.title(),
                article.description() == null ? "" : article.description());

        return parse(api.generate(prompt));
    }

    /**
     * 여러 건을 <b>Gemini 한 번</b>으로 번역한다.
     *
     * <p><b>왜 묶는가.</b> 건별로 부르면 브리핑 한 번에 번역만 세 번이고, 관련도 채점(매체당 1회)
     * 뒤에 오므로 <b>리미터가 소진됐을 때 잘려 나가는 쪽이 정확히 번역</b>이다. 실제로
     * "번역이 일시적으로 불가"가 자주 뜨던 원인이 이것이다. {@code RelevanceScorer}가 후보를
     * 한 번에 묶어 채점하는 것과 같은 처방이다.
     *
     * <p><b>개수가 어긋나면 통째로 실패시킨다.</b> 짝이 밀리면 A 기사에 B 번역이 붙는데,
     * 그건 번역이 없는 것보다 훨씬 나쁘다 — 조용히 틀린 내용이 나간다.
     *
     * @return 입력과 같은 순서, 같은 개수
     */
    public List<Translation> translateAll(List<Article> articles) {
        if (articles.isEmpty()) {
            return List.of();
        }
        // 한 건이면 묶을 것이 없다. 배치 프롬프트는 지시가 길어 한 건에는 오히려 손해다
        if (articles.size() == 1) {
            return List.of(translate(articles.get(0)));
        }
        TranslatedBatch parsed = objectMapper.readValue(
                api.generate(BATCH_PROMPT.formatted(articles.size(), sourceOf(articles))),
                TranslatedBatch.class);

        if (parsed == null || parsed.articles() == null
                || parsed.articles().size() != articles.size()) {
            throw new IllegalStateException("번역 결과 개수가 맞지 않습니다: 기대 " + articles.size()
                    + ", 실제 " + (parsed == null || parsed.articles() == null
                            ? "없음" : parsed.articles().size()));
        }

        List<Translation> translations = new ArrayList<>(articles.size());
        for (TranslatedText text : parsed.articles()) {
            if (text == null || text.title() == null || text.title().isBlank()) {
                throw new IllegalStateException("번역 결과에 title이 없습니다");
            }
            translations.add(Translation.of(text.title().trim(),
                    text.body() == null ? "" : text.body().trim()));
        }
        return List.copyOf(translations);
    }

    /** 번호를 매겨 넘긴다 — 모델이 순서를 지키게 하는 가장 값싼 장치다. */
    private static String sourceOf(List<Article> articles) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < articles.size(); i++) {
            Article article = articles.get(i);
            source.append(i + 1).append(". 제목: ").append(article.title()).append('\n')
                    .append("   요약문: ")
                    .append(article.description() == null ? "" : article.description())
                    .append('\n');
        }
        return source.toString();
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranslatedBatch(List<TranslatedText> articles) {}
}
