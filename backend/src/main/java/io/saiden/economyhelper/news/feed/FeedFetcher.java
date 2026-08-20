package io.saiden.economyhelper.news.feed;

import io.saiden.economyhelper.config.CacheNames;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.saiden.economyhelper.support.FailureReason;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 매체 하나의 피드를 받아 파싱한다.
 *
 * <p><b>서킷브레이커를 애노테이션이 아니라 프로그래매틱 API로 건다.</b>
 * <p><b>재시도도 프로그래매틱이다 — 다만 이유가 브레이커와 다르다.</b> 브레이커는 이름이
 * 매체별이어야 해서이고({@code feed-YAHOO}), 재시도는 이름을 하나만 쓰는데도 애너테이션을
 * 못 쓴다: {@link #fetch}가 예외를 <b>스스로 삼켜</b> 빈 목록을 주므로 {@code @Retry}를 달면
 * <b>발동조차 하지 않는다.</b> 그 사실을 모르고 달면 "걸어 뒀다"고 믿는 죽은 애너테이션이 남는다.
 *
 * {@code @CircuitBreaker(name = "feed")}는 이름이 컴파일 타임에 고정이라 5개 매체가
 * 브레이커 하나를 공유하게 된다 — Yahoo가 죽으면 BBC 호출까지 끊긴다.
 * 소스별로 이름을 달리하려면 런타임에 레지스트리에서 꺼내야 한다.
 *
 * <p>실패는 예외가 아니라 빈 리스트다. 한 매체가 죽어도 나머지 매체 발송은 계속돼야 한다.
 */
@Component
public class FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    /**
     * <b>우리 이름을 댄다.</b> RSS는 클라이언트가 자기를 밝히기를 기대하는 쪽이고, 우리는
     * 브라우저가 아니다.
     *
     * <p>헤더를 비우지는 않는다. 야후가 자바 기본 UA({@code Java-http-client})를 429로 막기
     * 때문이다(2026-08-14 실측, 두 번 다 429). 이 값으로는 다섯 매체 전부 200이다.
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
                       @Value("${economy-helper.ranking.max-age:3d}") Duration maxAge,
                       List<FeedClient> feedClients) {
        this.restClient = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.clock = clock;
        this.maxAge = maxAge;
        for (FeedClient client : feedClients) {
            parsers.put(client.type(), client);
        }
    }

    /**
     * 실패 시 빈 리스트. 빈 결과는 캐시하지 않는다 — 일시적 장애를 10분간 굳히면
     * 그 사이 정기 발송이 통째로 그 매체를 빠뜨린다.
     */
    @Cacheable(cacheNames = CacheNames.FEED, key = "#source", unless = "#result.isEmpty()")
    public List<Article> fetch(NewsSource source) {
        Feed feed = properties.feeds().get(source);
        if (feed == null) {
            log.warn("[{}] 피드 설정이 없습니다", source);
            return List.of();
        }

        CircuitBreaker breaker = circuitBreakers.circuitBreaker("feed-" + source);
        try {
            // 재시도가 브레이커 **바깥**이다 — 애너테이션을 쓰는 일곱과 같은 순서다.
            // 브레이커가 열리면 CallNotPermittedException이 나고 그건 retry의 무시 목록에
            // 있어 재시도가 즉시 멈춘다(열린 문을 두 번 두드리지 않는다)
            return Retry.decorateCallable(retries.retry("feed"),
                    CircuitBreaker.decorateCallable(breaker, () -> download(source, feed))).call();
        } catch (Exception e) {
            log.warn("[{}] 피드 수집 실패 — 이 매체는 건너뜁니다: {}", source, FailureReason.of(e));
            return List.of();
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
        List<Article> parsed = parser.parse(source, new StringReader(body));
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
