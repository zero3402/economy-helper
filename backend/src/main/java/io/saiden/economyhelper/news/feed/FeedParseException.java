package io.saiden.economyhelper.news.feed;

import io.saiden.economyhelper.news.NewsSource;

/**
 * 피드 XML 파싱 실패.
 *
 * <p>소스별 서킷브레이커가 이 예외를 실패로 센다 — 한 매체가 형식을 바꾸거나
 * 차단 페이지를 내려줘도 나머지 매체 발송은 계속된다.
 */
public class FeedParseException extends RuntimeException {

    private final NewsSource source;

    public FeedParseException(NewsSource source, String message, Throwable cause) {
        super("[" + source + "] " + message, cause);
        this.source = source;
    }

    public NewsSource source() {
        return source;
    }
}
