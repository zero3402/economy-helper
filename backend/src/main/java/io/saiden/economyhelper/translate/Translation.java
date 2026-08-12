package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.news.Article;

/**
 * 기사 하나의 한국어 표현.
 *
 * <p>요약이 아니라 <b>번역</b>이다. 우리가 가진 원문은 피드가 준 제목과 요약문 1~2문장이 전부라
 * (본문은 4/5 매체가 403) 그 위에 "5줄 요약"을 얹으면 없는 사실을 지어내야 한다. 번역이면
 * 출력이 원문 범위를 벗어날 수 없다.
 *
 * @param translated 번역에 성공했는지. {@code false}면 {@code title}·{@code body}가 영문 원문
 *                   그대로이며, 발송 시 "번역 일시 불가"를 함께 알린다. 번역이라서 가능한
 *                   강등이다 — 요약이었다면 원문을 그대로 내보낼 수 없었다.
 */
public record Translation(String title, String body, boolean translated) {

    public Translation {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        body = body == null ? "" : body;
    }

    public static Translation of(String title, String body) {
        return new Translation(title, body, true);
    }

    /** 원문 그대로. 번역이 실패했을 때 정보 손실 없이 강등하는 경로다. */
    public static Translation untranslated(Article article) {
        return new Translation(article.title(), article.description(), false);
    }
}
