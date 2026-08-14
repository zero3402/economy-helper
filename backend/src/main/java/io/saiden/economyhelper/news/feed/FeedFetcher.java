package io.saiden.economyhelper.news.feed;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.StringReader;
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
 * <p><b>서킷브레이커를 애노테이션이 아니라 프로그래매틱 API로 건다.</b>
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
     * <b>우리 이름을 댄다.</b> 한동안 크롬 UA를 그대로 흉내 냈는데, 우리는 크롬이 아니고
     * 브라우저인 척할 이유도 없다 — RSS는 원래 클라이언트가 자기를 밝히기를 기대하는 쪽이다.
     *
     * <p>헤더 자체는 남긴다. 야후가 자바 기본 UA({@code Java-http-client})를 429로 막기
     * 때문이다(2026-08-14 실측, 두 번 다 429). 이 값으로는 다섯 매체 전부 200이다.
     *
     * <p><b>200이 곧 "기사가 나온다"는 아니다.</b> 같은 날 Investing은 200을 주면서 항목이
     * 전부 버려지고 있었다({@link RssFeedClient#normalizePubDates} 참조). 상태 코드만 보고
     * 연동이 살아 있다고 판단하지 않는다.
     */
    private static final String USER_AGENT = "economy-helper/1.0";

    private final RestClient restClient;
    private final EconomyHelperProperties properties;
    private final CircuitBreakerRegistry circuitBreakers;
    private final Map<FeedType, FeedClient> parsers = new EnumMap<>(FeedType.class);

    public FeedFetcher(RestClient.Builder builder,
                       EconomyHelperProperties properties,
                       CircuitBreakerRegistry circuitBreakers,
                       List<FeedClient> feedClients) {
        this.restClient = builder.defaultHeader("User-Agent", USER_AGENT).build();
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        for (FeedClient client : feedClients) {
            parsers.put(client.type(), client);
        }
    }

    /**
     * 실패 시 빈 리스트. 빈 결과는 캐시하지 않는다 — 일시적 장애를 10분간 굳히면
     * 그 사이 정기 발송이 통째로 그 매체를 빠뜨린다.
     */
    @Cacheable(cacheNames = "feed", key = "#source", unless = "#result.isEmpty()")
    public List<Article> fetch(NewsSource source) {
        Feed feed = properties.feeds().get(source);
        if (feed == null) {
            log.warn("[{}] 피드 설정이 없습니다", source);
            return List.of();
        }

        CircuitBreaker breaker = circuitBreakers.circuitBreaker("feed-" + source);
        try {
            return breaker.executeCallable(() -> download(source, feed));
        } catch (Exception e) {
            log.warn("[{}] 피드 수집 실패 — 이 매체는 건너뜁니다: {}", source, e.toString());
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
        return own;
    }
}
