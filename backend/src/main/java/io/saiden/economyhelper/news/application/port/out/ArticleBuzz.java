package io.saiden.economyhelper.news.application.port.out;

import io.saiden.economyhelper.news.domain.Article;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 기사에 대한 남의 반응 — 랭킹 신호 넷 중 유일하게 실측된 반응이다.
 */
public interface ArticleBuzz {

    /**
     * @return 기사 링크 → 반응 점수. 반응이 없는 기사는 담기지 않는다(0으로 본다).
     *         실패는 빈 맵으로 강등한다 — 보충 신호라 발송을 멈추지 않는다
     */
    Map<String, Integer> buzzByLink(List<Article> articles, Instant now);
}
