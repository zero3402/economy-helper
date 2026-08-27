package io.saiden.economyhelper.news;

import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.news.rank.HackerNewsBuzzClient;
import io.saiden.economyhelper.news.rank.KeywordGroup;
import io.saiden.economyhelper.news.rank.PopularityScorer;
import io.saiden.economyhelper.news.rank.RelevanceScorer;
import io.saiden.economyhelper.support.Concurrently;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 수집 → 랭킹의 단일 진입점.
 *
 * <p>텔레그램 {@code /news}가 이 클래스를 부른다. 프론트용 REST가 붙는 날에도 같은 자리를
 * 쓰도록 갈라 뒀다 — <b>아직 HTTP 진입점은 웹훅 하나뿐이다.</b>
 * 두 경로가 다른 결과를 내지 않게 하려면 로직이 한 군데에 있어야 한다.
 *
 * <p>검색어를 어떻게 {@link KeywordGroup}으로 만드는지는 여기서 다루지 않는다
 * ({@link QueryExpander}의 몫이다). 이 클래스는 완성된 개념 목록으로 걸러내고 순위를 매긴다.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final FeedFetcher fetcher;
    private final HackerNewsBuzzClient buzzClient;
    private final PopularityScorer scorer;
    private final RelevanceScorer relevanceScorer;
    private final Clock clock;
    private final Duration window;
    private final int llmCandidates;
    private final double relevanceThreshold;
    private final int searchResults;
    private final int cryptoResults;
    private final int economyResults;

    public NewsService(FeedFetcher fetcher,
                       HackerNewsBuzzClient buzzClient,
                       PopularityScorer scorer,
                       RelevanceScorer relevanceScorer,
                       Clock clock,
                       @Value("${economy-helper.digest.window:24h}") Duration window,
                       @Value("${economy-helper.digest.llm-candidates:8}") int llmCandidates,
                       @Value("${economy-helper.digest.relevance-threshold:0.4}") double relevanceThreshold,
                       @Value("${economy-helper.digest.search-results:5}") int searchResults,
                       @Value("${economy-helper.digest.crypto-results:5}") int cryptoResults,
                       @Value("${economy-helper.digest.economy-results:5}") int economyResults) {
        this.fetcher = fetcher;
        this.buzzClient = buzzClient;
        this.scorer = scorer;
        this.relevanceScorer = relevanceScorer;
        this.clock = clock;
        this.window = window;
        this.llmCandidates = llmCandidates;
        this.relevanceThreshold = relevanceThreshold;
        this.searchResults = searchResults;
        this.cryptoResults = cryptoResults;
        this.economyResults = economyResults;
    }

    /** 신선도 창 — 화면이 "최근 몇 시간"이라고 말하려면 같은 값을 봐야 한다. */
    public Duration window() {
        return window;
    }

    /**
     * 최근 창(기본 24시간) 안에 발행된 것 중 <b>무리마다 점수 상위 몇 건</b> — 정기 발송용.
     *
     * <p><b>코인과 경제를 따로 채운다.</b> 예전에는 전 매체를 통틀어 상위 세 건이었는데, 그러면
     * 코인 기사가 금융 일반 피드에 거의 안 실리는 탓에({@link NewsSource#INVESTING_CRYPTO})
     * 코인 뉴스가 하루도 안 나가는 날이 생긴다. 지금은 {@link NewsCategory}로 가른 뒤
     * 무리마다 제 할당량을 준다.
     *
     * <p><b>모자란 무리는 채우지 않는다.</b> 코인이 세 건뿐인 날은 여덟 건이 나간다 —
     * 남은 자리를 경제로 메우면 사용자가 요구한 다섯 대 다섯이 조용히 다른 것이 되고,
     * 「자리를 채우려고 관련 없는 기사를 끌어오지 않는다」({@link #search})와도 어긋난다.
     *
     * <p><b>고르고 나서 다시 점수순으로 섞는다.</b> 화면에 무리 제목이 없고 통 제목이
     * {@code 뉴스 3/10}이므로, 그 {@code 3}은 지금까지처럼 <b>점수 3위</b>라는 뜻이어야 한다.
     * 할당량이 정하는 것은 어느 열 건이 들어오는가이지 순서가 아니다.
     *
     * <p><b>매체를 가리지 않고 점수로 줄 세운다.</b> 매체별 정규화({@code feedRank})를 거친
     * 0~1 점수라 매체가 달라도 비교가 성립한다.
     *
     * <p><b>재테크 관련도가 임계값 이상인 기사만 후보로 삼는다.</b> 필터 없이 순위만 매기면
     * "EU 국경 검사로 공항 대기줄 두 배" 같은 기사가 상위로 뽑힌다 — 랭킹 네 항 중
     * 셋({@code feedRank}·{@code recency}·{@code buzz})이 주제를 전혀 모르기 때문이다.
     * 관련도를 <b>어떻게 재는지</b>는 {@link RelevanceScorer}가 정한다.
     *
     * <p>수집에 실패한 매체도 결과에서 빠질 뿐 나머지를 막지 않는다.
     * 이게 "한 소스가 죽어도 발송은 계속된다"의 실제 구현이다.
     */
    public List<ScoredArticle> digest() {
        // 매체끼리는 서로를 기다릴 이유가 없어 겹쳐 돈다 — 가장 느린 매체 하나 만큼만 걸린다.
        // 매체 안에서는 순차다: 피드가 있어야 후보가 나오고 후보가 있어야 관련도를 잰다
        List<ScoredArticle> ranked = distinctByLink(
                Concurrently.map(List.of(NewsSource.values()), this::relevantOf).stream()
                        .flatMap(List::stream)
                        .sorted(Comparator.comparingDouble(ScoredArticle::score).reversed())
                        .toList(),
                scored -> scored.article().link());

        // 가르기 전에 링크로 한 번 걸렀으므로 한 기사가 두 무리에 앉을 수 없다.
        // 무리 안의 순서는 위에서 매긴 점수순 그대로다 (groupingBy는 값 목록의 등장 순서를 지킨다)
        Map<NewsCategory, List<ScoredArticle>> byCategory = ranked.stream()
                .collect(Collectors.groupingBy(scored -> NewsCategory.of(scored.article())));

        List<ScoredArticle> picked = new ArrayList<>(cryptoResults + economyResults);
        picked.addAll(quota(byCategory, NewsCategory.CRYPTO, cryptoResults));
        picked.addAll(quota(byCategory, NewsCategory.ECONOMY, economyResults));

        return picked.stream()
                .sorted(Comparator.comparingDouble(ScoredArticle::score).reversed())
                .toList();
    }

    /**
     * 무리 하나의 할당량만큼 — 모자라면 <b>모자란 채로</b> 돌려준다.
     *
     * <p>못 채운 자리를 로그로 남긴다. 조용히 짧게 나가면 「코인 뉴스가 왜 세 건인가」에
     * 답할 단서가 없다 — 소스가 죽은 것인지, 관련도 문턱에 걸린 것인지, 그 사이 코인 기사가
     * 없었던 것인지가 구분되지 않는다.
     */
    private static List<ScoredArticle> quota(Map<NewsCategory, List<ScoredArticle>> byCategory,
                                             NewsCategory category, int limit) {
        List<ScoredArticle> pool = byCategory.getOrDefault(category, List.of());
        if (pool.size() < limit) {
            log.info("[{}] 창 안의 기사가 {}건뿐이어서 할당량 {}건을 못 채웁니다 —"
                    + " 남은 자리를 다른 무리로 메우지 않습니다", category, pool.size(), limit);
        }
        return pool.stream().limit(limit).toList();
    }

    /**
     * 매체 하나에서 최근 창 안의 재테크 기사들을 점수순으로. 그 매체가 죽거나 그 사이 재테크 기사가
     * 없으면 비어 있다. 상위 몇 건을 고르는 것은 {@link #digest()}가 전 매체를 모아 한다.
     */
    private List<ScoredArticle> relevantOf(NewsSource source) {
        Instant now = clock.instant();
        // 최근 창 안의 발행분만 남긴다 — 묵은 기사로 자리를 채우지 않는다
        List<Article> articles = recent(fetcher.fetch(source), now);
        if (articles.isEmpty()) {
            log.warn("[{}] 최근 {} 발행분이 없어 이번 발송에서 제외합니다", source, window);
            return List.of();
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
            return List.of();
        }

        return scorer.rankByRelevance(relevant, relevance, buzz, now);
    }

    /**
     * <b>최근 {@code window} 안에 발행된</b> 기사만 남긴다.
     *
     * <p>신선도는 랭킹 점수에도 반영되지만({@code recency}), 그건 가중치의 한 항일 뿐이라
     * 피드 앞자리의 묵은 기사를 못 막는다. 그래서 하드 필터가 따로 있다.
     *
     * <p><b>날짜(KST 달력)가 아니라 경과 시간으로 자른다.</b> 우리가 읽는 것은 전부 외국 기사인데
     * KST 자정 경계는 그 매체의 하루를 둘로 쪼갠다 — 미국 동부 취재일(09:00~17:00 EDT)이
     * KST로는 22:00부터 다음 날 06:00까지라, 같은 사건을 다룬 기사들이 이틀로 갈린다.
     * 09시 브리핑 시점의 "오늘 KST"는 UTC 전날 15:00부터의 9시간뿐이기도 하다.
     *
     * <p>실측(2026-08-15): {@code /news 이더리움}이 빈손이었는데, 가장 최근 기사가
     * 23시간 18분 전({@code 08-14 21:36 KST}) 것이라 <b>하루도 안 지났는데 "어제"라서</b>
     * 잘려 나간 것이었다. 경과 시간으로 자르면 매체 소재지·발행 시각과 무관하게 창의 폭이 같다.
     */
    private List<Article> recent(List<Article> articles, Instant now) {
        Instant cutoff = now.minus(window);
        return articles.stream()
                .filter(article -> article.publishedAt().isAfter(cutoff))
                .toList();
    }

    /**
     * @param query 사용자가 친 원문. 주면 상위 후보를 <b>LLM으로 한 번 더 검증</b>한다 —
     *              문자열 매칭은 "본문에 한 번 스친" 기사도 통과시켜, 환율 기사가
     *              {@code /news 금리}의 1위가 되곤 했다. {@code null}이면 검증을 건너뛴다
     */
    public List<ScoredArticle> search(Collection<KeywordGroup> keywords, String query) {
        List<KeywordGroup> groups = usable(keywords);
        if (groups.isEmpty()) {
            return List.of();
        }

        // 매체 전부를 동시에 긁는다 — 검색은 사용자가 화면을 보고 기다리는 자리라
        // 순차로 도는 시간이 그대로 체감된다. 브리핑과 같은 신선도 창을 쓴다
        List<Article> matching = recent(distinctByLink(
                Concurrently.map(List.of(NewsSource.values()), fetcher::fetch).stream()
                        .flatMap(List::stream)
                        .filter(article -> matchesAny(article, groups))
                        .toList(), Article::link), clock.instant());

        // 걸린 게 적으면 그만큼만 나간다 — 자리를 채우려고 관련 없는 기사를 끌어오지 않는다
        return verified(rank(matching, groups), query).stream().limit(searchResults).toList();
    }

    /**
     * 상위 후보가 <b>정말 그 주제의 기사인지</b> LLM에 한 번 묻는다.
     *
     * <p>매칭은 후보를 좁히는 데까지만 쓴다. {@code /news 금리}가 본문에 {@code rate}가 한 번
     * 나온 환율 기사를 1위로 내보내던 것이 이 검증이 없어서였다.
     *
     * <p><b>전부 임계값 미만이면 빈 목록</b>이다 — 호출자는 "찾지 못했습니다"로 답한다.
     * 관련 없는 기사를 답이라고 내미는 것보다 못 찾았다고 하는 편이 낫다.
     *
     * <p>비용은 상위 몇 건 <b>한 번</b>이고 검색어+후보 조합으로 캐시된다. LLM이 죽으면
     * 전부 통과시켜 예전 동작으로 돌아간다 — 검증이 없어지는 것이지 검색이 죽는 것은 아니다.
     */
    private List<ScoredArticle> verified(List<ScoredArticle> ranked, String query) {
        if (query == null || query.isBlank() || ranked.isEmpty()) {
            return ranked;
        }
        List<ScoredArticle> candidates = ranked.stream().limit(llmCandidates).toList();
        Map<String, Double> relevance = relevanceScorer.scoreAll(
                candidates.stream().map(ScoredArticle::article).toList(), query);

        List<ScoredArticle> relevant = candidates.stream()
                .filter(scored -> relevance.getOrDefault(scored.article().link(), 0.0)
                        >= relevanceThreshold)
                .toList();
        if (relevant.isEmpty()) {
            log.info("[검색] '{}'에 걸린 {}건 중 실제로 그 주제를 다루는 기사가 없습니다",
                    query, candidates.size());
        }
        return relevant;
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

    /**
     * 같은 기사가 두 번 나오지 않게 <b>링크로 한 번 거른다.</b>
     *
     * <p><b>매체 하나가 피드를 둘 달 수 있다</b> — Investing.com이 본 섹션과 암호화폐 섹션을
     * 함께 단다({@code CLAUDE.md}). 같은 기사가 두 피드에 실리면 그대로 두 건이 된다.
     * 실측(2026-08-19): {@code /news 금리} 3건 중 1번과 3번이 <b>글자 그대로 같은 기사</b>였다.
     *
     * <p><b>앞의 것을 남긴다.</b> 브리핑은 점수순으로 정렬한 뒤 부르므로 남는 것이 더 높은
     * 점수이고, 검색은 피드 순서가 곧 편집자가 매긴 우선순위다.
     *
     * <p>{@code Set}으로 받지 않는다 — 순서가 곧 의미인 목록이다.
     */
    private static <T> List<T> distinctByLink(List<T> items, java.util.function.Function<T, String> link) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        return items.stream().filter(item -> seen.add(link.apply(item))).toList();
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
