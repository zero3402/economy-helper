package io.saiden.economyhelper.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.saiden.economyhelper.digest.DailyDigestJobTest.RecordingClient;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.telegram.TelegramClient;
import io.saiden.economyhelper.llm.GeminiApi;
import io.saiden.economyhelper.translate.GeminiTranslator;
import io.saiden.economyhelper.translate.Translation;
import io.saiden.economyhelper.translate.TranslationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진짜 Redis 위에서 두 가지를 확인한다.
 *
 * <ol>
 *   <li><b>2인스턴스가 동시에 돌아도 발송은 1회</b> — {@code CLAUDE.md}의 이중화 요구를
 *       지키면서 구독자가 같은 뉴스를 두 번 받지 않는다는, 이 단계의 핵심 보장이다.
 *   <li><b>애노테이션이 런타임에 실제로 프록시를 건다</b> — Boot 4에는 AOP 스타터가 없어
 *       {@code aspectjweaver}를 직접 걸었다. 지금까지는 컴파일·의존성 해결만 확인됐고
 *       {@code @Cacheable}·{@code @CircuitBreaker}·{@code @SchedulerLock}이 정말 동작하는지는
 *       미검증이었다. 여기서 판명된다.
 * </ol>
 */
@SpringBootTest(properties = {
        // 실제 텔레그램으로 나가지 않게 막는다. 즉시 연결 거부되므로 서킷브레이커 동작도 빨리 확인된다.
        "economy-helper.telegram.base-url=http://localhost:1"
})
@Import(DigestIntegrationTest.CountingTranslatorConfig.class)
@Testcontainers
class DigestIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    LockProvider lockProvider;
    @Autowired
    DailyDigestJob job;
    @Autowired
    TelegramClient telegramClient;
    @Autowired
    TranslationService translationService;
    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    org.springframework.cache.CacheManager cacheManager;

    @Test
    @DisplayName("두 인스턴스가 같은 슬롯을 동시에 처리해도 발송은 한 번뿐이다")
    void twoInstancesSendOnlyOnce() throws Exception {
        RecordingClient first = new RecordingClient();
        RecordingClient second = new RecordingClient();
        CyclicBarrier startTogether = new CyclicBarrier(2);

        // 인스턴스마다 자기 Redis 클라이언트를 갖는다 — 서버 하나를 두 파드가 보는 상황과 같다
        CompletableFuture<DigestResult> a =
                CompletableFuture.supplyAsync(() -> runAfter(startTogether, first));
        CompletableFuture<DigestResult> b =
                CompletableFuture.supplyAsync(() -> runAfter(startTogether, second));

        DigestResult resultA = a.get();
        DigestResult resultB = b.get();

        assertThat(first.sent.size() + second.sent.size())
                .as("replicas 2에서 09시 다이제스트가 두 번 나가면 안 된다")
                .isEqualTo(1);
        assertThat(List.of(resultA.sent(), resultB.sent()))
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("ShedLock의 Redis 락은 실제로 상호 배타적이다")
    void redisLockIsMutuallyExclusive() {
        LockConfiguration config = new LockConfiguration(
                Instant.now(), "dailyDigest-test", Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> held = lockProvider.lock(config);
        assertThat(held).isPresent();
        try {
            assertThat(lockProvider.lock(config))
                    .as("이미 잡힌 락이 또 잡히면 두 인스턴스가 같이 수집·번역을 돈다")
                    .isEmpty();
        } finally {
            held.get().unlock();
        }
    }

    @Test
    @DisplayName("@SchedulerLock이 런타임에 프록시로 걸려 있다")
    void schedulerLockIsProxiedAtRuntime() {
        assertThat(AopUtils.isAopProxy(job))
                .as("프록시가 아니면 @SchedulerLock은 주석과 다를 바 없다")
                .isTrue();
        assertThat(((Advised) job).getAdvisors())
                .anySatisfy(advisor -> assertThat(advisor.getClass().getName())
                        .containsIgnoringCase("ScheduledLock"));
    }

    @Test
    @DisplayName("@CircuitBreaker가 런타임에 동작한다 — 실패가 실제로 브레이커에 기록된다")
    void circuitBreakerAnnotationIsLive() {
        int before = circuitBreakerRegistry.circuitBreaker("telegram")
                .getMetrics().getNumberOfFailedCalls();

        assertThatThrownBy(() -> telegramClient.send("1", null, null, "연결 거부될 주소"))
                .isInstanceOf(Exception.class);

        assertThat(circuitBreakerRegistry.circuitBreaker("telegram")
                .getMetrics().getNumberOfFailedCalls())
                .as("Boot 4에는 AOP 스타터가 없어 aspectjweaver를 직접 걸었다. "
                        + "카운트가 늘지 않으면 애스펙트가 붙지 않은 것이다")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("@Cacheable이 런타임에 동작한다 — 같은 기사를 두 번 번역하지 않는다")
    void cacheableAnnotationIsLive() {
        Article article = new Article(NewsSource.CNBC, "Oil holds advance", "Oil kept gains.",
                "https://example.com/cache-proof-" + System.nanoTime(), NOW, 0);

        int before = CountingTranslatorConfig.calls.get();
        Translation first = translationService.translateAll(List.of(article)).get(0);
        awaitCached(article.link());
        Translation second = translationService.translateAll(List.of(article)).get(0);

        assertThat(second).isEqualTo(first);
        assertThat(CountingTranslatorConfig.calls.get() - before)
                .as("두 번 호출되면 무료 티어를 두 배로 태우고 있다는 뜻이다")
                .isEqualTo(1);
    }

    /**
     * 캐시 기록이 보일 때까지 짧게 기다린다.
     *
     * <p>측정해 보면 {@code @Cacheable}의 쓰기는 <b>호출 직후의 읽기에 곧바로 보이지 않는다</b>
     * (여기서는 20ms 안에 반영됐다). 운영에서는 다음 조회가 최소 몇 분 뒤라 무해하지만,
     * 이걸 모르고 테스트를 짜면 "캐시가 안 걸린다"로 잘못 읽게 된다.
     */
    private void awaitCached(String key) {
        for (int i = 0; i < 100 && cacheManager.getCache("translation").get(key) == null; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(cacheManager.getCache("translation").get(key))
                .as("2초를 기다려도 캐시에 값이 없다. 전체 키=%s", redisTemplate.keys("*"))
                .isNotNull();
    }

    @Test
    @DisplayName("캐시 값이 JSON으로 저장된다 — 레코드에 Serializable을 붙이지 않아도 된다")
    void cacheValuesRoundTripAsJson() {
        Article article = new Article(NewsSource.YAHOO_FINANCE, "Fed signals rate cut", null,
                "https://example.com/json-proof-" + System.nanoTime(), NOW, 0);

        translationService.translateAll(List.of(article)).get(0);
        awaitCached(article.link());

        Set<String> keys = redisTemplate.keys("*" + article.link());
        assertThat(keys).as("번역 결과가 Redis에 저장되지 않았다").isNotEmpty();

        String raw = redisTemplate.opsForValue().get(keys.iterator().next());
        assertThat(raw)
                .as("기본 JDK 직렬화였다면 레코드에서 예외가 났을 것이다")
                .isNotNull()
                .contains("번역된 제목");
    }

    private DigestResult runAfter(CyclicBarrier barrier, RecordingClient telegram) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        SendHistory history = new SendHistory(redisTemplate, DailyDigestJobTest.properties());
        // 시세 셋은 죽여 둔다 — 이 테스트의 관심사는 "동시 실행해도 한 번만 나가는가"다
        return new DailyDigestJob(fixedFacade(), deadFx(), deadStock(), deadCrypto(),
                telegram, history, Clock.fixed(NOW, ZoneOffset.UTC),
                DailyDigestJobTest.properties()).run(false);
    }

    private static io.saiden.economyhelper.market.FxService deadFx() {
        return new io.saiden.economyhelper.market.FxService(List.of()) {
            @Override
            public java.util.Optional<io.saiden.economyhelper.market.FxRate> usdToKrw() {
                return java.util.Optional.empty();
            }
        };
    }

    private static io.saiden.economyhelper.market.StockService deadStock() {
        return new io.saiden.economyhelper.market.StockService(List.of(), List.of(),
                new io.saiden.economyhelper.market.data.DataGoStockClient(null, null), null,
                code -> java.util.Optional.empty(), symbol -> java.util.Optional.empty()) {
            @Override
            public List<io.saiden.economyhelper.market.StockService.Answer> answersOf(
                    List<String> codes) {
                return List.of();
            }
        };
    }

    private static io.saiden.economyhelper.market.CryptoService deadCrypto() {
        return new io.saiden.economyhelper.market.CryptoService(
                new io.saiden.economyhelper.market.upbit.UpbitApi(
                        RestClient.builder(), "https://example.invalid"),
                new io.saiden.economyhelper.market.binance.BinanceApi(
                        RestClient.builder(),
                        new io.saiden.economyhelper.market.binance.BinanceBanGate(null, java.time.Clock.systemUTC()),
                        "https://example.invalid", ""),
                new io.saiden.economyhelper.market.CryptoResolver(null, null)) {
            @Override
            public List<io.saiden.economyhelper.market.CryptoQuote> quotesOf(List<String> markets) {
                return List.of();
            }
        };
    }

    private static NewsFacade fixedFacade() {
        return new NewsFacade(null, null, null) {
            @Override
            public List<NewsItem> digest() {
                return List.of(DailyDigestJobTest.item("동시 실행 테스트"));
            }
        };
    }

    /** 실제 Gemini 대신 호출 횟수를 세어 {@code @Cacheable}이 걸렸는지 관측한다. */
    @TestConfiguration
    static class CountingTranslatorConfig {

        static final AtomicInteger calls = new AtomicInteger();

        @Bean
        @Primary
        GeminiTranslator countingTranslator() {
            return new GeminiTranslator(
                    new GeminiApi(RestClient.builder(), "http://localhost:1", "test-key", "test-model"),
                    JsonMapper.builder().build()) {
                @Override
                public Translation translate(Article article) {
                    calls.incrementAndGet();
                    return Translation.of("번역된 제목", "번역된 본문");
                }
            };
        }
    }
}
