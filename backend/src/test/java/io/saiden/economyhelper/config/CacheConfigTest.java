package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.crypto.adapter.out.binance.BinanceApi;
import io.saiden.economyhelper.crypto.adapter.out.llm.CryptoResolver;
import io.saiden.economyhelper.crypto.adapter.out.upbit.UpbitApi;
import io.saiden.economyhelper.crypto.domain.BinancePrice;
import io.saiden.economyhelper.crypto.domain.ResolvedCoin;
import io.saiden.economyhelper.crypto.domain.UpbitTicker;
import io.saiden.economyhelper.fx.adapter.out.frankfurter.FrankfurterFxClient;
import io.saiden.economyhelper.fx.adapter.out.kexim.KeximFxClient;
import io.saiden.economyhelper.fx.adapter.out.kis.KisFxClient;
import io.saiden.economyhelper.news.adapter.out.feed.FeedFetcher;
import io.saiden.economyhelper.news.adapter.out.hackernews.HackerNewsApi;
import io.saiden.economyhelper.news.adapter.out.llm.RelevanceScorer;
import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.NewsSource;
import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import io.saiden.economyhelper.stock.adapter.out.datago.EtfPriceApi;
import io.saiden.economyhelper.stock.adapter.out.datago.MarketIndexApi;
import io.saiden.economyhelper.stock.adapter.out.datago.StockPriceApi;
import io.saiden.economyhelper.stock.adapter.out.fmp.FmpApi;
import io.saiden.economyhelper.stock.adapter.out.fmp.FmpUsOutlookClient;
import io.saiden.economyhelper.stock.adapter.out.kis.KisDomesticOutlookClient;
import io.saiden.economyhelper.stock.adapter.out.kis.KisMasterClient;
import io.saiden.economyhelper.stock.adapter.out.kis.KisStockApi;
import io.saiden.economyhelper.stock.adapter.out.llm.StockResolver;
import io.saiden.economyhelper.stock.adapter.out.polygon.PolygonDividendClient;
import io.saiden.economyhelper.stock.domain.StockOutlook;
import io.saiden.economyhelper.stock.domain.StockSource;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.translate.adapter.out.cache.SpringTranslationCache;
import io.saiden.economyhelper.translate.adapter.out.llm.QueryTranslator;
import io.saiden.economyhelper.translate.domain.Translation;
import io.saiden.economyhelper.weather.adapter.out.accu.AccuLocationApi;
import io.saiden.economyhelper.weather.adapter.out.accu.AccuWeatherClient;
import io.saiden.economyhelper.weather.adapter.out.kma.KmaWeatherClient;
import io.saiden.economyhelper.weather.adapter.out.llm.WeatherResolver;
import io.saiden.economyhelper.weather.adapter.out.openmeteo.GeocodingApi;
import io.saiden.economyhelper.weather.adapter.out.openmeteo.OpenMeteoArchiveClient;
import io.saiden.economyhelper.weather.adapter.out.openmeteo.OpenMeteoForecastClient;
import io.saiden.economyhelper.weather.adapter.out.openmeteo.OpenMeteoHourlyClient;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.HalfDay;
import io.saiden.economyhelper.weather.domain.SkyCondition;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
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
import org.springframework.cache.annotation.Caching;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.cache.RedisCacheManager;
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
                new BinancePrice("BTCUSDT", new Price(new BigDecimal("63703.69")),
                        new PercentChange(new BigDecimal("-1.451"))),
                new BinancePrice("ETHUSDT", new Price(new BigDecimal("1886.36")), null));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("crypto-price 캐시 — List<UpbitTicker>가 값 객체·시각까지 그대로 돌아온다")
    void roundTripsUpbitTickers() {
        JacksonJsonRedisSerializer<List<UpbitTicker>> serializer =
                CacheConfig.serializer(new TypeReference<List<UpbitTicker>>() {});
        List<UpbitTicker> original = List.of(
                new UpbitTicker("KRW-ETH", new Price(new BigDecimal("3117000.0")),
                        new BigDecimal("194497300539.0"), new PercentChange(new BigDecimal("0.64578624")),
                        Instant.ofEpochMilli(1787186239692L)),
                new UpbitTicker("KRW-XYZ", null, null, null, null));

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
    @DisplayName("전망 캐시 둘 — StockOutlook이 중첩된 배당까지 그대로 돌아온다")
    void roundTripsStockOutlook() {
        // ⚠️ 배당이 **중첩 레코드**라 담을 때는 넘어가고 읽을 때 깨지는 자리다. 그리고 레코드의
        //    equals는 BigDecimal.equals라 **자릿수까지** 본다 — 0.25가 0.250으로 돌아오면 여기서 걸린다
        JacksonJsonRedisSerializer<io.saiden.economyhelper.stock.domain.StockOutlook> serializer =
                CacheConfig.serializer(
                        new TypeReference<io.saiden.economyhelper.stock.domain.StockOutlook>() {});
        io.saiden.economyhelper.stock.domain.StockOutlook original =
                new io.saiden.economyhelper.stock.domain.StockOutlook(
                        java.time.LocalDate.of(2026, 10, 29),
                        new io.saiden.economyhelper.shared.domain.Price(new java.math.BigDecimal("340.72")),
                        io.saiden.economyhelper.stock.domain.StockOutlook.Dividend.row(
                                java.time.LocalDate.of(2026, 9, 10),
                                java.time.LocalDate.of(2026, 10, 1),
                                new java.math.BigDecimal("0.25")),
                        io.saiden.economyhelper.stock.domain.StockSource.FMP, NOW);

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    /**
     * ⚠️ <b>값 객체({@code Price}·{@code PercentChange})가 들어와도 캐시 JSON은 전과 같다.</b>
     * 아래 문자열은 필드가 {@code BigDecimal}이던 때의 모양 그대로다 — 맨 숫자다.
     * 기본 매핑이면 {@code "price":{"value":239500}}이 되어 이미 담긴 항목을 못 읽으므로,
     * {@code ValueObjectModule}이 맨 숫자로 쓰고 읽는다. 그래서 판 번호를 올리지 않았다.
     * 여기가 깨지면 모듈이 빠졌거나 모양이 바뀐 것이다 — 그때는 판을 올려야 한다.
     *
     * <p>{@code "empty"}는 {@code isEmpty()}를 Jackson이 속성으로 읽은 것이다 — 전부터 담기던 모양이고
     * 읽을 때는 무시된다.
     */
    @Test
    @DisplayName("kis-quote 캐시 — StockQuote의 가격·등락률이 전처럼 맨 숫자로 읽히고 같은 바이트로 쓰인다")
    void stockQuoteKeepsTheBareNumberShape() {
        assertSameBytes(new TypeReference<io.saiden.economyhelper.stock.domain.StockQuote>() {},
                "{\"name\":\"삼성전자\",\"price\":239500,\"changePercent\":-1.26,\"currency\":\"KRW\","
                        + "\"market\":\"DOMESTIC\",\"source\":\"KIS\",\"at\":\"2026-08-11T00:00:00Z\","
                        + "\"realtime\":true}");
        // 등락률을 모르는 값 — null은 null로 남는다(0%로 채우지 않는다)
        assertSameBytes(new TypeReference<io.saiden.economyhelper.stock.domain.StockQuote>() {},
                "{\"name\":\"나스닥\",\"price\":26588.12,\"changePercent\":null,\"currency\":\"NONE\","
                        + "\"market\":\"US\",\"source\":\"KIS\",\"at\":\"2026-08-11T00:00:00Z\","
                        + "\"realtime\":true}");
    }

    @Test
    @DisplayName("fx 캐시 셋 — FxRate의 환율·등락률이 전처럼 맨 숫자로 읽히고 같은 바이트로 쓰인다")
    void fxRateKeepsTheBareNumberShape() {
        assertSameBytes(new TypeReference<io.saiden.economyhelper.fx.domain.FxRate>() {},
                "{\"base\":\"USD\",\"quote\":\"KRW\",\"rate\":1412.17,\"changePercent\":0.22,"
                        + "\"source\":\"KIS\",\"asOf\":\"2026-08-11T00:00:00Z\",\"live\":true}");
        assertSameBytes(new TypeReference<io.saiden.economyhelper.fx.domain.FxRate>() {},
                "{\"base\":\"USD\",\"quote\":\"KRW\",\"rate\":1415.00,\"changePercent\":null,"
                        + "\"source\":\"KEXIM\",\"asOf\":\"2026-08-11T00:00:00Z\",\"live\":false}");
    }

    @Test
    @DisplayName("전망 캐시 — StockOutlook의 목표가가 전처럼 맨 숫자로 읽히고 같은 바이트로 쓰인다")
    void stockOutlookKeepsTheBareNumberShape() {
        io.saiden.economyhelper.stock.domain.StockOutlook back = assertSameBytes(
                new TypeReference<io.saiden.economyhelper.stock.domain.StockOutlook>() {},
                "{\"earningsDate\":\"2026-10-29\",\"targetPrice\":340.72,\"dividend\":{\"recordDate\":\"2026-09-10\","
                        + "\"payDate\":\"2026-10-01\",\"amount\":0.25,\"past\":false,\"empty\":false},"
                        + "\"source\":\"FMP\",\"at\":\"2026-08-11T00:00:00Z\",\"empty\":false}");
        assertThat(back.targetPrice().value()).isEqualByComparingTo("340.72");
        // 목표가가 없는 전망 — 국내 ETF가 늘 이 모양이다
        assertSameBytes(new TypeReference<io.saiden.economyhelper.stock.domain.StockOutlook>() {},
                "{\"earningsDate\":null,\"targetPrice\":null,\"dividend\":null,\"source\":\"KIS\","
                        + "\"at\":\"2026-08-11T00:00:00Z\",\"empty\":true}");
    }

    /** 전 모양의 JSON을 새 타입으로 읽고, 다시 쓰면 <b>같은 바이트</b>가 나오는지. */
    private static <T> T assertSameBytes(TypeReference<T> type, String legacyJson) {
        JacksonJsonRedisSerializer<T> serializer = CacheConfig.serializer(type);
        T read = serializer.deserialize(legacyJson.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(serializer.serialize(read), StandardCharsets.UTF_8)).isEqualTo(legacyJson);
        return read;
    }

    @Test
    @DisplayName("전망 캐시 — 빈 값도 그대로 돌아온다. 그것이 값이라 담기는 자리다")
    void roundTripsAnEmptyStockOutlook() {
        JacksonJsonRedisSerializer<io.saiden.economyhelper.stock.domain.StockOutlook> serializer =
                CacheConfig.serializer(
                        new TypeReference<io.saiden.economyhelper.stock.domain.StockOutlook>() {});
        io.saiden.economyhelper.stock.domain.StockOutlook original =
                io.saiden.economyhelper.stock.domain.StockOutlook.none(
                        io.saiden.economyhelper.stock.domain.StockSource.KIS, NOW);

        io.saiden.economyhelper.stock.domain.StockOutlook back =
                serializer.deserialize(serializer.serialize(original));

        assertThat(back).isEqualTo(original);
        assertThat(back.isEmpty()).as("빈 값으로 돌아와야 화면이 그 블록을 안 적는다").isTrue();
    }

    @Test
    @DisplayName("weather 캐시 — Weather가 그대로 돌아온다 (LocalDate 포함)")
    void roundTripsWeather() {
        JacksonJsonRedisSerializer<io.saiden.economyhelper.weather.domain.Weather> serializer =
                CacheConfig.serializer(
                        new TypeReference<io.saiden.economyhelper.weather.domain.Weather>() {});
        var original = new io.saiden.economyhelper.weather.domain.Weather(
                seongnam(),
                java.util.List.of(io.saiden.economyhelper.weather.domain.Weather.Daily.withChance(
                        java.time.LocalDate.of(2026, 8, 17),
                        io.saiden.economyhelper.weather.domain.SkyCondition.CLOUDY,
                        new BigDecimal("18.2"), new BigDecimal("29.6"), 20)),
                io.saiden.economyhelper.weather.domain.WeatherSource.ACCU_WEATHER,
                // 강수 줄만 다른 곳에서 온 날 — 이 칸이 비어 돌아오면 화면이 출처를 한 줄만 적어
                // 「AccuWeather가 준 적 없는 강수확률을 AccuWeather라고 적는」 상태가 된다
                io.saiden.economyhelper.weather.domain.WeatherSource.OPEN_METEO);

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
                List<io.saiden.economyhelper.weather.domain.HalfDay>>> serializer =
                CacheConfig.serializer(new TypeReference<java.util.Map<java.time.LocalDate,
                        List<io.saiden.economyhelper.weather.domain.HalfDay>>>() {});
        java.time.LocalDate day = java.time.LocalDate.of(2026, 8, 22);
        var original = java.util.Map.of(day, List.of(
                io.saiden.economyhelper.weather.domain.HalfDay.withChance(
                        java.time.LocalTime.of(12, 0), java.time.LocalTime.of(18, 0),
                        io.saiden.economyhelper.weather.domain.SkyCondition.DRIZZLE, 90),
                // 지나간 날의 모양도 함께 본다 — 확률 대신 강수량이 찬 토막이다
                io.saiden.economyhelper.weather.domain.HalfDay.withAmount(
                        java.time.LocalTime.of(20, 0), java.time.LocalTime.of(21, 0),
                        io.saiden.economyhelper.weather.domain.SkyCondition.RAIN,
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
        JacksonJsonRedisSerializer<Optional<io.saiden.economyhelper.weather.domain.GeoLocation>>
                serializer = CacheConfig.serializer(
                        new TypeReference<Optional<
                                io.saiden.economyhelper.weather.domain.GeoLocation>>() {});
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
                io.saiden.economyhelper.weather.domain.ResolvedPlace>> serializer =
                CacheConfig.serializer(new TypeReference<Optional<
                        io.saiden.economyhelper.weather.domain.ResolvedPlace>>() {});
        var original = Optional.of(
                new io.saiden.economyhelper.weather.domain.ResolvedPlace(
                        "성남", "KR", null, null, null, 1, 7));

        assertThat(serializer.deserialize(serializer.serialize(original))).isEqualTo(original);
    }

    private static io.saiden.economyhelper.weather.domain.GeoLocation seongnam() {
        return new io.saiden.economyhelper.weather.domain.GeoLocation(
                "성남시", "대한민국", 37.3851167, 127.1232944, java.time.ZoneId.of("Asia/Seoul"));
    }

    /** {@code @Cacheable}을 다는 클래스 전부. 빠지면 {@link #everyCacheableClassIsWatched}가 잡는다. */
    private static final List<Class<?>> CACHEABLE_TYPES = List.of(
            BinanceApi.class, UpbitApi.class, CryptoResolver.class, StockResolver.class,
            StockPriceApi.class, EtfPriceApi.class, MarketIndexApi.class, FmpApi.class,
            FrankfurterFxClient.class, KeximFxClient.class, KisFxClient.class,
            KisStockApi.class, KisMasterClient.class,
            KisDomesticOutlookClient.class, FmpUsOutlookClient.class,
            io.saiden.economyhelper.stock.adapter.out.polygon.PolygonDividendClient.class,
            FeedFetcher.class, HackerNewsApi.class, RelevanceScorer.class,
            QueryTranslator.class, SpringTranslationCache.class,
            OpenMeteoForecastClient.class, OpenMeteoArchiveClient.class,
            AccuWeatherClient.class, AccuLocationApi.class,
            GeocodingApi.class, WeatherResolver.class, KmaWeatherClient.class,
            OpenMeteoHourlyClient.class);


    /**
     * <b>캐시는 메서드에 직접 붙은 {@code @Cacheable}로만 선언한다 — 그것이 규칙이다.</b>
     *
     * <p>⚠️ <b>이 테스트가 없으면 발견 범위와 정책 범위가 갈린다.</b> 감시 목록 검사는 스프링의
     * 병합 규칙으로 넓게 찾는데({@link #cacheableSomewhere}), <b>정책 검사 둘</b>
     * (빈 값 거절 · 등록 누락)은 {@code method.getAnnotation(Cacheable.class)}로 좁게 본다.
     * 그 틈으로 {@code @Caching(cacheable = @Cacheable(…))}(unless 없음)이 빠져나가
     * 빈 맵이 캐시되고 등록 안 된 이름도 안 걸린다.
     *
     * <p>정책 검사에 스프링의 캐시 연산 해석을 넣는 대신 <b>지원하지 않는 형태를 거절</b>한다 —
     * 이 저장소에 그 형태가 없고, 해석을 두 곳에 두면 그 둘이 또 갈린다. {@code @Caching}이
     * 정말 필요해지는 날에는 이 테스트가 먼저 실패해 정책 검사도 넓히라고 말해 준다.
     */
    @Test
    @DisplayName("캐시는 메서드의 @Cacheable로만 선언한다 — @Caching·클래스 수준은 정책 검사가 못 보고 지나간다")
    void cachingIsDeclaredOnlyOnMethods() throws IOException {
        List<String> unsupported = new java.util.ArrayList<>();
        for (Class<?> type : compiledMainClasses()) {
            if (cacheableSomewhere(type)) {
                unsupported.add(type.getName() + " — 클래스 수준 캐시");
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                // ⚠️ 직접 @Cacheable 이 있는지와 **무관하게** @Caching을 거절한다. 「직접 것이
                //    없을 때만」으로 좁히면 둘을 **함께** 단 메서드가 빠져나가고, 그 @Caching 안의
                //    cacheable은 정책 검사가 여전히 못 본다
                if (AnnotatedElementUtils.hasAnnotation(method, Caching.class)) {
                    unsupported.add(type.getSimpleName() + "." + method.getName()
                            + " — @Caching은 정책 검사가 못 본다");
                } else if (cacheableSomewhere(method)
                        && method.getAnnotation(Cacheable.class) == null) {
                    unsupported.add(type.getSimpleName() + "." + method.getName()
                            + " — 메타 애너테이션으로만 캐시된다(정책 검사가 못 본다)");
                }
            }
        }

        assertThat(unsupported).as("정책 검사가 볼 수 없는 캐시 선언").isEmpty();
    }

    /** 컴파일된 main 클래스 — 초기화하지 않고 로드한다. */
    private List<Class<?>> compiledMainClasses() throws IOException {
        Path classes = Path.of("build/classes/java/main");
        List<Class<?>> loaded = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString()
                        .replace(".class", "").replace(java.io.File.separatorChar, '.');
                try {
                    loaded.add(Class.forName(name, false, getClass().getClassLoader()));
                } catch (Throwable ignored) {
                    // 로드할 수 없는 것은 캐시도 들 수 없다
                }
            }
        }
        assertThat(loaded).as("읽은 컴파일된 main 클래스 — 0개면 경로가 틀렸다").hasSizeGreaterThan(100);
        return loaded;
    }

    /** 스프링이 이 자리에 캐시를 적용하는가 — 직접·클래스 수준·{@code @Caching}·메타를 다 본다. */
    private static boolean cacheableSomewhere(java.lang.reflect.AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(element, Cacheable.class)
                || AnnotatedElementUtils.hasAnnotation(element, Caching.class);
    }

    /**
     * <b>{@link #CACHEABLE_TYPES}가 손으로 유지되는 목록이라 낡는다 — 그래서 목록 자체를 감시한다.</b>
     *
     * <p>빠지면 그 캐시는 위 두 그물(빈 값 거절·등록 누락)을 통째로 빠져나간다 —
     * {@code OpenMeteoHourlyClient}({@code precipitation-hours})가 실제로 그랬다.
     *
     * <p>⚠️ <b>애너테이션이 없는데 일부러 목록에 있는 것은 정상이다</b>({@code SpringTranslationCache} —
     * {@code CacheManager}로 직접 읽고 쓴다). 그래서 한쪽 방향만 단언한다: <b>붙은 것은 반드시
     * 목록에 있어야 하고</b>, 목록에 더 있는 것은 상관없다.
     *
     * <p>⚠️ <b>소스 정규식이 아니라 컴파일된 클래스로 본다</b> — {@code @Deprecated @Cacheable} ·
     * 완전수식 · 사이에 낀 블록 주석 · {@code @ Cacheable}이 전부 유효한 자바라 정규식으로는 끝이 없다.
     * 비교도 이름이 아니라 {@link Class} 객체로 한다 — 다른 패키지의 동명 클래스를 「감시 중」으로
     * 오인하지 않게. 읽은 클래스 수를 먼저 단언한다(0개면 통과와 구분되지 않는다).
     */
    @Test
    @DisplayName("@Cacheable을 단 클래스가 전부 감시 목록에 있다 — 빠지면 그 캐시는 어느 그물에도 안 걸린다")
    void everyCacheableClassIsWatched() throws IOException {
        Set<Class<?>> watched = Set.copyOf(CACHEABLE_TYPES);

        // ⚠️ 스프링이 캐시를 적용하는 범위는 「메서드에 직접 붙은 @Cacheable」보다 넓다 —
        //    클래스 수준 · @Caching(cacheable = ...) · 메타 애너테이션도 적용된다. 여기서는
        //    넓게 찾고, 그 넓은 것이 정책 검사를 빠져나가지 못하게 막는 것은
        //    cachingIsDeclaredOnlyOnMethods가 한다
        List<String> unwatched = compiledMainClasses().stream()
                .filter(type -> cacheableSomewhere(type)
                        || Arrays.stream(type.getDeclaredMethods())
                                .anyMatch(CacheConfigTest::cacheableSomewhere))
                .filter(type -> !watched.contains(type))
                .map(Class::getName)
                .toList();

        assertThat(unwatched).as("@Cacheable을 달았는데 CACHEABLE_TYPES에 없는 클래스").isEmpty();
    }

    @Test
    @DisplayName("Optional을 돌려주는 @Cacheable은 빈 값을 unless로 막는다 — 안 막으면 빈 답이 조회 실패로 보인다")
    void optionalCachesRefuseToStoreEmpty() {
        // ⚠️ 스프링은 Optional을 벗겨 넣는다. 빈 Optional은 null이 되고 disableCachingNullValues는
        //    그것을 「안 담는」 것이 아니라 IllegalArgumentException으로 **거절**한다 — 그 예외가
        //    호출자까지 올라가 「전망 조회 실패」로 찍혔다(2026-08-28 실물 감사, ETF 전부).
        //    브레이커는 안쪽이라 성공을 봤지만 값은 영영 캐시되지 않아 조회마다 KIS 1초·FMP 2회를 다시 썼다
        List<String> unguarded = CACHEABLE_TYPES.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getAnnotation(Cacheable.class) != null)
                .filter(method -> method.getReturnType() == java.util.Optional.class)
                .filter(method -> !method.getAnnotation(Cacheable.class).unless().contains("#result == null"))
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .toList();

        assertThat(unguarded).as("unless=\"#result == null\"이 없는 Optional 캐시").isEmpty();
    }

    /** 빈 컬렉션·맵을 거절하는 유일한 올바른 표현. 부정형이 섞이면 동작이 뒤집힌다. */
    private static final String EMPTY_GUARD = "#result.isEmpty()";

    /** 빈 것을 거르는가 — 글자 그대로, 또는 그 뒤에 {@code or}로 다른 거름이 붙은 것(앞에 {@code !}가 붙으면 반대다). */
    private static boolean guardsEmpty(String unless) {
        return unless.equals(EMPTY_GUARD) || unless.startsWith(EMPTY_GUARD + " or ");
    }

    /**
     * <b>빈 것을 일부러 담는 캐시</b> — 여기 적힌 것만 예외다.
     *
     * <p>공공데이터포털 {@code searchBy*} 넷은 열흘을 되짚어 빈 것이 「없다」이고 한 시간은
     * 안정된 값이다. 주식 API는 ETF 코드에 <b>구조적으로 늘 0건</b>이어서, 안 담으면 조회마다
     * 되짚기 열 번을 다시 쓴다.
     *
     * <p>⚠️ <b>{@code OpenMeteoHourlyClient.halves}는 여기 넣지 않는다.</b> 마른 날도
     * 반나절이 만들어지므로({@code HalfDay.dry}) 빈 map은 「마른 기간」이 아니라 <b>쓸 것을 하나도
     * 못 받았다</b>는 뜻이다(200에 빈 본문·파싱 전부 실패).
     */
    private static final Set<String> CACHES_THAT_KEEP_EMPTY = Set.of(
            "StockPriceApi.searchByName", "StockPriceApi.searchByCode",
            "EtfPriceApi.searchByName", "EtfPriceApi.searchByCode");

    @Test
    @DisplayName("컬렉션·맵을 돌려주는 @Cacheable은 빈 것을 담지 않는다 — 담으면 상대의 한순간 빈손이 TTL만큼 굳는다")
    void emptyCollectionsAndMapsAreNotCached() {
        // ⚠️ 일봉 캐시 넷이 그 상태였다 — KIS가 0.00만 준 순간의 빈 목록이 12시간 남아
        //    회복 뒤에도 차트가 안 붙었다
        // ⚠️ List만이 아니라 Collection과 Map 둘을 본다 — precipitation-hours(Map)가 List 그물을 빠져나갔다
        List<String> unguarded = CACHEABLE_TYPES.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getAnnotation(Cacheable.class) != null)
                .filter(method -> Collection.class.isAssignableFrom(method.getReturnType())
                        || Map.class.isAssignableFrom(method.getReturnType()))
                // ⚠️ contains("isEmpty()")로 보면 unless="!#result.isEmpty()"도 통과하는데
                //    그건 **반대로** 동작한다(빈 것만 담는다). 글자까지 견준다
                // 빈 것 거름 뒤에 「or 다른 거름」이 붙는 것은 받는다 — 관련도 캐시가 대체값까지 거른다
                .filter(method -> !guardsEmpty(method.getAnnotation(Cacheable.class).unless().strip()))
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .filter(name -> !CACHES_THAT_KEEP_EMPTY.contains(name))
                .toList();

        assertThat(unguarded).as("unless=\"#result.isEmpty()\"가 없는 목록 캐시").isEmpty();
    }

    @Test
    @DisplayName("@Cacheable을 단 캐시는 전부 CacheConfig에 등록돼 있다")
    void configuresEveryDeclaredCache() {
        Set<String> declared = CACHEABLE_TYPES.stream()
                .flatMap(type -> cacheNamesOf(type).stream())
                .collect(Collectors.toCollection(java.util.HashSet::new));

        // ⚠️ 애너테이션이 없는 캐시는 위 훑기에 안 걸린다. translation은 CacheManager로 직접
        // 읽고 쓰는데(캐시 미스만 골라 묶어 번역하려면 그래야 한다), 그렇다고 감시에서 빠지면
        // 등록 누락이 조용히 지나간다 — 이름을 직접 더해 그물을 유지한다
        declared.add(SpringTranslationCache.CACHE);

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
        //    geocode 30일 · resolve 7일 동안 옛 답이 계속 나간다(docs/design.md 4.2 「긴 TTL 캐시가 고침보다 오래 산다」 — /weather 미금).
        //    누가 "이름이 지저분하다"고 접미사를 떼면 그 사고가 그대로 돌아온다
        assertThat(List.of(CacheNames.GEOCODE, CacheNames.WEATHER_RESOLVE,
                        CacheNames.STOCK_RESOLVE, CacheNames.CRYPTO_RESOLVE))
                .allSatisfy(name -> assertThat(name)
                        .as("파생 규칙이 바뀌는 캐시다 — 판 번호를 떼면 옛 답을 지울 수단이 없다")
                        .matches(".+-v\\d+$"));

        // 반대쪽도 못 박는다. 시세는 수명이 초·분이라 규칙을 고쳐도 한 숨에 스스로 낫는다 —
        // 여기에 판을 매기면 노브만 늘고 배포마다 올려야 할 것이 는다.
        // 예외는 담는 모양이 바뀐 경우뿐이다(코인 시세 둘·환율 셋 — 롤링 배포 중 옛 모양이 적중으로 읽힌다)
        assertThat(List.of(CacheNames.CRYPTO_PRICE, CacheNames.BINANCE_PRICE,
                        CacheNames.FX, CacheNames.FX_KEXIM, CacheNames.FX_KIS))
                .allSatisfy(name -> assertThat(name)
                        .as("v2는 담는 모양이 바뀐 표시다 — 떼면 옛 모양이 적중으로 읽힌다")
                        .matches(".+-v\\d+$"));
        assertThat(List.of(CacheNames.KIS_QUOTE, CacheNames.WEATHER))
                .allSatisfy(name -> assertThat(name)
                        .as("상대가 준 값이고 수명이 짧다 — 판을 매길 이유가 없다")
                        .doesNotMatch(".+-v\\d+$"));
    }

    @Test
    @DisplayName("전망 캐시 셋은 그 시장의 자정에 끝난다 — 담긴 값이 「오늘」로 이미 잘려 있다")
    void outlookCachesEndAtTheMarketsMidnight() {
        RedisCacheManagerBuilder builder = RedisCacheManager.builder(new LettuceConnectionFactory());
        new CacheConfig().cacheCustomizer(propertiesWithTtl()).customize(builder);

        assertThat(List.of(CacheNames.KIS_OUTLOOK, CacheNames.US_OUTLOOK, CacheNames.US_DIVIDEND))
                .allSatisfy(name -> assertThat(builder.getCacheConfigurationFor(name).orElseThrow().getTtlFunction())
                        .as(name).isInstanceOf(UntilMidnightTtl.class));
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
