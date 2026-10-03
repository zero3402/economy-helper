package io.saiden.economyhelper.news.domain;

import io.saiden.economyhelper.translate.domain.Translation;
import java.time.Instant;

/**
 * 사용자에게 보여줄 최종 형태 — 기사 + 한국어 번역.
 *
 * <p><b>화면이 쓰는 것만 담는다.</b> 매체는 이름으로만 적고, 점수는 순서가 곧 그 결과다.
 *
 * @param translated {@code false}면 {@code title}·{@code body}가 영문 원문이다.
 */
public record NewsItem(
        String sourceName,
        String title,
        String body,
        String link,
        Instant publishedAt,
        boolean translated) {

    public static NewsItem of(ScoredArticle scored, Translation translation) {
        Article article = scored.article();
        return new NewsItem(
                article.source().displayName(),
                translation.title(),
                translation.body(),
                article.link(),
                article.publishedAt(),
                translation.translated());
    }
}
