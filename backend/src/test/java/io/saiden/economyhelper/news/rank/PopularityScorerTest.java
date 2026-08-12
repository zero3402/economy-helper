package io.saiden.economyhelper.news.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.news.ScoredArticle;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Spring 컨텍스트 없이 도는 순수 계산 테스트다. */
class PopularityScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final Duration HALF_LIFE = Duration.ofHours(6);

    private final PopularityScorer scorer =
            new PopularityScorer(RankingWeights.defaults(), HALF_LIFE);

    @Nested
    @DisplayName("feedRank — 편집자가 매긴 우선순위")
    class FeedRank {

        @Test
        @DisplayName("맨 위는 1.0, 맨 아래는 0.0")
        void topIsOneBottomIsZero() {
            assertThat(PopularityScorer.feedRankScore(0, 20)).isEqualTo(1.0);
            assertThat(PopularityScorer.feedRankScore(19, 20)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("항목이 하나뿐이면 0으로 나누지 않고 1.0")
        void singleItemDoesNotDivideByZero() {
            assertThat(PopularityScorer.feedRankScore(0, 1)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("걸러낸 목록을 받아도 순위 차이가 남는다 — 목록 길이로 나누면 전부 0이 된다")
        void keepsDiscriminationWhenListWasFiltered() {
            // 12건 피드에서 재테크 키워드로 3건만 남은 상황. 목록 길이(3)로 나누면
            // rank 2·7·11이 모두 clamp돼 0점이 되고 편집자 우선순위 신호가 사라진다.
            List<ScoredArticle> ranked = scorer.rank(
                    List.of(article("위", 2, NOW), article("가운데", 7, NOW), article("아래", 11, NOW)),
                    List.of(), Map.of(), NOW);

            assertThat(ranked).extracting(s -> s.article().title())
                    .containsExactly("위", "가운데", "아래");
            assertThat(ranked.get(0).breakdown().feedRank()).isGreaterThan(0.0);
            assertThat(ranked.get(1).breakdown().feedRank())
                    .isGreaterThan(0.0)
                    .isLessThan(ranked.get(0).breakdown().feedRank());
        }
    }

    @Nested
    @DisplayName("recency — 반감기 감쇠")
    class Recency {

        @Test
        @DisplayName("반감기만큼 지나면 정확히 0.5")
        void halfLifeGivesHalf() {
            double score = scorer.recencyScore(NOW.minus(HALF_LIFE), NOW);

            assertThat(score).isCloseTo(0.5, within(1e-9));
        }

        @Test
        @DisplayName("방금 나온 기사는 1.0, 오래될수록 0에 수렴")
        void decaysWithAge() {
            assertThat(scorer.recencyScore(NOW, NOW)).isEqualTo(1.0);
            assertThat(scorer.recencyScore(NOW.minus(Duration.ofDays(7)), NOW))
                    .isLessThan(0.001);
        }

        @Test
        @DisplayName("피드 시각 오차로 미래가 찍혀도 1.0을 넘지 않는다")
        void futureTimestampClampsToOne() {
            assertThat(scorer.recencyScore(NOW.plus(Duration.ofHours(3)), NOW)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("keywordMatch — 검색어와 사전을 한 식으로 감당한다")
    class KeywordMatch {

        @Test
        @DisplayName("검색어가 둘이면 전부 맞아야 만점")
        void fewKeywordsRequireAllToMatch() {
            assertThat(PopularityScorer.keywordScore("Fed raises rates", groups("fed", "rates")))
                    .isEqualTo(1.0);
            assertThat(PopularityScorer.keywordScore("Fed holds steady", groups("fed", "rates")))
                    .isCloseTo(0.5, within(1e-9));
        }

        @Test
        @DisplayName("사전이 크면 세 개만 맞아도 만점 — 분모가 포화점에서 멈춘다")
        void largeDictionarySaturates() {
            List<KeywordGroup> dictionary =
                    groups("금리", "환율", "물가", "증시", "채권", "부동산", "연준", "실적");

            double score = PopularityScorer.keywordScore("금리 인상에 환율과 물가가 출렁", dictionary);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("한 개념의 별칭 하나만 걸려도 만점 — 번역 확장이 점수를 깎지 않는다")
        void aliasGroupCountsAsOneConcept() {
            // 검색어 '반도체'가 세 표현으로 확장된 상황. 표현 단위로 셌다면 1/3 = 0.33이 된다
            KeywordGroup semiconductor = KeywordGroup.of("반도체", "semiconductor", "chip", "chips");

            assertThat(PopularityScorer.keywordScore("Nvidia chip demand surges",
                    List.of(semiconductor)))
                    .as("번역해서 표현을 늘렸다고 관련도가 떨어질 이유가 없다")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("개념이 둘이면 분모도 둘 — 별칭이 몇 개든 분모는 개념 수다")
        void denominatorCountsConceptsNotTerms() {
            KeywordGroup bitcoin = KeywordGroup.of("비트코인", "bitcoin", "btc");
            KeywordGroup rate = KeywordGroup.of("금리", "interest rate", "rates");

            assertThat(PopularityScorer.keywordScore("Bitcoin slips", List.of(bitcoin, rate)))
                    .isCloseTo(0.5, within(1e-9));
            assertThat(PopularityScorer.keywordScore("Bitcoin slips as rates rise",
                    List.of(bitcoin, rate)))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("대소문자를 가리지 않는다")
        void isCaseInsensitive() {
            assertThat(PopularityScorer.keywordScore("BITCOIN surges", groups("bitcoin")))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("키워드가 없으면 이 항은 0 — 정기 발송에서 검색어가 없을 때다")
        void emptyKeywordsContributeNothing() {
            assertThat(PopularityScorer.keywordScore("anything", List.of())).isZero();
            assertThat(PopularityScorer.keywordScore("anything", null)).isZero();
            assertThat(PopularityScorer.keywordScore("anything", List.of(KeywordGroup.of())))
                    .as("빈 묶음만 있으면 걸러낼 근거가 없다")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("buzz — HN 실측을 로그로 누른다")
    class Buzz {

        @Test
        @DisplayName("HN에 없는 기사는 0")
        void absentFromHackerNewsScoresZero() {
            assertThat(PopularityScorer.buzzScore(0)).isZero();
            assertThat(PopularityScorer.buzzScore(-1)).isZero();
        }

        @Test
        @DisplayName("단조 증가하되 상위 소수가 독식하지 못하게 로그로 눌린다")
        void growsMonotonicallyButSublinearly() {
            double small = PopularityScorer.buzzScore(10);
            double medium = PopularityScorer.buzzScore(100);
            double huge = PopularityScorer.buzzScore(930);

            assertThat(small).isLessThan(medium);
            assertThat(medium).isLessThan(huge);
            // 반응이 93배인데 점수는 3배를 넘지 않는다
            assertThat(huge).isLessThan(small * 3);
        }

        @Test
        @DisplayName("아주 큰 값도 1.0을 넘지 않는다")
        void clampsToOne() {
            assertThat(PopularityScorer.buzzScore(100_000)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("rank — 통합")
    class Rank {

        @Test
        @DisplayName("점수가 높은 순으로 정렬한다")
        void sortsByScoreDescending() {
            List<Article> articles = List.of(
                    article("오래되고 아래쪽", 9, NOW.minus(Duration.ofDays(2))),
                    article("최신이고 맨 위", 0, NOW));

            List<ScoredArticle> ranked = scorer.rank(articles, List.of(), Map.of(), NOW);

            assertThat(ranked.get(0).article().title()).isEqualTo("최신이고 맨 위");
            assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
        }

        @Test
        @DisplayName("HN 데이터가 있는 기사만 buzz를 받고, 없는 기사도 여전히 순위에 남는다")
        void onlyArticlesOnHackerNewsGetBuzz() {
            Article onHn = article("HN에 올라감", 5, NOW);
            Article notOnHn = article("HN에 없음", 5, NOW);

            List<ScoredArticle> ranked = scorer.rank(
                    List.of(onHn, notOnHn),
                    List.of(),
                    Map.of(onHn.link(), 500),
                    NOW);

            assertThat(ranked).hasSize(2);
            ScoredArticle top = ranked.get(0);
            assertThat(top.article().title()).isEqualTo("HN에 올라감");
            assertThat(top.breakdown().buzz()).isGreaterThan(0.0);
            assertThat(ranked.get(1).breakdown().buzz()).isZero();
        }

        @Test
        @DisplayName("buzzByLink가 null이어도 죽지 않는다 — HN 조회 실패 시 강등 경로")
        void survivesNullBuzzMap() {
            List<ScoredArticle> ranked =
                    scorer.rank(List.of(article("아무거나", 0, NOW)), groups("금리"), null, NOW);

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).breakdown().buzz()).isZero();
        }

        @Test
        @DisplayName("점수는 항상 0~1 안에 있다 — 가중치를 바꿔도 척도가 유지된다")
        void scoreStaysNormalized() {
            PopularityScorer lopsided =
                    new PopularityScorer(new RankingWeights(10, 1, 1, 100), HALF_LIFE);

            Article a = article("최고 조건", 0, NOW);
            ScoredArticle best = lopsided.score(a, 20, groups("최고"), 100_000, NOW);
            ScoredArticle worst = lopsided.score(article("최악 조건", 19, NOW.minusSeconds(9_999_999)),
                    20, groups("없는말"), 0, NOW);

            assertThat(best.score()).isCloseTo(1.0, within(1e-9));
            assertThat(worst.score()).isBetween(0.0, 0.05);
        }
    }

    @Test
    @DisplayName("가중치가 전부 0이면 생성 자체를 거부한다 — 0으로 나누기 전에 막는다")
    void rejectsAllZeroWeights() {
        assertThatThrownBy(() -> new RankingWeights(0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반감기가 0이면 생성을 거부한다")
    void rejectsZeroHalfLife() {
        assertThatThrownBy(() -> new PopularityScorer(RankingWeights.defaults(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 표현마다 1항목 묶음 — 개념 수와 표현 수가 같은 경우다(정기 발송 사전이 이 모양이다). */
    private static List<KeywordGroup> groups(String... terms) {
        return Arrays.stream(terms).map(KeywordGroup::of).toList();
    }

    private static Article article(String title, int feedRank, Instant publishedAt) {
        return new Article(
                NewsSource.BLOOMBERG,
                title,
                null,
                "https://example.com/" + feedRank + "/" + title.hashCode(),
                publishedAt,
                feedRank);
    }
}
