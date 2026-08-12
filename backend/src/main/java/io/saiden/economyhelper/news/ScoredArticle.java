package io.saiden.economyhelper.news;

/**
 * 점수가 매겨진 기사.
 *
 * <p>{@link ScoreBreakdown}을 함께 들고 다닌다 — 왜 이 기사가 1위인지 로그와 테스트에서
 * 설명할 수 있어야 가중치를 근거 있게 조정할 수 있다.
 *
 * @param score 0~1. 가중합을 가중치 합으로 나눈 값이라 가중치를 바꿔도 척도가 유지된다.
 */
public record ScoredArticle(Article article, double score, ScoreBreakdown breakdown) {

    /** 지표별 정규화 값(각 0~1). 가중치를 곱하기 전 값이다. */
    public record ScoreBreakdown(double feedRank, double recency, double keywordMatch, double buzz) {}
}
