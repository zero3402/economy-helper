package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.news.Article;

/**
 * 기사를 한국어로 옮긴다.
 *
 * <p>구현이 둘이라 인터페이스를 둔다 — {@link GeminiTranslator}(무료 티어)와
 * {@link PassthroughTranslator}(폴백). 제공자를 Groq 등으로 바꿔야 할 때도 여기만 갈아끼우면 된다.
 */
public interface Translator {

    /**
     * @throws RuntimeException 번역에 실패하면 던진다. 폴백 결정은 {@link TranslationService}가 한다 —
     *                          구현체가 스스로 원문을 돌려주면 실패가 조용히 묻힌다.
     */
    Translation translate(Article article);
}
