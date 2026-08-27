package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.KisIndex;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.config.EconomyHelperProperties.WeatherLocation;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>진짜 {@code application.yml}이 진짜로 붙는지</b> 본다.
 *
 * <p><b>왜 필요한가.</b> 이 레코드를 쓰는 테스트 여섯이 전부 {@code new}로 만들어
 * <b>바인딩을 통째로 건너뛴다</b>. 그런데 잘못 붙은 prefix는 예외가 아니라 {@code null}이라
 * {@code ./gradlew test}에 안 잡힌다 — 이 저장소는 그 함정에 이미 한 번 물렸다(한글 Map 키가
 * relaxed binding에 걸러져 별칭 표가 조용히 사라졌다).
 *
 * <p>그래서 여기서 보는 것은 값이 <b>맞는지</b>가 아니라 <b>도달했는지</b>다. 특히
 * relaxed binding이 걸러내는 문자들({@code ^}·한글)이 살아서 오는지가 요점이다 —
 * 그것 때문에 {@code us-symbols}·{@code us-indices}·{@code locations}가 Map이 아니라 List다.
 *
 * <p>Redis를 띄우지 않는다. 캐시 매니저만 꺼도 바인딩은 그대로 일어난다.
 */
@SpringBootTest
class EconomyHelperPropertiesTest {

    @DynamicPropertySource
    static void noRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired EconomyHelperProperties properties;

    @Test
    @DisplayName("매체 열거형의 모든 값에 피드가 붙는다 — 하나라도 빠지면 그 매체가 조용히 사라진다")
    void bindsAFeedForEveryNewsSource() {
        // 개수를 여기 적지 않는다 — 매체를 더할 때 고칠 곳이 늘면 반드시 낡는다.
        // 지키려는 것은 "열거형과 yml이 어긋나지 않는다"이지 그 값이 몇이냐가 아니다
        assertThat(properties.feeds())
                .as("매체는 일곱이지만 항목은 여덟이다 — Investing.com이 본 섹션과 암호화폐를 함께 단다")
                .containsOnlyKeys(NewsSource.values());
        assertThat(properties.feeds().get(NewsSource.AP).url())
                .as("AP는 공식 RSS가 없어 구글 뉴스 검색 피드를 프록시로 쓴다")
                .contains("news.google.com");
    }

    @Test
    @DisplayName("피드 주소 끝에 슬래시가 없다 — 308을 부르고 그 매체가 조용히 0건이 된다")
    void feedUrlsDoNotEndWithASlash() {
        // 실측(2026-08-27): CoinDesk를 `.../outboundfeeds/rss/`로 적었더니 308이 왔고,
        // 우리 RestClient는 리다이렉트를 안 따라가 15바이트 `Redirecting...`을 파싱하다
        // SAXParseException으로 끝났다 — 그 매체가 통째로 빠졌다. curl에 -L을 붙이면
        // 200으로 보여서 설정만 읽고는 알 수 없다
        assertThat(properties.feeds().values())
                .allSatisfy(feed -> assertThat(feed.url()).doesNotEndWith("/"));
    }

    @Test
    @DisplayName("코인 전용 피드가 셋 다 붙는다 — 브리핑의 코인 다섯 자리를 이것들이 채운다")
    void bindsEveryCryptoOnlyFeed() {
        // 열거형이 cryptoSection()으로 참이라고 말하는 자리에 실제 주소가 있는지를 본다.
        // 하나라도 비면 코인 풀이 조용히 좁아져 다섯 건이 안 찬다
        assertThat(java.util.Arrays.stream(NewsSource.values())
                        .filter(NewsSource::cryptoSection).toList())
                .allSatisfy(source -> assertThat(properties.feeds().get(source).url())
                        .as("%s의 피드 주소", source)
                        .isNotBlank());
    }

    @Test
    @DisplayName("국내 지수가 이름과 업종코드를 함께 든다 — 출처마다 조회 키가 다르다")
    void bindsDomesticIndicesWithBothKeys() {
        assertThat(properties.digest().indices())
                .extracting(Index::name, Index::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("코스피", "0001"),
                        org.assertj.core.groups.Tuple.tuple("코스닥", "1001"));
    }

    @Test
    @DisplayName("^로 시작하는 심볼이 살아서 온다 — Map 키였으면 relaxed binding이 걸러낸다")
    void keepsCaretSymbolsIntact() {
        assertThat(properties.digest().usSymbols())
                .extracting(UsSymbol::symbol)
                .contains("^IXIC", "^GSPC");
        assertThat(properties.digest().usSymbols())
                .extracting(UsSymbol::name)
                .as("화면에 쓸 한국어 이름도 함께 든다 — 브리핑은 LLM을 타지 않는다")
                .contains("나스닥", "애플");

        assertThat(properties.market().kis().usIndices())
                .extracting(KisIndex::symbol, KisIndex::kisSymbol)
                .as("KIS 심볼 표는 규칙이 없어 이 표가 유일한 길이다")
                .contains(org.assertj.core.groups.Tuple.tuple("^IXIC", "COMP"),
                        org.assertj.core.groups.Tuple.tuple("^DJI", ".DJI"));
    }

    @Test
    @DisplayName("한글 지명이 살아서 온다 — 알람 지역은 좌표를 직접 박는다")
    void keepsKoreanPlaceNamesIntact() {
        assertThat(properties.weather().locations())
                .hasSize(4)
                .extracting(WeatherLocation::name)
                .containsExactly("미금역", "서현역", "잠실역", "삼성중앙역");
        assertThat(properties.weather().locations().get(0).latitude()).isEqualTo(37.35);
    }

    @Test
    @DisplayName("캐시 TTL이 하나도 빠짐없이 붙는다 — 하나라도 null이면 그 캐시가 무기한이 된다")
    void bindsEveryCacheTtl() {
        // 값을 주지 않으면 Redis 캐시는 만료 없이 저장한다. feed가 영구 캐시되면
        // 발송 창 안에서 같은 기사가 되풀이되고, 다음 날까지 어제 기사가 남는다
        assertThat(properties.cacheTtl().feed()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.cacheTtl().geocode()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.cacheTtl().kisQuote()).isEqualTo(Duration.ofMinutes(1));

        // 성분 전부를 훑는다 — 이름을 하나씩 적으면 새 캐시가 늘 때 빠뜨린다
        for (java.lang.reflect.RecordComponent component
                : EconomyHelperProperties.CacheTtl.class.getRecordComponents()) {
            Object value = org.springframework.util.ReflectionUtils.invokeMethod(
                    component.getAccessor(), properties.cacheTtl());
            assertThat(value).as("cache-ttl.%s가 붙지 않았다 — 그 캐시는 만료 없이 저장된다",
                    component.getName()).isNotNull();
        }
    }

    @Test
    @DisplayName("랭킹 가중치 넷과 발송 목록이 붙는다")
    void bindsRankingAndDigestLists() {
        assertThat(properties.ranking().weights().feedRank()).isPositive();
        assertThat(properties.ranking().weights().buzz()).isPositive();
        assertThat(properties.ranking().recencyHalfLife()).isEqualTo(Duration.ofHours(6));

        assertThat(properties.digest().stocks()).containsExactly("005930", "000660");
        assertThat(properties.digest().cryptos()).containsExactly("KRW-BTC", "KRW-ETH", "KRW-USDT");
        assertThat(properties.digest().zone()).isEqualTo("Asia/Seoul");
        assertThat(properties.digest().sentHistoryTtl()).isEqualTo(Duration.ofDays(3));
    }
}
