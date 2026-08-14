package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties.CacheTtl;
import io.saiden.economyhelper.market.CryptoResolver;
import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import io.saiden.economyhelper.market.StockResolver;
import io.saiden.economyhelper.market.binance.BinanceApi;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.fmp.FmpApi;
import io.saiden.economyhelper.market.frankfurter.FrankfurterFxClient;
import io.saiden.economyhelper.market.kexim.KeximFxClient;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.news.rank.HackerNewsApi;
import io.saiden.economyhelper.news.rank.RelevanceScorer;
import io.saiden.economyhelper.translate.QueryTranslator;
import io.saiden.economyhelper.translate.Translation;
import io.saiden.economyhelper.translate.TranslationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.core.type.TypeReference;

/**
 * 캐시에 넣은 타입이 그대로 나오는지 Redis 없이 고정한다.
 *
 * <p>여기가 깨지면 증상이 캐시 <b>히트</b>에서만 나타난다 — 처음 한 번은 멀쩡히 동작하고
 * 두 번째 호출부터 타입이 어긋난다. 로컬에서 지나치기 딱 좋은 형태라 직렬화만 떼어 따로 본다.
 * 캐시 세 개가 각각 담는 타입을 그대로 나열한다.
 */
class CacheConfigTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    @DisplayName("translation 캐시 — Translation이 그대로 돌아온다")
    void roundTripsTranslation() {
        JacksonJsonRedisSerializer<Translation> serializer =
                CacheConfig.serializer(new TypeReference<Translation>() {});
        Translation original = Translation.of("유가, 4일 상승분 유지", "인플레이션 우려가 되살아났다.");

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("feed 캐시 — List<Article>이 Instant·null 필드까지 그대로 돌아온다")
    void roundTripsArticleList() {
        JacksonJsonRedisSerializer<List<Article>> serializer =
                CacheConfig.serializer(new TypeReference<List<Article>>() {});
        List<Article> original = List.of(
                new Article(NewsSource.BLOOMBERG, "Oil holds advance", "Oil kept its gains.",
                        "https://example.com/1", NOW, 0),
                // Google News 프록시는 description이 없다
                new Article(NewsSource.REUTERS, "Fed signals rate cut", null,
                        "https://example.com/2", NOW, 1));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("hn-buzz 캐시 — Map<String, Integer>가 그대로 돌아온다")
    void roundTripsBuzzMap() {
        JacksonJsonRedisSerializer<Map<String, Integer>> serializer =
                CacheConfig.serializer(new TypeReference<Map<String, Integer>>() {});
        Map<String, Integer> original = Map.of("example.com/a", 930, "example.com/b", 3);

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("market-index 캐시 — MarketIndex가 그대로 돌아온다")
    void roundTripsMarketIndex() {
        JacksonJsonRedisSerializer<MarketIndex> serializer =
                CacheConfig.serializer(new TypeReference<MarketIndex>() {});
        MarketIndex original = new MarketIndex("20260811", "코스피", "KOSPI시리즈", "6345.53");

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("binance-price 캐시 — List<BinancePrice>가 그대로 돌아온다")
    void roundTripsBinancePrices() {
        JacksonJsonRedisSerializer<List<BinancePrice>> serializer =
                CacheConfig.serializer(new TypeReference<List<BinancePrice>>() {});
        List<BinancePrice> original = List.of(
                new BinancePrice("BTCUSDT", new BigDecimal("63703.69")),
                new BinancePrice("ETHUSDT", new BigDecimal("1886.36")));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("crypto-resolve 캐시 — Optional<ResolvedCoin>이 그대로 돌아온다")
    void roundTripsResolvedCoin() {
        JacksonJsonRedisSerializer<Optional<ResolvedCoin>> serializer =
                CacheConfig.serializer(new TypeReference<Optional<ResolvedCoin>>() {});
        Optional<ResolvedCoin> original = Optional.of(new ResolvedCoin("BNB", "비앤비"));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("@Cacheable을 단 캐시는 전부 CacheConfig에 등록돼 있다")
    void configuresEveryDeclaredCache() {
        Set<String> declared = Stream.of(
                        BinanceApi.class, UpbitApi.class, CryptoResolver.class, StockResolver.class,
                        StockPriceApi.class, MarketIndexApi.class, FmpApi.class,
                        FrankfurterFxClient.class, KeximFxClient.class,
                        FeedFetcher.class, HackerNewsApi.class, RelevanceScorer.class,
                        QueryTranslator.class, TranslationService.class)
                .flatMap(type -> cacheNamesOf(type).stream())
                .collect(Collectors.toSet());

        assertThat(configuredCacheNames())
                .as("등록이 빠지면 Redis 기본값(JDK 직렬화·무기한)으로 떨어져 레코드를 담는 순간 "
                        + "예외가 난다. 실제로 binance-price가 그 상태로 배포됐다")
                .containsAll(declared);
    }

    /** {@code CacheConfig}가 실제로 등록한 이름. TTL 값은 여기서 보지 않는다. */
    private static Set<String> configuredCacheNames() {
        RedisCacheManagerBuilder builder = RedisCacheManager.builder(new LettuceConnectionFactory());
        new CacheConfig().cacheCustomizer(propertiesWithTtl()).customize(builder);
        return builder.getConfiguredCaches();
    }

    /** 캐시 설정만 보므로 나머지 묶음은 채우지 않는다. TTL 값 자체는 무엇이든 상관없다. */
    private static EconomyHelperProperties propertiesWithTtl() {
        Duration any = Duration.ofMinutes(1);
        return new EconomyHelperProperties(null, null, null,
                new CacheTtl(any, any, any, any, any, any, any, any, any, any, any, any, any, any),
                null);
    }

    @Test
    @DisplayName("캐시 이름 하나에 타입 하나 — 지수를 stock-price에 섞으면 캐시 히트에서만 터진다")
    void indexDoesNotShareStockPriceCache() {
        assertThat(cacheNamesOf(MarketIndexApi.class))
                .as("stock-price는 List<StockPrice>로 역직렬화하도록 못 박혀 있다. "
                        + "MarketIndex를 같은 이름에 넣으면 쓰기는 되고 두 번째 조회에서 깨진다")
                .doesNotContainAnyElementsOf(cacheNamesOf(StockPriceApi.class));
    }

    private static Set<String> cacheNamesOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getAnnotation(Cacheable.class))
                .filter(Objects::nonNull)
                .flatMap(cacheable -> Arrays.stream(cacheable.cacheNames()))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("사람이 읽을 수 있는 JSON으로 저장한다 — 타입 정보를 섞지 않는다")
    void writesPlainJson() {
        JacksonJsonRedisSerializer<Translation> serializer =
                CacheConfig.serializer(new TypeReference<Translation>() {});

        String raw = new String(serializer.serialize(Translation.of("제목", "본문")),
                StandardCharsets.UTF_8);

        assertThat(raw).startsWith("{").contains("제목").doesNotContain("@class");
    }
}
