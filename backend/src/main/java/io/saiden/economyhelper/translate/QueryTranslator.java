package io.saiden.economyhelper.translate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 한국어 검색어 토큰을 영문 기사에서 찾을 때 쓸 영어 표현들로 옮긴다.
 *
 * <p>필요한 이유: 매칭 대상이 영문 원문({@code title} + {@code description})이라
 * {@code /news 비트코인}이 아무것도 찾지 못했다. 사용자는 한국어로 치므로 검색어도 번역해야 한다.
 *
 * <p>정적 별칭 표를 두지 않는 이유는 {@code 엔비디아}·{@code 테슬라}·{@code 엔화}처럼
 * 목록에 미리 넣을 수 없는 고유명사가 끝없이 나오기 때문이다.
 *
 * <p><b>토큰 단위로 캐시한다.</b> {@code 금리}·{@code 환율} 같은 토큰은 여러 검색어에 걸쳐
 * 반복되므로 적중률이 높고, 대응 관계 자체가 낡지 않아 TTL을 길게 잡는다.
 */
@Component
public class QueryTranslator {

    /** 최대 개수를 제한하는 이유는 묶음이 커질수록 엉뚱한 기사가 걸릴 확률이 오르기 때문이다. */
    private static final int MAX_TERMS = 5;

    private static final String PROMPT = """
            다음 한국어 금융·경제 검색어를 영문 경제 뉴스에서 찾을 때 쓸 영어 표현으로 옮기세요.

            규칙:
            - 뉴스 제목과 요약문에 실제로 쓰이는 표현만 넣으세요.
            - 표기 변형과 동의어를 함께 주세요. 예) 반도체 -> semiconductor, semiconductors, chip, chips
            - 고유명사는 영문 표기를 쓰세요. 예) 엔비디아 -> Nvidia, NVDA
            - **상위 개념이나 더 넓은 범주를 넣지 마세요.**
              예) 비트코인에 cryptocurrency나 crypto를 넣지 마세요. 그러면 비트코인과 무관한
              다른 코인 기사가 걸립니다.
            - 설명, 번역할 수 없다는 말, 원문 반복을 넣지 마세요.
            - 최대 %d개.
            - 다른 말 없이 JSON만 출력하세요: {"terms": ["...", "..."]}

            검색어: %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public QueryTranslator(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * 실패하면 예외를 던진다 — 원문 토큰으로 검색할지는 호출자가 정한다
     * ({@link GeminiTranslator}와 같은 규칙).
     *
     * @return 영어 표현들. 빈 결과는 캐시하지 않는다 — 일시적 실패를 30일간 굳히면
     *         그 검색어는 그 기간 내내 안 걸린다
     */
    @Cacheable(cacheNames = "query", key = "#token", unless = "#result.isEmpty()")
    public List<String> toEnglishTerms(String token) {
        EnglishTerms parsed = objectMapper.readValue(
                api.generate(PROMPT.formatted(MAX_TERMS, token)), EnglishTerms.class);

        if (parsed == null || parsed.terms() == null) {
            throw new IllegalStateException("검색어 번역 응답에 terms가 없습니다: " + token);
        }
        List<String> terms = parsed.terms().stream()
                .filter(term -> term != null && !term.isBlank())
                .map(String::trim)
                .distinct()
                .limit(MAX_TERMS)
                .toList();
        if (terms.isEmpty()) {
            throw new IllegalStateException("검색어 번역 결과가 비어 있습니다: " + token);
        }
        return terms;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnglishTerms(List<String> terms) {}
}
