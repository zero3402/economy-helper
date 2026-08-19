package io.saiden.economyhelper.news.rank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.llm.GeminiApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * "이 기사가 재테크에 쓸모 있는가"를 0~1로 매긴다 — 정기 발송 랭킹의 의미 신호다.
 *
 * <p><b>왜 필요한가.</b> 랭킹 네 항 중 {@code feedRank}·{@code recency}·{@code buzz}는
 * 주제를 전혀 모른다(각각 RSS 노출 순서, 최신성, HN 반응이다). buzz는 커버리지가 매체당
 * 하루 ~4건이라 대부분 0점이기까지 하다. <b>기사가 무엇에 대한 것인지 아는 항은 이것뿐이다.</b>
 * 단어 목록으로는 대신할 수 없다 — 스테이블코인·AI 설비투자처럼 주제는 계속 새로 생기고,
 * 부분 문자열 매칭은 오탐을 낸다.
 *
 * <p><b>비용을 어떻게 통제하는가.</b> 기사 전체(최대 ~200건)를 하나씩 물으면 무료 티어를 태운다.
 * 호출자가 <b>로컬 점수로 후보를 좁혀</b> 넘기고, 여기서 <b>한 번에 묶어</b> 묻는다 —
 * 매체당 1회이고 정기 발송이 하루 한 번이라 이걸로 충분하다.
 * {@code /crypto}에서 후보를 좁혀 거래대금으로 가른 것과 같은 발상이다.
 *
 * <p><b>실패하면 전부 통과시킨다</b>({@link #passAll}). 후보 자체가 이미 금융 섹션 기사라
 * 걸러내지 않아도 엉뚱한 기사가 1위가 되지는 않는다.
 */
@Component
public class RelevanceScorer {

    private static final Logger log = LoggerFactory.getLogger(RelevanceScorer.class);

    private static final String PROMPT = """
            아래는 경제 뉴스 매체의 기사 제목 목록입니다.
            각 제목이 **재테크(투자·자산관리)에 실질적으로 도움이 되는 뉴스**인지 0.0~1.0으로 매기세요.

            기준:
            - 0.8~1.0: 금리·환율·주가·채권·원자재·암호화폐·기업 실적·통화정책·인플레이션처럼
                       투자 판단에 바로 쓰이는 내용
            - 0.4~0.7: 거시경제 동향, 산업 전망, 규제 변화처럼 간접적으로 영향을 주는 내용
            - 0.0~0.3: 정치·사회·스포츠·문화, 또는 투자 판단과 무관한 기업 소식

            규칙:
            - **입력 순서 그대로, 개수를 정확히 %d개** 돌려주세요.
            - 설명 없이 JSON만: {"scores": [0.9, 0.2, ...]}

            제목:
            %s
            """;

    /**
     * 검색어와의 관련도.
     *
     * <p>재테크 관련도와 <b>묻는 것이 다르다.</b> 여기서는 주제가 재테크인지가 아니라
     * "사용자가 찾는 그것을 <b>다루는</b> 기사인지"를 본다 — 한 줄 언급과 그 주제의 기사는
     * 사용자에게 전혀 다른 답이다.
     */
    private static final String SEARCH_PROMPT = """
            사용자가 '%s'에 대한 뉴스를 찾고 있습니다.
            아래 기사 제목들이 그 주제를 **실제로 다루는** 기사인지 0.0~1.0으로 매기세요.

            기준:
            - 0.8~1.0: 그 주제가 기사의 핵심이다
            - 0.4~0.7: 그 주제를 비중 있게 다루지만 핵심은 아니다
            - 0.0~0.3: 스쳐 지나가듯 언급했거나 무관하다. 같은 단어가 다른 뜻으로 쓰인 경우도 여기다

            규칙:
            - 검색어가 한국어여도 영문 기사에서 같은 개념을 가리키면 관련 있는 것입니다.
            - **입력 순서 그대로, 개수를 정확히 %d개** 돌려주세요.
            - 설명 없이 JSON만: {"scores": [0.9, 0.2, ...]}

            제목:
            %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public RelevanceScorer(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * 후보들의 관련도를 한 번에 매긴다.
     *
     * <p>배치 전체를 캐시한다 — 같은 기사 묶음이면 같은 키다. 피드 캐시가 10분이라
     * 그 안에 발송을 다시 트리거해도 Gemini를 또 부르지 않는다. 기사별로 캐시하면
     * 배치가 쪼개져 호출 수가 늘어난다.
     *
     * @return 기사 링크 → 0~1. 실패해도 예외를 던지지 않는다 — 발송이 멈추면 안 된다
     */
    @Cacheable(cacheNames = "relevance", key = "#candidates.![link]", unless = "#result.isEmpty()")
    public Map<String, Double> scoreAll(List<Article> candidates) {
        return score(candidates, size -> PROMPT.formatted(size, titlesOf(candidates)),
                "재테크 관련도");
    }

    /**
     * 후보들이 <b>검색어와</b> 관련 있는지를 한 번에 매긴다 — {@code /news {검색어}}용.
     *
     * <p><b>왜 문자열 매칭만으로는 부족한가.</b> {@code /news 금리}는 본문에 {@code rate}가
     * 한 번 스친 기사도 통과시킨다. 그러면 "환율 기사인데 금리를 한 줄 언급"한 것이 1위가 되고,
     * 사용자는 검색이 엉뚱한 답을 준다고 느낀다. 매칭은 후보를 좁히는 데까지만 쓰고,
     * <b>정말 그 주제의 기사인지는 의미를 아는 쪽</b>이 판단해야 한다.
     *
     * <p>비용은 {@link #scoreAll(List)}과 같다 — 상위 후보 몇 건을 <b>한 번에 묶어</b> 묻고,
     * 검색어와 후보 목록이 같으면 캐시가 받는다.
     */
    @Cacheable(cacheNames = "relevance", key = "#query + '|' + #candidates.![link]",
            unless = "#result.isEmpty()")
    public Map<String, Double> scoreAll(List<Article> candidates, String query) {
        return score(candidates, size -> SEARCH_PROMPT.formatted(query, size, titlesOf(candidates)),
                "'" + query + "' 관련도");
    }

    private Map<String, Double> score(List<Article> candidates,
                                      java.util.function.IntFunction<String> prompt, String what) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        try {
            return byLlm(candidates, prompt.apply(candidates.size()));
        } catch (Exception e) {
            log.error("[relevance] {} LLM 채점 실패 — 전부 통과시킵니다: {}", what, e.toString());
            return passAll(candidates);
        }
    }

    private static String titlesOf(List<Article> candidates) {
        return IntStream.range(0, candidates.size())
                .mapToObj(i -> (i + 1) + ". " + candidates.get(i).title())
                .collect(Collectors.joining("\n"));
    }

    private Map<String, Double> byLlm(List<Article> candidates, String prompt) {
        Scores parsed = objectMapper.readValue(api.generate(prompt), Scores.class);

        if (parsed == null || parsed.scores() == null || parsed.scores().size() != candidates.size()) {
            // 개수가 어긋나면 어느 점수가 어느 기사인지 알 수 없다. 짝을 잘못 맞추느니 폴백이 낫다
            throw new IllegalStateException("관련도 응답 개수가 맞지 않습니다: 기대 "
                    + candidates.size() + ", 실제 "
                    + (parsed == null || parsed.scores() == null ? "없음" : parsed.scores().size()));
        }

        Map<String, Double> byLink = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Double score = parsed.scores().get(i);
            byLink.put(candidates.get(i).link(), score == null ? 0.0 : PopularityScorer.clamp(score));
        }
        return byLink;
    }

    /**
     * 폴백 — <b>전부 통과시킨다.</b>
     *
     * <p>키워드 사전으로 내려가지 않는다. <b>피드를 전부 금융 섹션으로 좁혀 뒀기 때문</b>이다
     * (Yahoo Finance · Investing.com · CNBC markets · BBC business · AP business) —
     * 후보 자체가 이미 재테크 기사라 "EU 국경 검사로 공항 대기줄" 같은 기사가 섞이지 않는다.
     * 하류에서 단어로 거르는 것보다 상류에서 소스를 좁히는 편이 근본적이고, 손으로 관리할
     * 목록도 남지 않는다.
     */
    private static Map<String, Double> passAll(List<Article> candidates) {
        Map<String, Double> byLink = new HashMap<>();
        for (Article article : candidates) {
            byLink.put(article.link(), 1.0);
        }
        return byLink;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Scores(List<Double> scores) {}
}
