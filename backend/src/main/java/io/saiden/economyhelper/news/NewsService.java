package io.saiden.economyhelper.news;

import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.news.rank.HackerNewsBuzzClient;
import io.saiden.economyhelper.news.rank.KeywordGroup;
import io.saiden.economyhelper.news.rank.PopularityScorer;
import io.saiden.economyhelper.news.rank.RelevanceScorer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 수집 → 랭킹의 단일 진입점.
 *
 * <p>텔레그램 {@code /news}와 프론트용 REST가 <b>같은 이 클래스를 부른다</b> —
 * 두 경로가 다른 결과를 내지 않게 하려면 로직이 한 군데에 있어야 한다.
 *
 * <p>검색어를 어떻게 {@link KeywordGroup}으로 만드는지는 여기서 다루지 않는다
 * ({@link QueryExpander}의 몫이다). 이 클래스는 완성된 개념 목록으로 걸러내고 순위를 매긴다.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    /**
     * "사실상 같은 점수"의 기준. 1위의 95% 이상이면 오차 범위로 본다.
     *
     * <p>더 낮게 잡으면 뚜렷이 못한 기사가 무료라는 이유만으로 올라오고, 더 높게 잡으면
     * 이 규칙이 사실상 동점에만 걸려 아무 일도 하지 않는다.
     */
    private static final double READABLE_MARGIN = 0.95;

    private final FeedFetcher fetcher;
    private final HackerNewsBuzzClient buzzClient;
    private final PopularityScorer scorer;
    private final RelevanceScorer relevanceScorer;
    private final Clock clock;
    private final int llmCandidates;
    private final double relevanceThreshold;

    public NewsService(FeedFetcher fetcher,
                       HackerNewsBuzzClient buzzClient,
                       PopularityScorer scorer,
                       RelevanceScorer relevanceScorer,
                       Clock clock,
                       @Value("${economy-helper.digest.llm-candidates:8}") int llmCandidates,
                       @Value("${economy-helper.digest.relevance-threshold:0.4}") double relevanceThreshold) {
        this.fetcher = fetcher;
        this.buzzClient = buzzClient;
        this.scorer = scorer;
        this.relevanceScorer = relevanceScorer;
        this.clock = clock;
        this.llmCandidates = llmCandidates;
        this.relevanceThreshold = relevanceThreshold;
    }

    /**
     * 매체별 1건 — 정기 발송용.
     *
     * <p><b>재테크 관련도가 임계값 이상인 기사만 후보로 삼는다.</b> 없으면 그 매체는 이번 발송에서
     * 빠진다 — 재테크 뉴스가 아닌 걸 채워 보내는 것보다 낫다. 필터 없이 순위만 매기면
     * "EU 국경 검사로 공항 대기줄 두 배" 같은 기사가 1위로 뽑힌다(8단계에서 실제로 겪음).
     * 랭킹 네 항 중 셋({@code feedRank}·{@code recency}·{@code buzz})은 주제를 전혀 모르기 때문이다.
     *
     * <p>관련도를 <b>어떻게 재는지</b>는 {@link RelevanceScorer}가 정한다.
     * 여기서는 후보를 좁혀 넘기고 임계값으로 자를 뿐이다.
     *
     * <p>수집에 실패한 매체도 결과에서 빠질 뿐 나머지를 막지 않는다.
     * 이게 "한 소스가 죽어도 발송은 계속된다"의 실제 구현이다.
     */
    public Map<NewsSource, ScoredArticle> digest() {
        Instant now = clock.instant();

        // 매체끼리는 서로를 기다릴 이유가 없다. 예전에는 다섯 매체를 줄줄이 돌아
        // (피드 + HN + Gemini) × 5가 전부 더해졌다 — 이제 가장 느린 매체 하나 만큼만 걸린다.
        // 매체 <b>안에서는</b> 순차를 유지한다. 피드가 있어야 후보가 나오고 후보가 있어야 관련도다
        List<ScoredArticle> best = Concurrently.map(List.of(NewsSource.values()), this::topOf).stream()
                .flatMap(Optional::stream)
                .toList();

        Map<NewsSource, ScoredArticle> top = new EnumMap<>(NewsSource.class);
        for (ScoredArticle article : best) {
            top.put(article.article().source(), article);
        }
        return top;
    }

    /** 매체 하나에서 1건. 그 매체가 죽거나 재테크 기사가 없으면 비어 있다. */
    private Optional<ScoredArticle> topOf(NewsSource source) {
        Instant now = clock.instant();
        List<Article> articles = fetcher.fetch(source);
        if (articles.isEmpty()) {
            log.warn("[{}] 수집 결과가 없어 이번 발송에서 제외합니다", source);
            return Optional.empty();
        }

        Map<String, Integer> buzz = buzzClient.buzzByLink(articles, now);

        // 1) 관련도 없이 먼저 줄을 세워 상위 몇 건만 남긴다 — LLM에 전부 넣으면 무료 티어를 태운다.
        //    이 단계의 점수는 주제를 모르지만(노출 순서·최신성·반응) 후보를 좁히는 데는 충분하다
        List<Article> candidates = scorer.rankByRelevance(articles, Map.of(), buzz, now).stream()
                .limit(llmCandidates)
                .map(ScoredArticle::article)
                .toList();

        // 2) 후보들의 재테크 관련도를 한 번에 매긴다 (LLM 실패 시 전부 통과 — 피드가 이미 금융 전용이다)
        Map<String, Double> relevance = relevanceScorer.scoreAll(candidates);

        // 3) 임계값 미만은 버린다 — 예전의 키워드 필터가 하던 일을 점수가 대신한다
        List<Article> relevant = candidates.stream()
                .filter(article -> relevance.getOrDefault(article.link(), 0.0) >= relevanceThreshold)
                .toList();
        if (relevant.isEmpty()) {
            log.info("[{}] 재테크 관련도가 {} 이상인 기사가 없어 이번 발송에서 제외합니다 (후보 {}건)",
                    source, relevanceThreshold, candidates.size());
            return Optional.empty();
        }

        return scorer.rankByRelevance(relevant, relevance, buzz, now).stream().findFirst();
    }

    /**
     * {@code /news {검색어}} — 전 매체에서 1위 한 건.
     *
     * <p>먼저 검색어가 걸리는 기사만 남기고 그 안에서 순위를 매긴다. 걸러내지 않으면
     * 검색어와 무관한 기사가 다른 지표만으로 1위가 될 수 있다.
     */
    public Optional<ScoredArticle> search(Collection<KeywordGroup> keywords) {
        List<KeywordGroup> groups = usable(keywords);
        if (groups.isEmpty()) {
            return Optional.empty();
        }

        // 매체 다섯을 동시에 긁는다 — 검색은 사용자가 화면을 보고 기다리는 자리라
        // 순차로 도는 시간이 그대로 체감된다
        List<Article> matching = Concurrently.map(List.of(NewsSource.values()), fetcher::fetch).stream()
                .flatMap(List::stream)
                .filter(article -> matchesAny(article, groups))
                .toList();
        return preferReadable(rank(matching, groups));
    }

    /**
     * 1위와 <b>사실상 같은 점수</b>인 무료 기사가 있으면 그쪽을 준다.
     *
     * <p>Bloomberg·FT·Economist는 링크를 눌러도 대부분 못 읽는다. 점수가 뚜렷이 높으면
     * 그래도 그게 답이지만, 오차 범위 안이라면 <b>읽히는 쪽</b>이 사용자에게 더 나은 답이다.
     * 유료 매체를 목록에서 빼지 않는 이유는 그쪽 기사 품질이 실제로 높기 때문이다.
     */
    private static Optional<ScoredArticle> preferReadable(List<ScoredArticle> ranked) {
        Optional<ScoredArticle> top = ranked.stream().findFirst();
        if (top.isEmpty() || !top.get().article().source().paywalled()) {
            return top;
        }
        double threshold = top.get().score() * READABLE_MARGIN;
        return ranked.stream()
                .filter(scored -> !scored.article().source().paywalled())
                .filter(scored -> scored.score() >= threshold)
                .findFirst()
                .or(() -> top);
    }

    private List<ScoredArticle> rank(List<Article> articles, Collection<KeywordGroup> keywords) {
        if (articles.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        // HN 조회는 여기서 한 번에 끝낸다 — PopularityScorer는 I/O를 모르는 순수 함수다
        Map<String, Integer> buzz = buzzClient.buzzByLink(articles, now);
        return scorer.rank(articles, keywords, buzz, now);
    }

    private static List<KeywordGroup> usable(Collection<KeywordGroup> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream()
                .filter(group -> group != null && !group.isEmpty())
                .distinct()
                .toList();
    }

    private static boolean matchesAny(Article article, List<KeywordGroup> keywords) {
        String haystack = article.text().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(group -> group.matches(haystack));
    }
}
