package io.saiden.economyhelper.news.feed;

import io.saiden.economyhelper.news.NewsSource;

/**
 * 피드 XML 파싱 실패.
 *
 * <p>소스별 서킷브레이커가 이 예외를 실패로 센다 — 한 매체가 형식을 바꾸거나
 * 차단 페이지를 내려줘도 나머지 매체 발송은 계속된다.
 */
public class FeedParseException extends RuntimeException {

    /** 매체명은 메시지 앞에 붙인다 — 로그 한 줄만 보고도 어느 피드가 깨졌는지 알아야 한다. */
    public FeedParseException(NewsSource source, String message, Throwable cause) {
        super("[" + source + "] " + message, cause);
    }
}
