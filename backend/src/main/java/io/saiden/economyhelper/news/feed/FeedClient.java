package io.saiden.economyhelper.news.feed;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.Reader;
import java.util.List;

/**
 * 피드 XML을 {@link Article} 목록으로 바꾼다.
 *
 * <p>HTTP 요청은 여기 없다 — 파싱만 한다. 그래서 실제 응답을 뜬 픽스처로 네트워크 없이
 * 테스트할 수 있고, 수집·재시도·서킷브레이커는 상위 서비스가 맡는다.
 */
public interface FeedClient {

    /** 이 구현이 다루는 피드 형식. 상위 서비스가 소스별로 파서를 고를 때 쓴다. */
    FeedType type();

    /**
     * @throws FeedParseException XML이 망가져 파싱할 수 없을 때. 개별 항목의 결함은
     *                            예외가 아니라 건너뛰기로 처리한다 — 한 건 때문에
     *                            피드 전체를 잃지 않기 위해서다.
     */
    List<Article> parse(NewsSource source, Reader xml);
}
