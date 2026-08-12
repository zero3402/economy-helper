package io.saiden.economyhelper.news.rank;

/**
 * 인기도 점수 가중치. {@code application.yml}의 {@code economy-helper.ranking.weights}와 대응한다.
 *
 * <p>합이 1일 필요는 없다 — {@link PopularityScorer}가 합으로 나눠 정규화하므로
 * 비율만 맞으면 된다.
 */
public record RankingWeights(double feedRank, double recency, double keywordMatch, double buzz) {

    public RankingWeights {
        if (feedRank < 0 || recency < 0 || keywordMatch < 0 || buzz < 0) {
            throw new IllegalArgumentException("가중치는 0 이상이어야 합니다");
        }
        if (feedRank + recency + keywordMatch + buzz <= 0) {
            throw new IllegalArgumentException("가중치가 전부 0일 수는 없습니다");
        }
    }

    public double sum() {
        return feedRank + recency + keywordMatch + buzz;
    }

    /** application.yml 기본값과 같아야 한다. */
    public static RankingWeights defaults() {
        return new RankingWeights(0.35, 0.25, 0.25, 0.15);
    }
}
