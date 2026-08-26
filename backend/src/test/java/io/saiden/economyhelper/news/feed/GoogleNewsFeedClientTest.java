package io.saiden.economyhelper.news.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import io.saiden.economyhelper.support.TestFixtures;
import org.junit.jupiter.api.Test;

/**
 * AP 프록시 피드가 표준 RSS와 다른 두 지점을 고정한다:
 * 제목의 매체명 꼬리와, 요약문 대신 오는 리다이렉트 링크 마크업.
 */
class GoogleNewsFeedClientTest {

    private final GoogleNewsFeedClient client = new GoogleNewsFeedClient();

    @Test
    @DisplayName("제목 꼬리의 ' - apnews.com'을 뗀다")
    void stripsOutletSuffixFromTitle() {
        List<Article> articles = parseFixture();

        assertThat(articles).hasSize(100);
        assertThat(articles).allSatisfy(article -> {
            // 픽스처 100건이 전부 이 꼬리를 달고 온다 — 안 떼면 제목마다 매체명이 붙는다
            assertThat(article.title()).doesNotEndWith("- apnews.com");
            assertThat(article.title()).isNotBlank();
        });
    }

    @Test
    @DisplayName("description은 Google 리다이렉트 링크뿐이라 버린다")
    void discardsRedirectMarkupDescription() {
        List<Article> articles = parseFixture();

        assertThat(articles).allSatisfy(article -> assertThat(article.description()).isNull());
    }

    @Test
    @DisplayName("요약문이 없으므로 번역 입력이 제목 한 줄뿐이다")
    void translationTextIsTitleOnly() {
        List<Article> articles = parseFixture();

        Article first = articles.get(0);
        assertThat(first.text()).isEqualTo(first.title()).doesNotContain("\n");
    }

    @Test
    @DisplayName("헤드라인 중간의 하이픈은 건드리지 않는다")
    void keepsHyphensInsideHeadline() {
        String kept = client.normalizeTitle("US-China trade talks stall - apnews.com");

        assertThat(kept).isEqualTo("US-China trade talks stall");
    }

    private List<Article> parseFixture() {
        return client.parse(NewsSource.AP, fixture("googlenews-ap.xml"));
    }

    private Reader fixture(String name) {
        return TestFixtures.reader("fixtures/" + name);
    }
}
