package io.saiden.economyhelper.news.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 응답을 뜬 픽스처로 파싱을 고정한다.
 *
 * <p>네트워크를 타지 않으므로 테스트가 결정적이고, 매체가 피드 형식을 바꾼 사실은
 * 픽스처를 다시 뜰 때 드러난다.
 */
class RssFeedClientTest {

    private final RssFeedClient client = new RssFeedClient();

    @Test
    @DisplayName("RSS 형식을 다룬다고 스스로 밝힌다")
    void reportsRssType() {
        assertThat(client.type()).isEqualTo(FeedType.RSS);
    }

    @Test
    @DisplayName("Bloomberg — 20건을 파싱하고 노출 순서를 feedRank로 보존한다")
    void parsesBloombergPreservingFeedOrder() {
        List<Article> articles = client.parse(NewsSource.BLOOMBERG, fixture("bloomberg.xml"));

        assertThat(articles).hasSize(20);

        Article first = articles.get(0);
        assertThat(first.source()).isEqualTo(NewsSource.BLOOMBERG);
        assertThat(first.title()).isNotBlank();
        assertThat(first.description()).isNotBlank();
        assertThat(first.link()).startsWith("https://");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.feedRank()).isZero();

        assertThat(articles.get(19).feedRank()).isEqualTo(19);
    }

    @Test
    @DisplayName("Economist — CDATA를 감싼 개행·들여쓰기를 걷어낸다")
    void normalizesEconomistCdataWhitespace() {
        List<Article> articles = client.parse(NewsSource.ECONOMIST, fixture("economist.xml"));

        assertThat(articles).isNotEmpty();
        assertThat(articles).allSatisfy(article -> {
            assertThat(article.title())
                    .isEqualTo(article.title().strip())
                    .doesNotContain("\n")
                    .doesNotContain("CDATA");
            assertThat(article.description()).doesNotContain("\n");
        });
    }

    @Test
    @DisplayName("Economist — 유니코드 아포스트로피가 깨지지 않는다")
    void preservesUnicodeInEconomistTitles() {
        List<Article> articles = client.parse(NewsSource.ECONOMIST, fixture("economist.xml"));

        assertThat(articles)
                .as("Economist는 타이포그래픽 아포스트로피(’)를 쓴다")
                .anySatisfy(article -> assertThat(article.title()).contains("’"));
    }

    @Test
    @DisplayName("CoinDesk — content:encoded가 비어 있어도 description을 살린다")
    void usesCoindeskDescriptionDespiteEmptyContentEncoded() {
        List<Article> articles = client.parse(NewsSource.COINDESK, fixture("coindesk.xml"));

        assertThat(articles).hasSize(25);
        assertThat(articles).allSatisfy(article -> assertThat(article.description()).isNotBlank());
    }

    @Test
    @DisplayName("FT — 12건을 파싱한다")
    void parsesFt() {
        List<Article> articles = client.parse(NewsSource.FT, fixture("ft.xml"));

        assertThat(articles).hasSize(12);
        assertThat(articles).allSatisfy(article -> {
            assertThat(article.title()).isNotBlank();
            assertThat(article.link()).startsWith("https://");
            assertThat(article.publishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("망가진 XML은 FeedParseException으로 감싼다 — 소스별 서킷브레이커가 셀 수 있도록")
    void wrapsMalformedXmlAsFeedParseException() {
        assertThatThrownBy(() -> client.parse(NewsSource.FT, new StringReader("not xml at all")))
                .isInstanceOf(FeedParseException.class)
                .hasMessageContaining("FT");
    }

    private Reader fixture(String name) {
        InputStream in = getClass().getResourceAsStream("/fixtures/" + name);
        assertThat(in).as("픽스처 %s 가 있어야 한다", name).isNotNull();
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
