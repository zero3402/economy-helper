package io.saiden.economyhelper.news.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.NewsSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import io.saiden.economyhelper.support.TestFixtures;
import org.junit.jupiter.api.Test;

/**
 * 실제 응답을 뜬 픽스처로 파싱을 고정한다(2026-08-14 채취).
 *
 * <p>네트워크를 타지 않으므로 테스트가 결정적이고, 매체가 피드 형식을 바꾼 사실은
 * 픽스처를 다시 뜰 때 드러난다.
 *
 * <p><b>픽스처가 정말 그 매체 것인지부터 검사한다</b>({@link #fixturesActuallyComeFromTheirOutlet}).
 * 매체를 다섯 곳으로 갈아엎을 때 픽스처는 옛 매체 것을 파일명만 바꿔 둔 채였다 —
 * Investing 자리에 CoinDesk, Yahoo 자리에 FT가 들어 있었다. 그래서 <b>실물 Investing이
 * 규격을 어긴 날짜를 내려보내 전량 폐기되는 동안에도 이 테스트는 초록불이었다.</b>
 * 파일명이 아니라 내용이 매체를 정한다.
 */
class RssFeedClientTest {

    private final RssFeedClient client = new RssFeedClient();

    @Test
    @DisplayName("픽스처가 정말 그 매체 것이다 — 파일명만 바꾼 옛 매체 응답이 아니다")
    void fixturesActuallyComeFromTheirOutlet() {
        assertFixtureIsFrom("yahoo-finance.xml", "finance.yahoo.com");
        assertFixtureIsFrom("investing.xml", "investing.com");
        assertFixtureIsFrom("cnbc.xml", "cnbc.com");
        assertFixtureIsFrom("bbc.xml", "bbc.co.uk");
        // AP는 링크가 구글 뉴스 불투명 주소라 목적지를 볼 수 없다. 대신 피드가 스스로
        // 밝히는 검색 조건이 목적지를 못 박는다
        assertThat(raw("googlenews-ap.xml"))
                .as("AP 피드는 site:apnews.com으로 묶인 구글 뉴스 검색이어야 한다")
                .contains("site:apnews.com");
    }

    @Test
    @DisplayName("Yahoo는 남의 매체 기사를 섞어 내보낸다 — 그중에 페이월이 있다")
    void yahooSyndicatesThirdPartyOutlets() {
        List<Article> articles = client.parse(NewsSource.YAHOO_FINANCE, fixture("yahoo-finance.xml"));

        // 이 사실을 여기 못 박아 둔다. 매체를 다섯 곳으로 고른 기준이 "전문 무료"인데
        // Yahoo 피드는 그 경계 밖 링크를 실어 나른다 — 걸러내는 쪽은 PaywallFilter가 맡는다
        assertThat(articles)
                .as("Yahoo 자기 기사만 있는 게 아니다")
                .anySatisfy(article -> assertThat(article.link()).doesNotContain("yahoo.com"));
    }

    @Test
    @DisplayName("Investing — 규격을 어긴 pubDate여도 전량 살린다")
    void survivesInvestingNonStandardPubDate() {
        List<Article> articles = client.parse(NewsSource.INVESTING, fixture("investing.xml"));

        // RSS 2.0은 pubDate를 RFC 822로 규정하는데 Investing은 '2026-08-14 07:54:20'으로 준다.
        // 되돌려 주지 않으면 Rome이 날짜를 못 읽어 열 건이 전부 버려진다 — 실제로 그랬다.
        // 여덟인 이유는 열 건 중 둘이 Reuters 재게재본이라 따로 걸러지기 때문이다
        assertThat(articles).hasSize(8);
        assertThat(articles).allSatisfy(article ->
                assertThat(article.publishedAt()).isNotNull());
    }

    @Test
    @DisplayName("페이월 매체의 재게재본은 버린다 — 주소가 investing.com이라 호스트 필터를 통과한다")
    void dropsArticlesSyndicatedFromPaywalledPublishers() {
        List<Article> articles = client.parse(NewsSource.INVESTING, fixture("investing.xml"));

        // 이 피드는 열 건 중 둘이 <author>Reuters</author>다. 링크는 investing.com이라
        // NewsSource.owns가 못 막는다 — 안에 얹힌 남의 기사는 author로 가른다
        assertThat(articles)
                .as("Reuters 재게재본 두 건이 빠진다")
                .hasSize(8)
                .as("그 매체 자신의 기사는 그대로 남는다")
                .allSatisfy(article -> assertThat(article.link()).contains("investing.com"));
    }

    @Test
    @DisplayName("author가 비어 있으면 통과시킨다 — AP 프록시 피드가 그렇다")
    void keepsArticlesWithoutAnAuthor() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel><title>t</title><link>https://x</link>
                <item><title>제목</title><link>https://apnews.com/a</link>
                <pubDate>Thu, 14 Aug 2026 07:54:20 GMT</pubDate></item>
                </channel></rss>""";

        assertThat(client.parse(NewsSource.AP, new java.io.StringReader(xml))).hasSize(1);
    }

    @Test
    @DisplayName("시간대 없는 pubDate는 UTC로 읽는다 — KST로 읽으면 9시간 낡은 값이 된다")
    void readsBarePubDateAsUtc() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel><title>t</title><link>https://x</link>
                <item><title>제목</title><link>https://x/a</link>
                <pubDate>2026-08-14 07:54:20</pubDate></item>
                </channel></rss>""";

