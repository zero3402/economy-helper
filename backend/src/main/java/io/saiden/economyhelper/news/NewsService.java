package io.saiden.economyhelper.news;

import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.news.rank.HackerNewsBuzzClient;
import io.saiden.economyhelper.news.rank.KeywordGroup;
import io.saiden.economyhelper.news.rank.PopularityScorer;
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

    private final FeedFetcher fetcher;
    private final HackerNewsBuzzClient buzzClient;
    private final PopularityScorer scorer;
    private final Clock clock;

    public NewsService(FeedFetcher fetcher,
                       HackerNewsBuzzClient buzzClient,
                       PopularityScorer scorer,
                       Clock clock) {
        this.fetcher = fetcher;
        this.buzzClient = buzzClient;
        this.scorer = scorer;
        this.clock = clock;
    }

    /**
     * 매체별 1건 — 정기 발송용.
     *
     * <p><b>재테크 키워드가 걸리는 기사만 후보로 삼는다.</b> 걸리는 게 하나도 없으면 그 매체는
     * 이번 발송에서 빠진다. FT 홈 피드처럼 일반 뉴스가 섞여 오는 매체에서 필터 없이 순위만 매기면
     * "EU 국경 검사로 공항 대기줄 두 배" 같은 기사가 1위로 뽑힌다(8단계에서 실제로 겪음) —
     * 재테크 뉴스가 아닌 걸 채워 보내는 것보다 그 매체를 비우는 편이 낫다.
     *
     * <p>수집에 실패한 매체도 결과에서 빠질 뿐 나머지를 막지 않는다.
     * 이게 "한 소스가 죽어도 발송은 계속된다"의 실제 구현이다.
     */
    public Map<NewsSource, ScoredArticle> digest(Collection<KeywordGroup> keywords) {
        List<KeywordGroup> dictionary = usable(keywords);
        Map<NewsSource, ScoredArticle> top = new EnumMap<>(NewsSource.class);
        for (NewsSource source : NewsSource.values()) {
            List<Article> articles = fetcher.fetch(source);
            if (articles.isEmpty()) {
                log.warn("[{}] 수집 결과가 없어 이번 발송에서 제외합니다", source);
                continue;
            }

            List<Article> relevant = relevantTo(articles, dictionary);
            if (relevant.isEmpty()) {
                log.info("[{}] 재테크 키워드가 걸리는 기사가 없어 이번 발송에서 제외합니다 (수집 {}건)",
                        source, articles.size());
                continue;
            }
            rank(relevant, dictionary).stream().findFirst().ifPresent(article -> top.put(source, article));
        }
        return top;
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

        List<Article> matching = new ArrayList<>();
        for (NewsSource source : NewsSource.values()) {
            for (Article article : fetcher.fetch(source)) {
                if (matchesAny(article, groups)) {
                    matching.add(article);
                }
            }
        }
        return rank(matching, groups).stream().findFirst();
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

    /** 사전이 비어 있으면 걸러낼 근거가 없으므로 전부 통과시킨다. */
    private static List<Article> relevantTo(List<Article> articles, List<KeywordGroup> dictionary) {
        if (dictionary.isEmpty()) {
            return articles;
        }
        return articles.stream().filter(article -> matchesAny(article, dictionary)).toList();
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
