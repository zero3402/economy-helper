package io.saiden.economyhelper.news.rank;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.ScoredArticle;
import io.saiden.economyhelper.news.ScoredArticle.ScoreBreakdown;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 기사 인기도 점수.
 *
 * <p>매체 어디도 조회수·댓글 수를 공개하지 않고 Most Read 페이지도 전부 403/404라
 * 공개 신호 네 가지를 합쳐 근사한다. 그중 {@code buzz}만이 실측(Hacker News)이고
 * 나머지 셋은 대용 지표다.
 *
 * <p>Spring에 기대지 않는 순수 계산이다 — 컨텍스트 없이 단위 테스트한다.
 * HN 조회 같은 I/O는 호출자가 미리 끝내 {@code buzzByLink}로 넘긴다.
 */
public class PopularityScorer {

    /** 이만큼 맞으면 keywordMatch 만점. 재테크 사전처럼 항목이 많아도 몇 개면 충분하다. */
    private static final int KEYWORD_SATURATION = 3;

    /**
     * 본문에만 걸렸을 때의 몫. 제목에 걸린 것의 절반으로 센다.
     *
     * <p>0으로 두면 제목에 단어가 없는 좋은 기사를 통째로 잃고, 1이면 예전 동작(둘을 구분하지
     * 않음)으로 돌아간다. 절반이면 <b>제목에 걸린 기사가 이기되 본문 매칭도 살아 있다.</b>
     */
    private static final double BODY_MATCH_WEIGHT = 0.5;

    /** HN 반응은 꼬리가 길다(1~900+). 로그로 눌러 상위 소수가 점수를 독식하지 않게 한다. */
    private static final double BUZZ_SATURATION = 300.0;

    private final RankingWeights weights;
    private final Duration recencyHalfLife;

    public PopularityScorer(RankingWeights weights, Duration recencyHalfLife) {
        if (weights == null) {
            throw new IllegalArgumentException("weights is required");
        }
        if (recencyHalfLife == null || recencyHalfLife.isZero() || recencyHalfLife.isNegative()) {
            throw new IllegalArgumentException("recencyHalfLife must be positive");
        }
        this.weights = weights;
        this.recencyHalfLife = recencyHalfLife;
    }

    /**
     * 점수가 높은 순으로 정렬해 돌려준다.
     *
     * @param buzzByLink 기사 링크 → HN {@code points + num_comments}. 없는 링크는 0으로 본다 —
     *                   HN 커버리지가 20~30%라 대부분은 여기 없고, 그래도 나머지 세 지표로
     *                   순위가 매겨진다.
     */
    public List<ScoredArticle> rank(List<Article> articles,
                                    Collection<KeywordGroup> keywords,
                                    Map<String, Integer> buzzByLink,
                                    Instant now) {
        return rankBy(articles, article -> keywordScore(article, keywords), buzzByLink, now);
    }

    /**
     * 관련도를 <b>밖에서 계산해</b> 넘기는 경로 — 정기 발송이 쓴다.
     *
     * <p>정기 발송에는 검색어가 없어 관련도의 근거가 필요한데, 그걸 무엇으로 재는지는
     * 이 클래스가 알 바가 아니다. 지금은 LLM이 매긴 "재테크 관련도"가 들어오고
     * (LLM이 죽으면 키워드 사전 매칭이 대신 들어온다) — 어느 쪽이든 여기는 그대로다.
     *
     * @param relevanceByLink 기사 링크 → 0~1. 없는 링크는 0으로 본다
     */
    public List<ScoredArticle> rankByRelevance(List<Article> articles,
                                               Map<String, Double> relevanceByLink,
                                               Map<String, Integer> buzzByLink,
                                               Instant now) {
        return rankBy(articles,
                article -> relevanceByLink == null ? 0.0 : relevanceByLink.getOrDefault(article.link(), 0.0),
                buzzByLink, now);
    }

    private List<ScoredArticle> rankBy(List<Article> articles,
                                       ToDoubleFunction<Article> relevance,
                                       Map<String, Integer> buzzByLink,
                                       Instant now) {
        int feedSize = normalizingFeedSize(articles);
        List<ScoredArticle> scored = new ArrayList<>(articles.size());
        for (Article article : articles) {
            int buzz = buzzByLink == null ? 0 : buzzByLink.getOrDefault(article.link(), 0);
            scored.add(score(article, feedSize, relevance.applyAsDouble(article), buzz, now));
        }
        scored.sort(Comparator.comparingDouble(ScoredArticle::score).reversed());
        return List.copyOf(scored);
    }

