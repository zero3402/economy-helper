package io.saiden.economyhelper.translate;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

        Translation result = service.translate(article("Oil holds advance", "Oil kept its gains."));

        assertThat(result.translated()).isTrue();
        assertThat(result.title()).isEqualTo("한국어 제목");
    }

    @Test
    @DisplayName("429가 나면 원문 그대로 강등한다 — 정보 손실 없이 발송은 계속된다")
    void fallsBackToOriginalOnRateLimit() {
        TranslationService service = service(new FakeGemini(
                null, new RuntimeException("429 Quota exceeded")));

        Article article = article("Oil holds advance", "Oil kept its gains.");
        Translation result = service.translate(article);

        assertThat(result.translated()).isFalse();
        assertThat(result.title()).isEqualTo("Oil holds advance");
        assertThat(result.body()).isEqualTo("Oil kept its gains.");
    }

    @Test
    @DisplayName("어떤 예외가 나도 던지지 않는다 — 한 건의 번역 실패가 발송 전체를 막으면 안 된다")
    void neverPropagatesFailures() {
        TranslationService service = service(new FakeGemini(null, new IllegalStateException("응답 파손")));

        assertThat(service.translate(article("Title", "Body")).translated()).isFalse();
    }

    @Test
    @DisplayName("요약문이 없는 기사(AP)는 body가 빈 문자열로 강등된다")
    void handlesArticlesWithoutDescription() {
        TranslationService service = service(new FakeGemini(null, new RuntimeException("실패")));

        Translation result = service.translate(
                new Article(NewsSource.AP, "Fed signals cut", null,
                        "https://example.com/r", NOW, 0));

        assertThat(result.title()).isEqualTo("Fed signals cut");
        assertThat(result.body()).isEmpty();
    }

    private static TranslationService service(GeminiTranslator gemini) {
        return new TranslationService(gemini);
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
        public Translation translate(Article article) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
