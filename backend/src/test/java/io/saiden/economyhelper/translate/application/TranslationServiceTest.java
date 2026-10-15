package io.saiden.economyhelper.translate.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.infrastructure.llm.GeminiApi;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.translate.adapter.out.cache.SpringTranslationCache;
import io.saiden.economyhelper.translate.adapter.out.llm.GeminiTranslator;
import io.saiden.economyhelper.translate.application.port.out.TranslationCache;
import io.saiden.economyhelper.translate.domain.Translation;
import io.saiden.economyhelper.translate.domain.TranslationRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 폴백 체인을 고정한다.
 *
 * <p>{@code @Cacheable}은 프록시가 있어야 동작하므로 여기서는 검증하지 않는다 —
 * 여기서 확인할 것은 <b>번역이 실패해도 발송이 멈추지 않는다</b>는 점이다.
 */
class TranslationServiceTest {


    @Test
    @DisplayName("Gemini가 성공하면 그 번역을 쓴다")
    void usesGeminiWhenItSucceeds() {
        TranslationService service = service(new FakeGemini(
                Translation.of("한국어 제목", "한국어 본문"), null));

        Translation result = service.translateAll(List.of(article("Oil holds advance", "Oil kept its gains."))).get(0);

        assertThat(result.translated()).isTrue();
        assertThat(result.title()).isEqualTo("한국어 제목");
    }

    @Test
    @DisplayName("429가 나면 원문 그대로 강등한다 — 정보 손실 없이 발송은 계속된다")
    void fallsBackToOriginalOnRateLimit() {
        TranslationService service = service(new FakeGemini(
                null, new RuntimeException("429 Quota exceeded")));

        TranslationRequest article = article("Oil holds advance", "Oil kept its gains.");
        Translation result = service.translateAll(List.of(article)).get(0);

        assertThat(result.translated()).isFalse();
        assertThat(result.title()).isEqualTo("Oil holds advance");
        assertThat(result.body()).isEqualTo("Oil kept its gains.");
    }

    @Test
    @DisplayName("요약문이 없는 기사(AP)는 body가 빈 문자열로 강등된다")
    void handlesArticlesWithoutDescription() {
        TranslationService service = service(new FakeGemini(null, new RuntimeException("실패")));

        Translation result = service.translateAll(List.of(
                new TranslationRequest("https://example.com/r", "Fed signals cut", null))).get(0);

        assertThat(result.title()).isEqualTo("Fed signals cut");
        assertThat(result.body()).isEmpty();
    }

    // --- 묶음 번역: 호출 수가 리미터를 넘기지 않게 하는 장치 ------------------

    @Test
    @DisplayName("여러 건을 Gemini 한 번으로 번역한다 — 건별로 부르면 리미터 끝자락에서 번역이 잘린다")
    void translatesManyArticlesInOneCall() {
        FakeGemini gemini = new FakeGemini(Translation.of("한국어 제목", "한국어 본문"), null);
        TranslationService service = service(gemini);

        List<Translation> results = service.translateAll(List.of(
                article("First", "Body one"), article("Second", "Body two"),
                article("Third", "Body three")));

        assertThat(results).hasSize(3).allSatisfy(t -> assertThat(t.translated()).isTrue());
        assertThat(gemini.calls).as("세 건이어도 Gemini는 한 번이다").isEqualTo(1);
        assertThat(gemini.batched).isEqualTo(3);
    }

