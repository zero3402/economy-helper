package io.saiden.economyhelper.news.application.port.out;

import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.NewsSource;
import java.util.List;

/**
 * 매체 하나의 기사 목록. 피드 형식과 브레이커는 구현이 안다.
 */
public interface ArticleFeed {

    /** @return 그 매체의 기사. <b>실패하면 빈 목록</b> — 한 매체가 죽어도 나머지는 나간다 */
    List<Article> fetch(NewsSource source);
}