        List<Article> articles = client.parse(NewsSource.INVESTING, new StringReader(xml));

        assertThat(articles).singleElement().satisfies(article ->
                assertThat(article.publishedAt())
                        .isEqualTo(java.time.Instant.parse("2026-08-14T07:54:20Z")));
    }

    @Test
    @DisplayName("규격을 지킨 pubDate는 손대지 않는다")
    void leavesCompliantPubDateAlone() {
        String compliant = "<pubDate>Fri, 14 Aug 2026 07:45:51 GMT</pubDate>";

        assertThat(RssFeedClient.normalizePubDates(compliant)).isEqualTo(compliant);
    }

    @Test
    @DisplayName("CNBC — 노출 순서를 feedRank로 보존한다")
    void parsesCnbcPreservingFeedOrder() {
        List<Article> articles = client.parse(NewsSource.CNBC, fixture("cnbc.xml"));

        assertThat(articles).hasSize(30);

        Article first = articles.get(0);
        assertThat(first.source()).isEqualTo(NewsSource.CNBC);
        assertThat(first.title()).isNotBlank();
        assertThat(first.description()).isNotBlank();
        assertThat(first.link()).startsWith("https://");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.feedRank()).isZero();

        assertThat(articles.get(29).feedRank()).isEqualTo(29);
    }

    @Test
    @DisplayName("BBC — 52건 모두 제목과 요약을 갖는다")
    void parsesBbcWithDescriptions() {
        List<Article> articles = client.parse(NewsSource.BBC, fixture("bbc.xml"));

        assertThat(articles).hasSize(52);
        assertThat(articles).allSatisfy(article -> assertThat(article.description()).isNotBlank());
    }

    @Test
    @DisplayName("Yahoo Finance — 48건. 요약이 없는 피드라 본문 자리가 빈다")
    void parsesYahooFinanceWhichHasNoDescriptions() {
        List<Article> articles = client.parse(NewsSource.YAHOO_FINANCE, fixture("yahoo-finance.xml"));

        assertThat(articles).hasSize(48);
        assertThat(articles).allSatisfy(article -> {
            assertThat(article.title()).isNotBlank();
            assertThat(article.link()).startsWith("https://");
            assertThat(article.publishedAt()).isNotNull();
        });
        // 요약이 없는 매체가 있다는 사실을 여기 고정해 둔다 — 없는 것이지 파싱이 깨진 게 아니다
        assertThat(articles).allSatisfy(article -> assertThat(article.description()).isNull());
    }

    @Test
    @DisplayName("CDATA를 감싼 개행·들여쓰기를 걷어낸다")
    void normalizesCdataWhitespace() {
        List<Article> articles = client.parse(NewsSource.BBC, fixture("bbc.xml"));

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
    @DisplayName("유니코드 아포스트로피가 깨지지 않는다")
    void preservesUnicodeInTitles() {
        List<Article> articles = client.parse(NewsSource.YAHOO_FINANCE, fixture("yahoo-finance.xml"));

        assertThat(articles)
                .as("타이포그래픽 아포스트로피(’)를 쓰는 제목이 섞여 있다")
                .anySatisfy(article -> assertThat(article.title()).contains("’"));
    }

    @Test
    @DisplayName("망가진 XML은 FeedParseException으로 감싼다 — 소스별 서킷브레이커가 셀 수 있도록")
    void wrapsMalformedXmlAsFeedParseException() {
        assertThatThrownBy(() -> client.parse(NewsSource.YAHOO_FINANCE, new StringReader("not xml at all")))
                .isInstanceOf(FeedParseException.class)
                .hasMessageContaining("YAHOO_FINANCE");
    }

    /**
     * <b>항목 링크의 과반</b>이 그 매체 것인지로 확인한다.
     *
     * <p>파일 어딘가에 그 문자열이 있는지만 보면 이름값을 못 한다 — WSJ 피드에
     * {@code finance.yahoo.com} 링크가 하나만 섞여 있어도 통과한다. 반대로 전부를
     * 요구할 수도 없다: Yahoo는 남의 매체 기사를 실어 나른다.
     */
    private void assertFixtureIsFrom(String name, String host) {
        List<String> links = java.util.regex.Pattern.compile("<link>(https?://[^<]+)</link>")
                .matcher(raw(name)).results().map(m -> m.group(1)).toList();
        long own = links.stream().filter(link -> link.contains(host)).count();

        assertThat(links).as("%s 에 항목 링크가 있어야 한다", name).isNotEmpty();
        assertThat(own * 2).as("%s 는 %s 피드를 뜬 것이어야 한다 (%d/%d)", name, host, own, links.size())
                .isGreaterThan(links.size());
    }

    private String raw(String name) {
        return TestFixtures.text("fixtures/" + name);
    }

    private Reader fixture(String name) {
        return TestFixtures.reader("fixtures/" + name);
    }
}
