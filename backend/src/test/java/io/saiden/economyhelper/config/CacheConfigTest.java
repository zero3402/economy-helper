package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.CryptoResolver;
import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import io.saiden.economyhelper.market.StockResolver;
import io.saiden.economyhelper.market.binance.BinanceApi;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.fmp.FmpApi;
import io.saiden.economyhelper.market.kis.KisStockApi;
import io.saiden.economyhelper.market.frankfurter.FrankfurterFxClient;
import io.saiden.economyhelper.market.kexim.KeximFxClient;
import io.saiden.economyhelper.market.kis.KisFxClient;
import io.saiden.economyhelper.market.upbit.UpbitApi;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.news.rank.HackerNewsApi;
import io.saiden.economyhelper.news.rank.RelevanceScorer;
import io.saiden.economyhelper.translate.QueryTranslator;
import io.saiden.economyhelper.translate.Translation;
import io.saiden.economyhelper.translate.TranslationService;
import io.saiden.economyhelper.market.weather.WeatherResolver;
import io.saiden.economyhelper.market.weather.accu.AccuLocationApi;
import io.saiden.economyhelper.market.weather.accu.AccuWeatherClient;
import io.saiden.economyhelper.market.weather.openmeteo.GeocodingApi;
import io.saiden.economyhelper.market.weather.openmeteo.OpenMeteoArchiveClient;
import io.saiden.economyhelper.market.weather.openmeteo.OpenMeteoForecastClient;
import io.saiden.economyhelper.support.TestProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
                new Article(NewsSource.CNBC, "Oil holds advance", "Oil kept its gains.",
                        "https://example.com/1", NOW, 0),
                // Google News 프록시는 description이 없다
                new Article(NewsSource.AP, "Fed signals rate cut", null,
                        "https://example.com/2", NOW, 1));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("binance-price 캐시 — List<BinancePrice>가 그대로 돌아온다")
    void roundTripsBinancePrices() {
        JacksonJsonRedisSerializer<List<BinancePrice>> serializer =
                CacheConfig.serializer(new TypeReference<List<BinancePrice>>() {});
        List<BinancePrice> original = List.of(
                new BinancePrice("BTCUSDT", new BigDecimal("63703.69"), null),
                new BinancePrice("ETHUSDT", new BigDecimal("1886.36"), null));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("crypto-resolve 캐시 — Optional<ResolvedCoin>이 그대로 돌아온다")
    void roundTripsResolvedCoin() {
        JacksonJsonRedisSerializer<Optional<ResolvedCoin>> serializer =
                CacheConfig.serializer(new TypeReference<Optional<ResolvedCoin>>() {});
        Optional<ResolvedCoin> original = Optional.of(new ResolvedCoin("BNB"));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("weather 캐시 — Weather가 그대로 돌아온다 (LocalDate 포함)")
    void roundTripsWeather() {
        JacksonJsonRedisSerializer<io.saiden.economyhelper.market.weather.Weather> serializer =
                CacheConfig.serializer(
                        new TypeReference<io.saiden.economyhelper.market.weather.Weather>() {});
        var original = new io.saiden.economyhelper.market.weather.Weather(
                seongnam(),
                java.util.List.of(io.saiden.economyhelper.market.weather.Weather.Daily.withChance(
                        java.time.LocalDate.of(2026, 8, 17),
                        io.saiden.economyhelper.market.weather.SkyCondition.CLOUDY,
                        new BigDecimal("18.2"), new BigDecimal("29.6"), 20)),
                io.saiden.economyhelper.market.weather.WeatherSource.ACCU_WEATHER,
                // 강수 줄만 다른 곳에서 온 날 — 이 칸이 비어 돌아오면 화면이 출처를 한 줄만 적어
                // 「AccuWeather가 준 적 없는 강수확률을 AccuWeather라고 적는」 상태가 된다
                io.saiden.economyhelper.market.weather.WeatherSource.OPEN_METEO);

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    /**
     * ⚠️ <b>이 저장소에서 맵 키가 {@code String}이 아닌 유일한 캐시다.</b> 나머지
     * ({@code hn-buzz}·{@code relevance})는 전부 {@code Map<String, …>}이라 왕복이 공짜인데,
     * 이쪽만 키가 {@link java.time.LocalDate}다. 키가 문자열로 돌아오면
     * {@code WeatherService}의 {@code spells.containsKey(day.date())}가 <b>조용히 거짓</b>이 되어
     * 예외도 로그도 없이 강수 시각 줄만 사라진다 — 「고쳤는데 여전히 안 나온다」의 교과서적 모양이다.
     */
    @Test
    @DisplayName("precipitation-hours 캐시 — LocalDate 키가 문자열이 되어 돌아오지 않는다")
    void roundTripsPrecipitationHours() {
        JacksonJsonRedisSerializer<java.util.Map<java.time.LocalDate,
                List<io.saiden.economyhelper.market.weather.HalfDay>>> serializer =
                CacheConfig.serializer(new TypeReference<java.util.Map<java.time.LocalDate,
                        List<io.saiden.economyhelper.market.weather.HalfDay>>>() {});
        java.time.LocalDate day = java.time.LocalDate.of(2026, 8, 22);
        var original = java.util.Map.of(day, List.of(
                io.saiden.economyhelper.market.weather.HalfDay.withChance(
                        java.time.LocalTime.of(12, 0), java.time.LocalTime.of(18, 0),
                        io.saiden.economyhelper.market.weather.SkyCondition.DRIZZLE, 90),
                // 지나간 날의 모양도 함께 본다 — 확률 대신 강수량이 찬 토막이다
                io.saiden.economyhelper.market.weather.HalfDay.withAmount(
                        java.time.LocalTime.of(20, 0), java.time.LocalTime.of(21, 0),
                        io.saiden.economyhelper.market.weather.SkyCondition.RAIN,
                        new BigDecimal("3.7"))));

        var restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isEqualTo(original);
        assertThat(restored.containsKey(day))
                .as("키가 LocalDate로 돌아와야 한다 — 문자열이면 조회가 조용히 빗나간다")
                .isTrue();
    }

    @Test
    @DisplayName("geocode 캐시 — ZoneId가 그대로 돌아온다. 틀리면 날짜가 하루 어긋난다")
    void roundTripsGeoLocation() {
        JacksonJsonRedisSerializer<Optional<io.saiden.economyhelper.market.weather.GeoLocation>>
                serializer = CacheConfig.serializer(
                        new TypeReference<Optional<
                                io.saiden.economyhelper.market.weather.GeoLocation>>() {});
        var original = Optional.of(seongnam());

        // ZoneId.of(...)는 실제로 package-private ZoneRegion이라 상위 타입으로 직렬화기를
        // 찾아 돈다 — "되긴 되는" 자리라 못 박아 둔다. 이 값이 틀리면 그 지역의 하루가
        // 우리 달력으로 잘려 날짜가 하루 밀린다
        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("weather-resolve 캐시 — Optional<ResolvedPlace>가 그대로 돌아온다")
    void roundTripsResolvedPlace() {
        JacksonJsonRedisSerializer<Optional<
                io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace>> serializer =
                CacheConfig.serializer(new TypeReference<Optional<
                        io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace>>() {});
        var original = Optional.of(
                new io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace(
                        "성남", "KR", null, null, null, 1, 7));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    private static io.saiden.economyhelper.market.weather.GeoLocation seongnam() {
        return new io.saiden.economyhelper.market.weather.GeoLocation(
                "성남시", "대한민국", 37.3851167, 127.1232944, java.time.ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("@Cacheable을 단 캐시는 전부 CacheConfig에 등록돼 있다")
    void configuresEveryDeclaredCache() {
        Set<String> declared = Stream.of(
                        BinanceApi.class, UpbitApi.class, CryptoResolver.class, StockResolver.class,
                        StockPriceApi.class, MarketIndexApi.class, FmpApi.class,
                        FrankfurterFxClient.class, KeximFxClient.class, KisFxClient.class,
                        KisStockApi.class,
                        FeedFetcher.class, HackerNewsApi.class, RelevanceScorer.class,
                        QueryTranslator.class, TranslationService.class,
                        // 날씨. 여기에 안 적으면 새 캐시를 감시가 아예 안 본다
                        OpenMeteoForecastClient.class, OpenMeteoArchiveClient.class,
                        AccuWeatherClient.class, AccuLocationApi.class,
                        GeocodingApi.class, WeatherResolver.class)
                .flatMap(type -> cacheNamesOf(type).stream())
                .collect(Collectors.toCollection(java.util.HashSet::new));

        // ⚠️ 애너테이션이 없는 캐시는 위 훑기에 안 걸린다. translation은 CacheManager로 직접
        // 읽고 쓰는데(캐시 미스만 골라 묶어 번역하려면 그래야 한다), 그렇다고 감시에서 빠지면
        // 등록 누락이 조용히 지나간다 — 이름을 직접 더해 그물을 유지한다
        declared.add(TranslationService.CACHE);

        assertThat(configuredCacheNames())
                .as("등록이 빠지면 Redis 기본값(JDK 직렬화·무기한)으로 떨어져 레코드를 담는 순간 "
                        + "예외가 난다. 실제로 binance-price가 그 상태로 배포됐다")
                .containsAll(declared);
    }

    @Test
    @DisplayName("우리 규칙이 만든 값을 담는 캐시는 판 번호를 달고 있다 — 없으면 고침이 한 달 뒤에 보인다")
    void versionsTheCachesWhoseValuesWeDerive() {
        // ⚠️ 이 단언이 지키는 것은 관례다. 지오코딩의 후보 선택 규칙과 해석기 셋의 프롬프트는
        //    우리가 고치는 것이고, 고치면 캐시된 답이 곧 옛 답이 된다. 판 번호가 없으면
        //    geocode 30일 · resolve 7일 동안 옛 답이 계속 나간다.
        //    실제로 물렸다: /weather 미금이 고침 뒤에도 'Seongnam, 대한민국'을 답했고
        //    (전라북도 남원시, 분당에서 200km) 그 내내 골든 파일은 초록이었다.
        //    누가 "이름이 지저분하다"고 접미사를 떼면 그 사고가 그대로 돌아온다
        assertThat(List.of(CacheNames.GEOCODE, CacheNames.WEATHER_RESOLVE,
                        CacheNames.STOCK_RESOLVE, CacheNames.CRYPTO_RESOLVE))
                .allSatisfy(name -> assertThat(name)
                        .as("파생 규칙이 바뀌는 캐시다 — 판 번호를 떼면 옛 답을 지울 수단이 없다")
                        .matches(".+-v\\d+$"));

        // 반대쪽도 못 박는다. 시세는 수명이 초·분이라 규칙을 고쳐도 한 숨에 스스로 낫는다 —
        // 여기에 판을 매기면 노브만 늘고 배포마다 올려야 할 것이 는다
        assertThat(List.of(CacheNames.CRYPTO_PRICE, CacheNames.BINANCE_PRICE,
                        CacheNames.KIS_QUOTE, CacheNames.FX, CacheNames.WEATHER))
                .allSatisfy(name -> assertThat(name)
                        .as("상대가 준 값이고 수명이 짧다 — 판을 매길 이유가 없다")
                        .doesNotMatch(".+-v\\d+$"));
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
        return TestProperties.builder().cacheTtl(TestProperties.everyTtl(any)).build();
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