    /**
     * @param relevance 0~1. {@code ScoreBreakdown}에서는 여전히 {@code keywordMatch} 자리에 들어간다 —
     *                  가중치({@code keyword-match: 0.25})가 그 자리를 가리키기 때문이다
     */
    public ScoredArticle score(Article article,
                               int feedSize,
                               double relevance,
                               int buzzRaw,
                               Instant now) {
        ScoreBreakdown breakdown = new ScoreBreakdown(
                feedRankScore(article.feedRank(), feedSize),
                recencyScore(article.publishedAt(), now),
                clamp(relevance),
                buzzScore(buzzRaw));

        double weighted = breakdown.feedRank() * weights.feedRank()
                + breakdown.recency() * weights.recency()
                + breakdown.keywordMatch() * weights.keywordMatch()
                + breakdown.buzz() * weights.buzz();

        return new ScoredArticle(article, weighted / weights.sum(), breakdown);
    }

    /**
     * 정규화 분모는 <b>목록 길이가 아니라 등장한 최대 {@code feedRank}</b>다.
     *
     * <p>호출자는 걸러낸 목록을 넘길 수 있다 — 정기 발송의 재테크 키워드 필터, {@code /news} 검색이
     * 둘 다 그렇다. 그때 목록 길이로 나누면 {@code rank}가 길이를 넘겨 대부분이 0으로 clamp되고,
     * 가중치가 가장 큰 신호(0.35)가 통째로 사라진다. 12건 피드에서 3건만 남은 경우
     * 남은 셋이 전부 0점이 되는 식이다.
     */
    private static int normalizingFeedSize(List<Article> articles) {
        int max = 1;
        for (Article article : articles) {
            max = Math.max(max, article.feedRank() + 1);
        }
        return max;
    }

    /** 피드 맨 위가 1.0, 맨 아래가 0.0 — 편집자가 매긴 우선순위를 그대로 읽는다. */
    static double feedRankScore(int rank, int feedSize) {
        if (feedSize <= 1) {
            return 1.0;
        }
        return clamp(1.0 - ((double) rank / (feedSize - 1)));
    }

    /** 반감기 지수 감쇠. 피드 시각 오차로 미래가 찍힌 경우는 1.0으로 본다. */
    double recencyScore(Instant publishedAt, Instant now) {
        double ageSeconds = Duration.between(publishedAt, now).toSeconds();
        if (ageSeconds <= 0) {
            return 1.0;
        }
        return clamp(Math.pow(0.5, ageSeconds / recencyHalfLife.toSeconds()));
    }

    /**
     * 매칭된 <b>개념</b> 수 / {@code min(개념 수, 포화점)}.
     *
     * <p>분모를 이렇게 잡으면 검색어가 두어 개일 때는 "전부 맞아야 만점"이고,
     * 재테크 사전처럼 수십 개일 때는 "몇 개만 맞으면 만점"이 된다. 하나의 식으로
     * {@code /news} 검색과 정기 발송을 모두 감당한다.
     *
     * <p>세는 단위가 표현이 아니라 {@link KeywordGroup}인 게 중요하다. {@code 반도체}를
     * {@code [semiconductor, chip, chips]}로 확장했을 때 표현 단위로 세면 하나만 걸려도 0.33이 되어
     * 번역 확장이 오히려 점수를 깎는다. 묶음 단위로 세면 검색어 하나당 만점 1개로 유지된다.
     */
    static double keywordScore(Article article, Collection<KeywordGroup> keywords) {
        return keywordScore(article.title(), article.description(), keywords);
    }

    /**
     * <p><b>제목과 본문을 갈라 센다.</b> 제목에 있으면 그 기사가 그 주제를 <b>다루는</b> 것이고,
     * 본문에만 있으면 <b>언급한</b> 것이다. 한 덩어리로 세면 "환율 기사인데 금리를 한 줄 언급"한
     * 것이 {@code /news 금리}의 1위로 올라온다.
     */
    static double keywordScore(String title, String body, Collection<KeywordGroup> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0.0;
        }
        List<KeywordGroup> normalized = keywords.stream()
                .filter(group -> group != null && !group.isEmpty())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return 0.0;
        }

        String titleHay = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String bodyHay = body == null ? "" : body.toLowerCase(Locale.ROOT);
        double matched = normalized.stream()
                .mapToDouble(group -> group.matches(titleHay) ? 1.0
                        : group.matches(bodyHay) ? BODY_MATCH_WEIGHT : 0.0)
                .sum();
        int denominator = Math.min(normalized.size(), KEYWORD_SATURATION);
        return clamp(matched / denominator);
    }

    /** HN 반응을 로그 스케일로 0~1에 매핑한다. HN에 없는 기사는 0. */
    static double buzzScore(int raw) {
        if (raw <= 0) {
            return 0.0;
        }
        return clamp(Math.log1p(raw) / Math.log1p(BUZZ_SATURATION));
    }

    /** 0~1 밖으로 새는 값을 자른다. {@code RelevanceScorer}도 LLM 점수에 이걸 쓴다. */
    static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
