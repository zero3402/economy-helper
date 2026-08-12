package io.saiden.economyhelper.news.rank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.translate.GeminiApi;
import java.util.Collection;
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
 * 주제를 전혀 모른다(각각 RSS 노출 순서, 최신성, HN 반응이다). 게다가 buzz는 커버리지가
 * 매체당 하루 ~4건이고 CoinDesk는 사실상 0이라 대부분 0점이다. 의미를 아는 항이 하나는
 * 있어야 하는데, 그 자리를 손으로 쓴 영어 단어 목록이 채우고 있었다 —
 * 스테이블코인·AI 설비투자처럼 새로 생기는 주제를 못 잡고 부분 문자열 매칭이라 오탐도 났다.
 *
 * <p><b>비용을 어떻게 통제하는가.</b> 기사 전체(최대 ~200건)를 하나씩 물으면 무료 티어를 태운다.
 * 호출자가 <b>로컬 점수로 후보를 좁혀</b> 넘기고, 여기서 <b>한 번에 묶어</b> 묻는다 —
 * 매체당 1회, 하루 5회다. 정기 발송이 하루 한 번이라 이걸로 충분하다.
 * {@code /crypto}에서 후보를 좁혀 거래대금으로 가른 것과 같은 발상이다.
 *
 * <p><b>실패하면 키워드 사전으로 내려간다.</b> 이 신호가 통째로 사라지면 일반 뉴스가 1위로
 * 뽑히므로(Phase 1에서 실제로 겪었다) 폴백이 필수다. {@code TranslationService}가 번역 실패 시
 * 원문으로 강등하는 것과 같은 구조다.
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
     * @param fallbackKeywords Gemini가 실패했을 때 쓸 재테크 키워드 사전
     * @return 기사 링크 → 0~1. 실패해도 예외를 던지지 않는다 — 발송이 멈추면 안 된다
     */
    @Cacheable(cacheNames = "relevance", key = "#candidates.![link]", unless = "#result.isEmpty()")
    public Map<String, Double> scoreAll(List<Article> candidates,
                                        Collection<KeywordGroup> fallbackKeywords) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        try {
            return byLlm(candidates);
        } catch (Exception e) {
            log.error("[relevance] LLM 채점 실패 — 키워드 사전으로 내려갑니다: {}", e.toString());
            return byKeywords(candidates, fallbackKeywords);
        }
    }

    private Map<String, Double> byLlm(List<Article> candidates) {
        String titles = IntStream.range(0, candidates.size())
                .mapToObj(i -> (i + 1) + ". " + candidates.get(i).title())
                .collect(Collectors.joining("\n"));

        Scores parsed = objectMapper.readValue(
                api.generate(PROMPT.formatted(candidates.size(), titles)), Scores.class);

        if (parsed == null || parsed.scores() == null || parsed.scores().size() != candidates.size()) {
            // 개수가 어긋나면 어느 점수가 어느 기사인지 알 수 없다. 짝을 잘못 맞추느니 폴백이 낫다
            throw new IllegalStateException("관련도 응답 개수가 맞지 않습니다: 기대 "
                    + candidates.size() + ", 실제 "
                    + (parsed == null || parsed.scores() == null ? "없음" : parsed.scores().size()));
        }

        Map<String, Double> byLink = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Double score = parsed.scores().get(i);
            byLink.put(candidates.get(i).link(), score == null ? 0.0 : clamp(score));
        }
        return byLink;
    }

    /**
     * 폴백 — 예전 방식 그대로 키워드 매칭 점수를 쓴다.
     *
     * <p><b>사전까지 비어 있으면 전부 1.0을 준다.</b> 걸러낼 근거가 하나도 없는데 0을 주면
     * 임계값 필터가 모든 매체를 비워 발송 자체가 사라진다 — LLM도 죽고 사전도 없다는 이유로
     * 아침 브리핑이 통째로 안 나가는 건 과하다. 예전 필터도 같은 판단이었다
     * ("사전이 비어 있으면 걸러낼 근거가 없으므로 전부 통과시킨다").
     */
    private static Map<String, Double> byKeywords(List<Article> candidates,
                                                  Collection<KeywordGroup> keywords) {
        boolean noBasis = keywords == null || keywords.isEmpty();
        Map<String, Double> byLink = new HashMap<>();
        for (Article article : candidates) {
            byLink.put(article.link(),
                    noBasis ? 1.0 : PopularityScorer.keywordScore(article.text(), keywords));
        }
        return byLink;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Scores(List<Double> scores) {}
}
