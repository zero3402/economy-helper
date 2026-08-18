package io.saiden.economyhelper.config;

import io.saiden.economyhelper.config.EconomyHelperProperties.CacheTtl;
import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.binance.BinanceApi.BinancePrice;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.fmp.FmpApi.FmpQuote;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import io.saiden.economyhelper.market.upbit.UpbitApi.UpbitTicker;
import io.saiden.economyhelper.market.upbit.UpbitMarket;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.translate.Translation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 캐시의 만료 시간과 직렬화 방식을 캐시별로 정한다.
 *
 * <p>둘 다 기본값을 쓰면 안 되는 이유가 있다.
 *
 * <ul>
 *   <li><b>만료</b>: Spring Redis 캐시의 기본 TTL은 <b>무한</b>이다. {@code feed}가 영구
 *       캐시되면 09시와 21시 발송이 같은 기사로 나간다 — 하루 두 번 보내는 의미가 사라진다.
 *   <li><b>직렬화</b>: 기본값인 JDK 직렬화는 {@code Serializable}이 아닌 레코드
 *       ({@link Article}, {@link Translation})를 만나면 첫 캐시 쓰기부터 예외다.
 * </ul>
 *
 * <p>JSON으로 바꾸되 <b>다형 타입 정보(@class)는 쓰지 않는다.</b> 두 가지 이유다.
 * 하나, 그 방식은 {@code List.of(...)}처럼 final인 불변 컬렉션에 타입 정보를 붙이지 못해
 * 쓸 때는 조용히 넘어가고 <b>읽을 때 깨진다</b> — 피드 캐시가 정확히 그 모양이다.
 * 둘, 타입 정보를 켜면 Redis에 쓸 수 있는 쪽이 임의 클래스를 인스턴스화할 수 있게 된다.
 * 캐시마다 담기는 타입이 하나로 정해져 있으므로 그 타입을 직접 못 박는 편이 안전하고 정확하다.
 */
@Configuration
public class CacheConfig {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Bean
    RedisCacheManagerBuilderCustomizer cacheCustomizer(EconomyHelperProperties properties) {
        CacheTtl ttl = properties.cacheTtl();
        return builder -> builder
                .withCacheConfiguration("feed",
                        cache(ttl.feed(), new TypeReference<List<Article>>() {}))
                .withCacheConfiguration("translation",
                        cache(ttl.translation(), new TypeReference<Translation>() {}))
                .withCacheConfiguration("hn-buzz",
                        cache(ttl.buzz(), new TypeReference<Map<String, Integer>>() {}))
                .withCacheConfiguration("query",
                        cache(ttl.query(), new TypeReference<List<String>>() {}))
                // 배치 단위로 캐시한다 — 기사별로 쪼개면 배치가 깨져 Gemini 호출이 늘어난다
                .withCacheConfiguration("relevance",
                        cache(ttl.relevance(), new TypeReference<Map<String, Double>>() {}))
                .withCacheConfiguration("upbit-markets",
                        cache(ttl.upbitMarkets(), new TypeReference<List<UpbitMarket>>() {}))
                .withCacheConfiguration("crypto-price",
                        cache(ttl.cryptoPrice(), new TypeReference<List<UpbitTicker>>() {}))
                // 업비트와 같은 수명이지만 담기는 타입이 달라 crypto-price에 섞을 수 없다
                .withCacheConfiguration("binance-price",
                        cache(ttl.binancePrice(), new TypeReference<List<BinancePrice>>() {}))
                // LLM 해석 결과 — 같은 검색어에 Gemini를 두 번 태우지 않는다
                .withCacheConfiguration("stock-resolve",
                        cache(ttl.stockResolve(), new TypeReference<java.util.Optional<ResolvedStock>>() {}))
                .withCacheConfiguration("crypto-resolve",
                        cache(ttl.cryptoResolve(), new TypeReference<java.util.Optional<ResolvedCoin>>() {}))
                // 전일 종가라 자주 바뀌지 않는다 — 짧게 잡을 이유가 없다
                .withCacheConfiguration("stock-price",
                        cache(ttl.stockPrice(), new TypeReference<List<StockPrice>>() {}))
                // 미국은 현재가라 1분이 상한이다. 하루 250회는 캐시가 아니라 FmpQuotaGuard가 지킨다
                .withCacheConfiguration("us-quote",
                        cache(ttl.usQuote(), new TypeReference<FmpQuote>() {}))
                // 지수는 담기는 타입이 달라 stock-price에 섞을 수 없다. 수명은 같다 — 같은 전일 종가다
                .withCacheConfiguration("market-index",
                        cache(ttl.stockPrice(), new TypeReference<MarketIndex>() {}))
                .withCacheConfiguration("fx",
                        cache(ttl.fx(), new TypeReference<FxRate>() {}))
                // 하루 1,000회 한도를 지키는 실질 방어다 — 1시간이면 하루 최대 24회
                .withCacheConfiguration("fx-kexim",
                        cache(ttl.fxKexim(), new TypeReference<FxRate>() {}))
                // 하루 중에도 움직이는 값이다 — 화면에 '읽은 시각'을 찍으므로 1분이 상한이다
                .withCacheConfiguration("fx-kis",
                        cache(ttl.fxKis(), new TypeReference<FxRate>() {}))
                // 시세와 같은 급이다 — 길게 잡으면 '오늘 날씨'가 어제 예보가 된다
                .withCacheConfiguration("weather",
                        cache(ttl.weather(), new TypeReference<Weather>() {}))
                // 지명의 좌표는 낡지 않는다. 검색 한 번에 조회가 두 번 나가는 것을 막는다
                .withCacheConfiguration("geocode",
                        cache(ttl.geocode(), new TypeReference<java.util.Optional<GeoLocation>>() {}))
                // 좌표에 대응하는 AccuWeather 지점 키도 낡지 않는다. 이게 하루 50회 한도의
                // 실질 방어다 — 없으면 날씨 조회 한 번마다 호출이 두 번 나간다
                .withCacheConfiguration("accu-location",
                        cache(ttl.accuLocation(), new TypeReference<String>() {}))
                // LLM 해석. '내일'을 offsetDays로 받으므로 캐시해도 내일이 고정되지 않는다
                .withCacheConfiguration("weather-resolve",
                        cache(ttl.weatherResolve(),
                                new TypeReference<java.util.Optional<ResolvedPlace>>() {}));
    }

    private static RedisCacheConfiguration cache(Duration ttl, TypeReference<?> type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // null을 캐시하지 않는다 — 수집 실패로 비어 있는 상태가 굳는 것을 막는다
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(serializer(type)));
    }

    static <T> JacksonJsonRedisSerializer<T> serializer(TypeReference<T> type) {
        return new JacksonJsonRedisSerializer<>(MAPPER, MAPPER.constructType(type));
    }
}
