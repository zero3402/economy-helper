package io.saiden.economyhelper.news;

import java.time.Instant;

/**
 * 피드에서 뽑아낸 기사 한 건.
 *
 * <p>본문은 담지 않는다 — 매체 대부분이 기사 페이지를 봇에 열어 주지 않아
 * 우리가 가진 텍스트는 피드가 준 제목과 요약문이 전부다. 번역 입력도 이게 전부다.
 *
 * @param description 없을 수 있다. Google News 프록시는 요약문 대신 리다이렉트 링크
 *                    마크업을 주므로 버린다 — 그 경우 번역 입력이 제목뿐이다.
 * @param feedRank    피드 내 0-based 노출 순서. 편집자가 매긴 우선순위로 보고 랭킹에 쓴다.
 */
public record Article(
        NewsSource source,
        String title,
        String description,
        String link,
        Instant publishedAt,
        int feedRank) {

    public Article {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (link == null || link.isBlank()) {
            throw new IllegalArgumentException("link is required");
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException("publishedAt is required");
        }
        if (feedRank < 0) {
            throw new IllegalArgumentException("feedRank must be >= 0, was " + feedRank);
        }
    }

    /** 검색 전-필터가 훑을 텍스트({@code NewsService.matchesAny}). 번역과 관련도 채점은 {@code title}·{@code description}을 따로 쓴다 — 여기를 거치지 않는다. 요약문이 없으면 제목만 돌려준다. */
    public String text() {
        return description == null || description.isBlank()
                ? title
                : title + "\n\n" + description;
    }
}
