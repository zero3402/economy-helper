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
        int feedSize = normalizingFeedSize(articles);
        List<ScoredArticle> scored = new ArrayList<>(articles.size());
        for (Article article : articles) {
            int buzz = buzzByLink == null ? 0 : buzzByLink.getOrDefault(article.link(), 0);
            scored.add(score(article, feedSize, keywords, buzz, now));
        }
        scored.sort(Comparator.comparingDouble(ScoredArticle::score).reversed());
        return List.copyOf(scored);
    }

    public ScoredArticle score(Article article,
                               int feedSize,
                               Collection<KeywordGroup> keywords,
                               int buzzRaw,
                               Instant now) {
        ScoreBreakdown breakdown = new ScoreBreakdown(
                feedRankScore(article.feedRank(), feedSize),
                recencyScore(article.publishedAt(), now),
                keywordScore(article.text(), keywords),
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
    static double keywordScore(String text, Collection<KeywordGroup> keywords) {
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

        String haystack = text.toLowerCase(Locale.ROOT);
        long matched = normalized.stream().filter(group -> group.matches(haystack)).count();
        int denominator = Math.min(normalized.size(), KEYWORD_SATURATION);
        return clamp((double) matched / denominator);
    }

    /** HN 반응을 로그 스케일로 0~1에 매핑한다. HN에 없는 기사는 0. */
    static double buzzScore(int raw) {
        if (raw <= 0) {
            return 0.0;
        }
        return clamp(Math.log1p(raw) / Math.log1p(BUZZ_SATURATION));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
