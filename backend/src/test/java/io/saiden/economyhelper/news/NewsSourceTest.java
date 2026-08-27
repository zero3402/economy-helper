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
        assertThat(NewsSource.COINDESK.owns(
                "https://www.coindesk.com/markets/2026/08/26/a")).isTrue();
        // 실측 링크에 ?utm_source=rss_feed 꼬리가 붙는다 — 호스트만 보므로 통과해야 한다
        assertThat(NewsSource.COINTELEGRAPH.owns(
                "https://cointelegraph.com/news/a?utm_source=rss_feed&utm_medium=rss")).isTrue();
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
        assertThat(NewsSource.COINDESK.owns("https://notcoindesk.com/a")).isFalse();
        assertThat(NewsSource.COINTELEGRAPH.owns("https://fake-cointelegraph.com/a")).isFalse();
    }

    @Test
    @DisplayName("코인만 싣기로 한 자리는 값이 스스로 안다 — 분기문에 두면 매체를 더할 때 빠뜨린다")
    void knowsWhichFeedsAreCryptoOnly() {
        assertThat(NewsSource.INVESTING_CRYPTO.cryptoSection()).isTrue();
        assertThat(NewsSource.COINDESK.cryptoSection()).isTrue();
        assertThat(NewsSource.COINTELEGRAPH.cryptoSection()).isTrue();

        // 같은 매체의 본 섹션은 아니다 — 섹션이 갈리는 것이 이 값의 요점이다
        assertThat(NewsSource.INVESTING.cryptoSection()).isFalse();
        assertThat(NewsSource.YAHOO_FINANCE.cryptoSection()).isFalse();
        assertThat(NewsSource.CNBC.cryptoSection()).isFalse();
        assertThat(NewsSource.BBC.cryptoSection()).isFalse();
        assertThat(NewsSource.AP.cryptoSection()).isFalse();
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
