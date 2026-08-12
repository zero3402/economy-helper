package io.saiden.economyhelper.news;

import io.saiden.economyhelper.translate.Translation;
import java.time.Instant;

/**
 * 사용자에게 보여줄 최종 형태 — 기사 + 한국어 번역.
 *
 * <p>텔레그램과 REST가 이 타입 하나를 공유한다. 두 채널이 서로 다른 모양을 만들기 시작하면
 * 곧 서로 다른 내용을 보여주게 된다.
 *
 * @param translated {@code false}면 {@code title}·{@code body}가 영문 원문이다.
 */
public record NewsItem(
        NewsSource source,
        String sourceName,
        String title,
        String body,
        String link,
        Instant publishedAt,
        boolean translated,
        double score) {

    public static NewsItem of(ScoredArticle scored, Translation translation) {
        Article article = scored.article();
        return new NewsItem(
                article.source(),
                article.source().displayName(),
                translation.title(),
                translation.body(),
                article.link(),
                article.publishedAt(),
                translation.translated(),
                scored.score());
    }
}
