package io.saiden.economyhelper.news.feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 매체를 고른 기준이 "전문 무료"인데 Yahoo 피드가 그 경계 밖 링크를 실어 나른다.
 * 여기가 그 경계를 지키는 지점이다.
 */
class PaywallFilterTest {

    @Test
    @DisplayName("페이월 매체는 막는다 — Yahoo가 실제로 실어 보낸 둘을 포함해서")
    void blocksPaywalledOutlets() {
        assertThat(PaywallFilter.blocks("https://www.wsj.com/articles/abc")).isTrue();
        assertThat(PaywallFilter.blocks("https://www.investors.com/news/abc")).isTrue();
        assertThat(PaywallFilter.blocks("https://www.barrons.com/articles/abc")).isTrue();
    }

    @Test
    @DisplayName("CLAUDE.md가 매체 목록에서 뺀 곳도 막는다 — 뺀 이유가 여기에도 그대로다")
    void blocksOutletsAlreadyExcludedFromTheSourceList() {
        assertThat(PaywallFilter.blocks("https://www.bloomberg.com/news/a")).isTrue();
        assertThat(PaywallFilter.blocks("https://www.reuters.com/business/a")).isTrue();
        assertThat(PaywallFilter.blocks("https://www.ft.com/content/a")).isTrue();
        assertThat(PaywallFilter.blocks("https://www.economist.com/finance/a")).isTrue();
    }

    @Test
    @DisplayName("우리 다섯 매체는 통과한다")
    void allowsOurOwnOutlets() {
        assertThat(PaywallFilter.blocks("https://finance.yahoo.com/news/a")).isFalse();
        assertThat(PaywallFilter.blocks("https://www.investing.com/news/a")).isFalse();
        assertThat(PaywallFilter.blocks("https://www.cnbc.com/2026/08/14/a.html")).isFalse();
        assertThat(PaywallFilter.blocks("https://www.bbc.co.uk/news/articles/a")).isFalse();
        assertThat(PaywallFilter.blocks("https://news.google.com/rss/articles/CBMi")).isFalse();
    }

    @Test
    @DisplayName("도메인 끝만 같은 남의 주소를 말려들게 하지 않는다")
    void doesNotBlockLookalikeHosts() {
        // endsWith("wsj.com")만 봤다면 이것들이 전부 걸린다
        assertThat(PaywallFilter.blocks("https://notwsj.com/a")).isFalse();
        assertThat(PaywallFilter.blocks("https://myft.com/a")).isFalse();
        assertThat(PaywallFilter.blocks("https://fake-reuters.com/a")).isFalse();
    }

    @Test
    @DisplayName("서브도메인도 같은 매체다")
    void blocksSubdomains() {
        assertThat(PaywallFilter.blocks("https://research.investors.com/a")).isTrue();
        assertThat(PaywallFilter.blocks("https://www.bloomberg.com/a")).isTrue();
    }

    @Test
    @DisplayName("읽을 수 없는 주소는 통과시킨다 — 못 읽는 것이 페이월이라는 근거는 아니다")
    void allowsUnparseableLinks() {
        assertThat(PaywallFilter.blocks("not a url at all")).isFalse();
        assertThat(PaywallFilter.blocks("/relative/path")).isFalse();
    }
}
