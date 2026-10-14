package io.saiden.economyhelper.news.application.port.out;

import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.NewsSource;
import java.util.List;

/**
 * 매체 하나의 기사 목록. 피드 형식과 브레이커는 구현이 안다.
 */
public interface ArticleFeed {

    /**
     * @return 그 매체의 기사. 피드가 답했는데 창 안의 기사가 없으면 빈 목록이다
     * @throws Unavailable 수집에 실패했을 때 — <b>빈 목록과 가른다.</b> 빈 목록으로 삼키면 매체가 전부 죽은 날
     *         {@code /news}가 「찾지 못했습니다」로 답한다. 한 매체의 실패를 건너뛰는 것은 호출자({@code NewsService})다
     */
    List<Article> fetch(NewsSource source);

    /** 매체 하나의 수집 실패 — 「기사가 없다」가 아니다. */
    final class Unavailable extends RuntimeException {

        public Unavailable(NewsSource source, Throwable cause) {
            super(source + " 피드 수집 실패", cause);
        }
    }
}
