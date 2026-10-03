package io.saiden.economyhelper.news.application.port.out;

import io.saiden.economyhelper.news.domain.Article;
import java.util.List;
import java.util.Map;

/**
 * 의미를 아는 쪽이 매기는 관련도. 문자열 매칭은 후보를 좁히는 데까지만 쓴다.
 *
 * <p>둘 다 <b>실패해도 던지지 않는다</b> — 발송이 멈추면 안 된다.
 */
public interface RelevanceJudge {

    /** @return 기사 링크 → 재테크 관련도 0~1 */
    Map<String, Double> scoreAll(List<Article> candidates);

    /** @return 기사 링크 → 검색어 관련도 0~1 — {@code /news {검색어}}용 */
    Map<String, Double> scoreAll(List<Article> candidates, String query);
}
