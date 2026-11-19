package io.saiden.economyhelper.translate.adapter.out.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.infrastructure.llm.GeminiApi;
import io.saiden.economyhelper.infrastructure.llm.LlmJson;
import io.saiden.economyhelper.translate.application.port.out.SearchTermTranslator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 한국어 검색어 토큰들을 영문 기사에서 찾을 때 쓸 영어 표현들로 옮긴다 — <b>토큰 여럿을 한 번에.</b>
 *
 * <p>필요한 이유: 매칭 대상이 영문 원문({@code title} + {@code description})이라
 * {@code /news 비트코인}이 아무것도 찾지 못했다. 사용자는 한국어로 치므로 검색어도 번역해야 한다.
 *
 * <p>정적 별칭 표를 두지 않는 이유는 {@code 엔비디아}·{@code 테슬라}·{@code 엔화}처럼
 * 목록에 미리 넣을 수 없는 고유명사가 끝없이 나오기 때문이다.
 *
 * <p><b>묶어 묻되 답은 토큰별로 받는다.</b> 검색어 전체를 한 문장으로 옮기면 결과를 원래 토큰에 되붙일 수 없어
 * 개념 경계가 무너진다. 그래서 답마다 <b>물은 토큰을 되돌려 받아</b>({@code source}) 그것으로 짝을 찾는다 —
 * 자리(순서)를 믿고 맞추면 모델이 순서를 바꾸거나 하나를 빠뜨렸을 때 짝이 밀리고, 어긋난 답이 토큰 캐시
 * ({@code SpringSearchTermCache}, 30일)에 굳으면 그 검색어가 남의 기사를 찾는다.
 */
@Component
public class QueryTranslator implements SearchTermTranslator {

    private static final Logger log = LoggerFactory.getLogger(QueryTranslator.class);

    /** 최대 개수를 제한하는 이유는 묶음이 커질수록 엉뚱한 기사가 걸릴 확률이 오르기 때문이다. */
    private static final int MAX_TERMS = 5;

    private static final String PROMPT = """
            다음 한국어 금융·경제 검색어들을 영문 경제 뉴스에서 찾을 때 쓸 영어 표현으로 옮기세요.

            규칙:
            - 뉴스 제목과 요약문에 실제로 쓰이는 표현만 넣으세요.
            - 표기 변형과 동의어를 함께 주세요. 예) 반도체 -> semiconductor, semiconductors, chip, chips
            - 고유명사는 영문 표기를 쓰세요. 예) 엔비디아 -> Nvidia, NVDA
            - **상위 개념이나 더 넓은 범주를 넣지 마세요.**
              예) 비트코인에 cryptocurrency나 crypto를 넣지 마세요. 그러면 비트코인과 무관한
              다른 코인 기사가 걸립니다.
            - 설명, 번역할 수 없다는 말, 원문 반복을 넣지 마세요.
            - 검색어마다 최대 %d개. **검색어끼리 표현을 섞지 마세요** — 각각 다른 개념입니다.
            - source에는 받은 검색어를 글자 그대로 되돌려 주세요.
            - 검색어 %d개 각각에 답을 하나씩 돌려주세요.
            - 다른 말 없이 JSON만 출력하세요: {"results": [{"source": "...", "terms": ["...", "..."]}, ...]}

            검색어:
            %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public QueryTranslator(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * 실패하면 예외를 던진다 — 원문 토큰으로 검색할지는 호출자가 정한다({@link GeminiTranslator}와 같은 규칙).
     *
     * @return 토큰 → 영어 표현들. 그 토큰을 되돌려 준 답이 없거나 둘 이상이거나 표현이 비었으면 <b>그 토큰만 빠진다</b>
     * @throws IllegalStateException 답 목록이 없거나 하나도 못 옮겼을 때
     */
    @Override
    public Map<String, List<String>> toEnglishTerms(List<String> tokens) {
        if (tokens.isEmpty()) {
            return Map.of();
        }
        Batch parsed = LlmJson.read(objectMapper,
                api.generate(PROMPT.formatted(MAX_TERMS, tokens.size(), numbered(tokens))), Batch.class);

        if (parsed == null || parsed.results() == null) {
            throw new IllegalStateException("검색어 번역 응답에 results가 없습니다: " + tokens);
        }
        Map<String, List<String>> byToken = new LinkedHashMap<>();
        for (String token : tokens) {
            List<EnglishTerms> answers = parsed.results().stream()
                    .filter(answer -> answer != null && answer.isFor(token))
                    .toList();
            // 둘 이상이면 어느 것인지 모른다 — 고르지 않는다
            if (answers.size() != 1) {
                log.warn("[translate] 검색어 번역에 물은 검색어의 답이 {}개입니다 — 그 검색어는 원문으로 둡니다", answers.size());
                continue;
            }
            List<String> terms = answers.getFirst().cleaned();
            if (!terms.isEmpty()) {
                byToken.put(token, terms);
            }
        }
        if (byToken.isEmpty()) {
            throw new IllegalStateException("검색어 번역 결과가 비어 있습니다: " + tokens);
        }
        return byToken;
    }

    /** 번호를 매겨 넘긴다 — 모델이 하나도 빠뜨리지 않게 하는 가장 값싼 장치다. */
    private static String numbered(List<String> tokens) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            lines.append(i + 1).append(". ").append(tokens.get(i)).append('\n');
        }
        return lines.toString().stripTrailing();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Batch(List<EnglishTerms> results) {}

    /**
     * @param source 모델이 되돌려 준 검색어 — 이 답이 어느 토큰의 것인지
     * @param terms  영어 표현들
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnglishTerms(String source, List<String> terms) {

        /** 대소문자와 앞뒤 공백만 봐준다 — 그 밖에 다르면 다른 검색어의 답이다. */
        boolean isFor(String token) {
            return source != null && source.strip().equalsIgnoreCase(token);
        }

        /** 빈 것·겹친 것을 걷고 {@link #MAX_TERMS}개로 자른다. */
        List<String> cleaned() {
            if (terms == null) {
                return List.of();
            }
            return terms.stream()
                    .filter(term -> term != null && !term.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(MAX_TERMS)
                    .toList();
        }
    }
}