    @Test
    @DisplayName("이미 번역한 기사는 묶음에서 뺀다 — 키 단위 캐시가 무료 티어를 아끼는 수단이다")
    void batchesOnlyTheCacheMisses() {
        FakeGemini gemini = new FakeGemini(Translation.of("한국어 제목", "한국어 본문"), null);
        TranslationService service = service(gemini);

        TranslationRequest cached = article("First", "Body one");
        TranslationRequest fresh = article("Second", "Body two");
        service.translateAll(List.of(cached));

        service.translateAll(List.of(cached, fresh));

        assertThat(gemini.batched).as("캐시에 있던 한 건이 빠지고 미스 한 건만 실린다").isEqualTo(1);
        assertThat(gemini.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("묶음이 실패하면 그 건들만 원문으로 강등된다 — 순서와 개수는 지킨다")
    void degradesTheWholeBatchToOriginalOnFailure() {
        TranslationService service = service(new FakeGemini(null, new RuntimeException("429")));

        List<Translation> results = service.translateAll(List.of(
                article("First", "Body one"), article("Second", "Body two")));

        assertThat(results).hasSize(2).allSatisfy(t -> assertThat(t.translated()).isFalse());
        assertThat(results.get(0).title()).isEqualTo("First");
        assertThat(results.get(1).title()).isEqualTo("Second");
    }

    @Test
    @DisplayName("강등된 결과는 캐시에 남기지 않는다 — 일시적 429로 영문이 7일간 굳으면 안 된다")
    void neverCachesADegradedTranslation() {
        FakeGemini failing = new FakeGemini(null, new RuntimeException("429"));
        TranslationService service = service(failing);
        TranslationRequest article = article("First", "Body one");

        service.translateAll(List.of(article));
        service.translateAll(List.of(article));

        assertThat(failing.calls).as("두 번째도 다시 시도해야 한다").isEqualTo(2);
    }

    // --- 캐시 조회: 건마다 Redis 왕복이라 동시에 보낸다 ------------------------

    @Test
    @DisplayName("캐시 조회는 동시에 나간다 — 기사 열 건이면 Redis 왕복 열 번을 줄줄이 기다리지 않는다")
    void looksUpTheCacheConcurrently() {
        // 세 조회가 서로를 기다리게 한다 — 순차로 돌면 첫 조회가 나머지 둘을 영영 못 만난다
        CountDownLatch allStarted = new CountDownLatch(3);
        TranslationCache meeting = new TranslationCache() {
            @Override
            public Optional<Translation> find(String key) {
                allStarted.countDown();
                try {
                    if (!allStarted.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("캐시 조회가 순차로 돈다 — 나머지 조회가 시작되지 않았다");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return Optional.empty();
            }

            @Override
            public void put(String key, Translation translation) {
            }
        };
        TranslationService service = new TranslationService(
                new FakeGemini(Translation.of("한국어 제목", "한국어 본문"), null), meeting);

        assertThat(service.translateAll(List.of(
                article("First", "Body one"), article("Second", "Body two"), article("Third", "Body three"))))
                .hasSize(3);
    }

    @Test
    @DisplayName("한 건의 캐시 읽기가 실패해도 그 건만 미스로 번역된다 — 캐시가 죽어도 답은 나가고, 멀쩡한 적중은 그대로 쓴다")
    void treatsACacheReadFailureAsAMissForThatArticleOnly() {
        FakeGemini gemini = new FakeGemini(Translation.of("한국어 제목", "한국어 본문"), null);
        TranslationRequest broken = article("First", "Body one");
        TranslationRequest healthy = article("Second", "Body two");
        TranslationService service = new TranslationService(gemini,
                new SpringTranslationCache(cacheFailingToRead(broken.key())));
        service.translateAll(List.of(broken, healthy));

        List<Translation> results = service.translateAll(List.of(broken, healthy));

        assertThat(results).hasSize(2).allSatisfy(t -> assertThat(t.translated()).isTrue());
        assertThat(gemini.batched).as("읽기가 실패한 한 건만 미스로 실린다").isEqualTo(1);
        assertThat(gemini.calls).isEqualTo(2);
    }

    /** {@code brokenKey}를 읽을 때만 던지는 캐시 — Redis가 한 키에서 끊긴 상황이다. 쓰기는 멀쩡하다. */
    private static CacheManager cacheFailingToRead(String brokenKey) {
        return new ConcurrentMapCacheManager(SpringTranslationCache.CACHE) {
            @Override
            protected Cache createConcurrentMapCache(String name) {
                return new ConcurrentMapCache(name) {
                    @Override
                    public <T> T get(Object key, Class<T> type) {
                        if (brokenKey.equals(key)) {
                            throw new IllegalStateException("Redis 연결이 끊겼습니다");
                        }
                        return super.get(key, type);
                    }
                };
            }
        };
    }

    private static TranslationService service(GeminiTranslator gemini) {
        return new TranslationService(gemini,
                new SpringTranslationCache(new ConcurrentMapCacheManager(SpringTranslationCache.CACHE)));
    }

    private static TranslationRequest article(String title, String description) {
        return new TranslationRequest("https://example.com/" + title.hashCode(), title, description);
    }

    /** 성공/실패를 지정하는 스텁. HTTP는 {@code GeminiTranslatorTest}에서 따로 본다. */
    private static final class FakeGemini extends GeminiTranslator {
        private final Translation result;
        private final RuntimeException failure;

        private FakeGemini(Translation result, RuntimeException failure) {
            super(new GeminiApi(RestClient.builder(), TestProperties.offline()),
                    JsonMapper.builder().build());
            this.result = result;
            this.failure = failure;
        }

        @Override
                public List<Translation> translateAll(List<TranslationRequest> articles) {
            calls++;
            batched = articles.size();
            if (failure != null) {
                throw failure;
            }
            return articles.stream().map(a -> result).toList();
        }

        /** Gemini를 몇 번 불렀는지 — 묶음이 실제로 한 번인지 여기서만 보인다. */
        private int calls;

        /** 마지막 묶음에 실린 건수. 캐시 적중분이 빠졌는지 확인한다. */
        private int batched;
    }
}
