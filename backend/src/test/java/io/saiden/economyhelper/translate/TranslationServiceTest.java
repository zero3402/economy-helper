package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.llm.GeminiApi;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

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

        Article article = article("Oil holds advance", "Oil kept its gains.");
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
                new Article(NewsSource.AP, "Fed signals cut", null,
                        "https://example.com/r", NOW, 0))).get(0);

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
    @DisplayName("이미 번역한 기사는 묶음에서 뺀다 — 링크 단위 캐시가 무료 티어를 아끼는 수단이다")
    void batchesOnlyTheCacheMisses() {
        FakeGemini gemini = new FakeGemini(Translation.of("한국어 제목", "한국어 본문"), null);
        TranslationService service = service(gemini);

        Article cached = article("First", "Body one");
        Article fresh = article("Second", "Body two");
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
        Article article = article("First", "Body one");

        service.translateAll(List.of(article));
        service.translateAll(List.of(article));

        assertThat(failing.calls).as("두 번째도 다시 시도해야 한다").isEqualTo(2);
    }

    private static TranslationService service(GeminiTranslator gemini) {
        return new TranslationService(gemini, new ConcurrentMapCacheManager("translation"));
    }

    private static Article article(String title, String description) {
        return new Article(NewsSource.CNBC, title, description,
                "https://example.com/" + title.hashCode(), NOW, 0);
    }

    /** 성공/실패를 지정하는 스텁. HTTP는 {@link GeminiTranslatorTest}에서 따로 본다. */
    private static final class FakeGemini extends GeminiTranslator {
        private final Translation result;
        private final RuntimeException failure;

        private FakeGemini(Translation result, RuntimeException failure) {
            super(new GeminiApi(RestClient.builder(), "https://example.invalid", "unused", "unused"),
                    JsonMapper.builder().build());
            this.result = result;
            this.failure = failure;
        }

        @Override
                public List<Translation> translateAll(List<Article> articles) {
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
