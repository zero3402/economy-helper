package io.saiden.economyhelper.news;

import io.saiden.economyhelper.translate.Translation;
import java.time.Instant;

/**
 * 사용자에게 보여줄 최종 형태 — 기사 + 한국어 번역.
 *
 * <p><b>화면이 쓰는 것만 담는다.</b> 매체 열거값과 점수도 함께 들고 다녔는데, 화면은 매체를
 * 이름({@code sourceName})으로만 적고 점수는 순위를 매길 때 이미 다 쓴 값이다 —
 * 순서가 곧 그 결과다. REST 응답을 만들 때 그 둘이 필요해지면 그때 다시 담는다.
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
