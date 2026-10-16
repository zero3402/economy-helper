package io.saiden.economyhelper.news.adapter.out.feed;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.news.application.port.out.ArticleFeed;
import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.FeedType;
import io.saiden.economyhelper.news.domain.NewsSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 매체 하나의 피드를 받아 파싱한다.
 *
 * <p><b>서킷브레이커와 재시도를 애너테이션이 아니라 프로그래매틱 API로 건다.</b> 이유가 둘이다.
 * 브레이커는 이름이 매체별이어야 한다({@code feed-YAHOO}) — 애너테이션은 이름이 컴파일 타임에
 * 고정이라 매체들이 브레이커 하나를 공유해 Yahoo가 죽으면 BBC 호출까지 끊긴다. 재시도는
 * {@link #fetch}가 실패를 {@link ArticleFeed.Unavailable}로 <b>감싸기 전에</b> 돌아야 한다 —
 * {@code @Retry}를 달면 감싼 예외를 보게 되어 재시도 설정의 무시 목록(열린 브레이커)이 먹지 않는다.
 *
 * <p>실패는 빈 리스트가 아니라 예외다 — 빈 리스트는 「피드가 답했는데 창 안의 기사가 없다」다. 한 매체를
 * 건너뛰고 나머지로 답하는 것은 호출자({@code NewsService.fetchEach})가 하고, 전부 실패하면 장애로 알린다.
 */
@Component
public class FeedFetcher implements ArticleFeed {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    /**
     * <b>우리 이름을 댄다.</b> RSS는 클라이언트가 자기를 밝히기를 기대하는 쪽이고, 우리는
     * 브라우저가 아니다.
     *
     * <p>헤더를 비우지는 않는다. 야후가 자바 기본 UA({@code Java-http-client})를 429로 막기
     * 때문이다(2026-08-14 실측, 두 번 다 429). 이 값으로는 매체 전부 200이다.
     */
    private static final String USER_AGENT = "economy-helper/1.0";

    private final RestClient restClient;
    private final EconomyHelperProperties properties;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final Clock clock;
    private final Duration maxAge;
    private final Map<FeedType, FeedClient> parsers = new EnumMap<>(FeedType.class);

    public FeedFetcher(RestClient.Builder builder,
                       EconomyHelperProperties properties,
                       CircuitBreakerRegistry circuitBreakers,
                       RetryRegistry retries,
                       Clock clock,
                       List<FeedClient> feedClients) {
        this.restClient = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.clock = clock;
        this.maxAge = properties.ranking().maxAge();
        for (FeedClient client : feedClients) {
            parsers.put(client.type(), client);
        }
    }

    /**
     * 빈 결과는 캐시하지 않는다 — 일시적 장애를 10분간 굳히면 그 사이 정기 발송이 통째로 그 매체를 빠뜨린다.
     * 실패는 예외라 애초에 캐시에 담기지 않는다.
     *
     * @throws ArticleFeed.Unavailable 재시도 뒤에도 받지 못했거나 파싱하지 못했을 때, 브레이커가 열렸을 때
     */
    @Override
    @Cacheable(cacheNames = CacheNames.FEED, key = "#source", unless = "#result.isEmpty()")
    public List<Article> fetch(NewsSource source) {
        Feed feed = properties.feeds().get(source);
        if (feed == null) {
            // 빈 목록이 아니다 — 빈 목록은 「피드가 답했는데 기사가 없다」라 설정 누락이 성공으로 세어져 조용히 묻힌다
            throw new ArticleFeed.Unavailable(source, "피드 설정이 없습니다");
        }

        CircuitBreaker breaker = circuitBreakers.circuitBreaker("feed-" + source);
        try {
            // 재시도가 브레이커 **바깥**이다 — 애너테이션을 쓰는 곳들과 같은 순서다.
            // 브레이커가 열리면 CallNotPermittedException이 나고 그건 retry의 무시 목록에
            // 있어 재시도가 즉시 멈춘다(열린 문을 두 번 두드리지 않는다)
            return Retry.decorateCallable(retries.retry("feed"),
                    CircuitBreaker.decorateCallable(breaker, () -> download(source, feed))).call();
        } catch (Exception e) {
            // 로그는 건너뛰는 쪽(NewsService)이 남긴다 — 원인 사슬은 cause로 따라간다
            throw new ArticleFeed.Unavailable(source, e);
        }
    }

    private List<Article> download(NewsSource source, Feed feed) {
        FeedClient parser = parsers.get(feed.type());
        if (parser == null) {
            throw new IllegalStateException("파서가 없는 피드 형식입니다: " + feed.type());
        }
        String body = restClient.get().uri(feed.url()).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new FeedParseException(source, "응답 본문이 비어 있습니다", null);
        }

        // 그 매체 피드에서는 그 매체 기사만 쓴다. Yahoo 피드가 wsj.com·investors.com을
        // 실어 나르는데 둘 다 페이월이다 — 우리가 고른 것은 Yahoo지 그들이 아니다.
        // 캐시(@Cacheable) 안쪽이라 걸러낸 결과가 캐시되고 파서 종류와 무관하게 걸린다
        List<Article> parsed = parser.parse(source, body);
        List<Article> own = parsed.stream().filter(article -> source.owns(article.link())).toList();
        if (own.size() < parsed.size()) {
            // 조용히 사라지면 나중에 "왜 이 매체만 기사가 적지"를 처음부터 다시 추적하게 된다
            log.info("[{}] 다른 매체 기사 {}건을 뺐습니다 ({}건 중)",
                    source, parsed.size() - own.size(), parsed.size());
        }

        // 오래된 기사를 여기서 자른다. 신선도 가중치만으로는 못 막는다 — 랭킹 네 항 중
        // 하나일 뿐이라 피드 앞자리에 놓인 옛 기사가 그대로 1위가 된다. 실측에서 Yahoo가
        // 50건 중 12건을 2024~2025년 기사로 채웠고 그중 둘이 /news 검색 상위에 올라왔다
        Instant cutoff = clock.instant().minus(maxAge);
        List<Article> fresh = own.stream()
                .filter(article -> article.publishedAt().isAfter(cutoff)).toList();
        if (fresh.size() < own.size()) {
            log.info("[{}] {} 넘은 기사 {}건을 뺐습니다 ({}건 중)",
                    source, maxAge, own.size() - fresh.size(), own.size());
        }
        return fresh;
    }
}
