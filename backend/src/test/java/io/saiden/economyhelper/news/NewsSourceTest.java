package io.saiden.economyhelper.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "무료 매체만 고른다"는 기준을 실제로 지키는 지점.
 *
 * <p>매체를 고르는 것만으로는 부족했다 — <b>Yahoo 피드가 남의 기사를 실어 나른다.</b>
 * 페이월 매체 목록을 따로 관리하는 대신 <b>그 매체 기사만 받는</b> 쪽으로 뒤집었다.
 */
class NewsSourceTest {

    @Test
    @DisplayName("자기 매체 기사는 받는다 — 서브도메인도 자기 것이다")
    void ownsItsOwnArticles() {
        assertThat(NewsSource.YAHOO_FINANCE.owns("https://finance.yahoo.com/news/a")).isTrue();
        assertThat(NewsSource.INVESTING.owns("https://www.investing.com/news/a")).isTrue();
        assertThat(NewsSource.CNBC.owns("https://www.cnbc.com/2026/08/14/a.html")).isTrue();
        assertThat(NewsSource.BBC.owns("https://www.bbc.co.uk/news/articles/a")).isTrue();
        assertThat(NewsSource.BBC.owns("https://www.bbc.com/news/articles/a")).isTrue();
    }

    @Test
    @DisplayName("Yahoo 피드가 실어 온 남의 기사는 안 받는다 — 우리가 고른 건 Yahoo다")
    void rejectsSyndicatedArticlesFromOtherOutlets() {
        // 2026-08-14 실측: 48건 중 wsj.com 1건, investors.com 7건. 둘 다 페이월이다
        assertThat(NewsSource.YAHOO_FINANCE.owns("https://www.wsj.com/articles/a")).isFalse();
        assertThat(NewsSource.YAHOO_FINANCE.owns("https://www.investors.com/news/a")).isFalse();
    }

    @Test
    @DisplayName("매체끼리도 남남이다 — CNBC 피드에 BBC 기사가 오면 그것도 아니다")
    void doesNotAcceptAnotherConfiguredOutletsArticle() {
        assertThat(NewsSource.CNBC.owns("https://www.bbc.co.uk/news/a")).isFalse();
        assertThat(NewsSource.BBC.owns("https://www.cnbc.com/a")).isFalse();
    }

    @Test
    @DisplayName("도메인 끝만 같은 남의 주소에 속지 않는다")
    void doesNotFallForLookalikeHosts() {
        // endsWith("yahoo.com")만 봤다면 이것들이 전부 통과한다
        assertThat(NewsSource.YAHOO_FINANCE.owns("https://notyahoo.com/a")).isFalse();
        assertThat(NewsSource.CNBC.owns("https://fake-cnbc.com/a")).isFalse();
    }

    @Test
    @DisplayName("AP는 구글 뉴스 주소가 자기 주소다 — 쿼리가 site:apnews.com으로 묶여 있다")
    void apOwnsItsGoogleNewsProxyLinks() {
        assertThat(NewsSource.AP.owns("https://news.google.com/rss/articles/CBMi")).isTrue();
    }

    @Test
    @DisplayName("읽을 수 없는 주소는 안 받는다 — 허용 목록이라 애매하면 빼는 쪽이 맞다")
    void rejectsUnparseableLinks() {
        assertThat(NewsSource.CNBC.owns("not a url at all")).isFalse();
        assertThat(NewsSource.CNBC.owns("/relative/path")).isFalse();
        assertThat(NewsSource.CNBC.owns(null)).isFalse();
    }
}
