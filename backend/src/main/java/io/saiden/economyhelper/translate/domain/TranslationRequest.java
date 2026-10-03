package io.saiden.economyhelper.translate.domain;

/**
 * 번역할 글 한 건 — 번역 컨텍스트가 받는 입력이다.
 *
 * <p>번역은 기사가 무엇인지 모른다. 부르는 쪽(뉴스)이 제 기사를 이 모양으로 옮겨 넘긴다 —
 * 그래야 의존이 뉴스 → 번역 한 방향으로만 선다.
 *
 * @param key   번역 캐시의 키. 뉴스는 기사 링크를 넣는다 — 같은 기사를 두 번 번역하지 않는다
 * @param title 원문 제목
 * @param body  원문 본문(피드 요약문). 없으면 {@code null}
 */
public record TranslationRequest(String key, String title, String body) {
}
