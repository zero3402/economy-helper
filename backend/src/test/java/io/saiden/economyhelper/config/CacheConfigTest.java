package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.translate.Translation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("사람이 읽을 수 있는 JSON으로 저장한다 — 타입 정보를 섞지 않는다")
    void writesPlainJson() {
        JacksonJsonRedisSerializer<Translation> serializer =
                CacheConfig.serializer(new TypeReference<Translation>() {});

        String raw = new String(serializer.serialize(Translation.of("제목", "본문")),
                StandardCharsets.UTF_8);

        assertThat(raw).startsWith("{").contains("제목").doesNotContain("@class");
    }
}
