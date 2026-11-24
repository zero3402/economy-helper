package io.saiden.economyhelper.news.adapter.out.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.NewsSource;
import io.saiden.economyhelper.testsupport.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실물 형식을 따른 합성 픽스처로 파싱을 고정한다.
 *
 * <p>골격·날짜 모양·링크 호스트·author·CDATA·마크업은 실제 응답(파일마다 채취일을 채널
 * 설명에 적었다) 그대로이고, <b>제목·요약·사람 이름·링크 경로·이미지는 지어 넣었다</b> — 남의
 * 기사를 저장소에 복제하지 않는다({@link #feedFixturesCarryNoRealArticles}). 매체가 형식을 바꾼
 * 사실은 픽스처를 다시 뜰 때 드러난다.
 *
 * <p><b>픽스처가 정말 그 매체 것인지부터 검사한다</b>({@link #fixturesActuallyComeFromTheirOutlet}).
 * 파일명만 바꾼 남의 매체 응답이면 실물이 깨져도 이 테스트는 초록이다 — 파일명이 아니라 내용이
 * 매체를 정한다.
 */
class RssFeedClientTest {

    private static final List<String> FEED_FIXTURES = List.of(
            "cnbc.xml", "bbc.xml", "guardian-business.xml", "dw-business.xml", "googlenews-ap.xml",
            "coindesk.xml", "theblock.xml", "decrypt.xml");

    private final RssFeedClient client = new RssFeedClient();

    @Test
    @DisplayName("픽스처가 정말 그 매체 것이다 — 파일명만 바꾼 옛 매체 응답이 아니다")
    void fixturesActuallyComeFromTheirOutlet() {
        assertFixtureIsFrom("cnbc.xml", "cnbc.com");
        assertFixtureIsFrom("bbc.xml", "bbc.co.uk");
        assertFixtureIsFrom("guardian-business.xml", "theguardian.com");
        assertFixtureIsFrom("dw-business.xml", "dw.com");
        assertFixtureIsFrom("coindesk.xml", "coindesk.com");
        assertFixtureIsFrom("theblock.xml", "theblock.co");
        assertFixtureIsFrom("decrypt.xml", "decrypt.co");
        // AP는 링크가 구글 뉴스 불투명 주소라 목적지를 볼 수 없다. 대신 피드가 스스로
        // 밝히는 검색 조건이 목적지를 못 박는다
        assertThat(raw("googlenews-ap.xml"))
                .as("AP 피드는 site:apnews.com으로 묶인 구글 뉴스 검색이어야 한다")
                .contains("site:apnews.com");
    }

    @Test
    @DisplayName("피드 픽스처에 실제 기사가 없다 — 제목이 전부 합성이다")
    void feedFixturesCarryNoRealArticles() {
        for (String name : FEED_FIXTURES) {
            String xml = raw(name);
            int start = Math.max(xml.indexOf("<item>"), xml.indexOf("<item "));
            List<String> titles = java.util.regex.Pattern
                    .compile("<title>(?:<!\\[CDATA\\[)?\\s*([^<\\]]*)")
                    .matcher(xml.substring(start)).results().map(m -> m.group(1)).toList();

            assertThat(titles).as("%s 에 항목 제목이 있어야 한다", name).isNotEmpty();
            assertThat(titles).as("%s 는 실물을 그대로 뜬 것이 아니라 합성본이어야 한다", name)
                    .allSatisfy(title -> assertThat(title).startsWith("Synthetic headline"));
        }
    }

    @Test
    @DisplayName("페이월 매체의 재게재본은 버린다 — 주소가 그 매체 것이라 호스트 필터를 통과한다")
    void dropsArticlesSyndicatedFromPaywalledPublishers() {
        // 옛 매체 Investing.com의 실측(2026-08-17): 열 건 중 둘이 <author>Reuters</author>였다.
        // 링크는 자기 도메인이라 NewsSource.owns가 못 막는다 — 안에 얹힌 남의 기사는 author로 가른다
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel><title>t</title><link>https://x</link>
                <item><title>자기 기사</title><link>https://www.cnbc.com/a</link>
                <author>CNBC</author><pubDate>Thu, 14 Aug 2026 07:54:20 GMT</pubDate></item>
                <item><title>얹힌 기사</title><link>https://www.cnbc.com/b</link>
                <author>Reuters</author><pubDate>Thu, 14 Aug 2026 07:54:20 GMT</pubDate></item>
                </channel></rss>""";

        assertThat(client.parse(NewsSource.CNBC, xml))
                .extracting(Article::title).containsExactly("자기 기사");
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

        assertThat(client.parse(NewsSource.AP, xml)).hasSize(1);
    }

    @Test
    @DisplayName("이스케이프된 HTML·엔티티를 풀고 태그를 걷는다 — 화면에 「S&amp;amp;P」·「&lt;p&gt;」가 찍히지 않는다")
    void decodesEscapedHtmlInTheDescription() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel><title>t</title><link>https://x</link>
                <item><title>S&amp;amp;P 500 &amp;#8212; record</title><link>https://apnews.com/a</link>
                <description>&amp;lt;p&amp;gt;Stocks &amp;amp; bonds rose&amp;lt;/p&amp;gt;</description>
                <pubDate>Thu, 14 Aug 2026 07:54:20 GMT</pubDate></item>
                </channel></rss>""";

        assertThat(client.parse(NewsSource.AP, xml)).singleElement().satisfies(article -> {
            assertThat(article.title()).isEqualTo("S&P 500 — record");
            assertThat(article.description()).isEqualTo("Stocks & bonds rose");
        });
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

        List<Article> articles = client.parse(NewsSource.CNBC, xml);

        assertThat(articles).singleElement().satisfies(article ->
                assertThat(article.publishedAt())
                        .isEqualTo(java.time.Instant.parse("2026-08-14T07:54:20Z")));
    }

    @Test
    @DisplayName("있을 수 없는 pubDate 하나가 피드 전체를 버리지 않는다 — 그 기사만 빠진다")
    void oneImpossiblePubDateDropsOnlyThatArticle() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel><title>t</title><link>https://x</link>
                <item><title>멀쩡한 기사</title><link>https://x/a</link>
                <pubDate>2026-08-14 07:54:20</pubDate></item>
                <item><title>없는 날짜</title><link>https://x/b</link>
                <pubDate>2026-02-30 24:00:00</pubDate></item>
                </channel></rss>""";

        assertThat(client.parse(NewsSource.CNBC, xml))
                .extracting(Article::title).containsExactly("멀쩡한 기사");
    }

    @Test
    @DisplayName("이름 엔티티를 푼다 — 「&mdash;」·「&rsquo;」가 글자 그대로 남으면 화면이 한 번 더 이스케이프한다")
    void decodesNamedEntities() {
        assertThat(RssFeedClient.decodeEntities("A&mdash;B it&rsquo;s S&amp;P&nbsp;500 &#8212; &#x2014;"))
                .isEqualTo("A—B it’s S&P 500 — —");
        assertThat(RssFeedClient.decodeEntities("&unknownthing; &#xZZ;"))
                .as("모르는 것은 그대로 둔다 — 지어 넣지 않는다").isEqualTo("&unknownthing; &#xZZ;");
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
    @DisplayName("DW — RSS 1.0(RDF)과 dc:date를 읽는다. 20건 모두 날짜가 붙는다")
    void parsesDwRdfFeedWithDcDates() {
        List<Article> articles = client.parse(NewsSource.DW, fixture("dw-business.xml"));

        assertThat(articles).hasSize(20);
        assertThat(articles).allSatisfy(article -> {
            assertThat(article.title()).startsWith("Synthetic headline");
            assertThat(article.link()).startsWith("https://www.dw.com/");
            assertThat(article.publishedAt()).isNotNull();
        });
        // 첫 항목의 <dc:date>2026-11-24T…Z</dc:date> — 시간대가 붙은 ISO라 그대로 읽힌다
        assertThat(articles.get(0).publishedAt()).isAfter(java.time.Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("Guardian — 이스케이프된 HTML 요약에서 태그를 걷는다")
    void stripsEscapedMarkupFromGuardianDescriptions() {
        List<Article> articles = client.parse(NewsSource.GUARDIAN, fixture("guardian-business.xml"));

        assertThat(articles).hasSize(40);
        assertThat(articles).allSatisfy(article -> {
            assertThat(article.publishedAt()).isNotNull();
            assertThat(article.description()).isNotBlank().doesNotContain("<").doesNotContain("&lt;");
        });
    }

    @Test
    @DisplayName("The Block · Decrypt — 전 항목이 평문 요약과 날짜를 갖는다")
    void parsesCryptoOutletFeeds() {
        List<Article> theBlock = client.parse(NewsSource.THE_BLOCK, fixture("theblock.xml"));
        List<Article> decrypt = client.parse(NewsSource.DECRYPT, fixture("decrypt.xml"));

        assertThat(theBlock).hasSize(20);
        assertThat(decrypt).hasSize(37);
        assertThat(java.util.stream.Stream.concat(theBlock.stream(), decrypt.stream())).allSatisfy(article -> {
            assertThat(article.publishedAt()).isNotNull();
            assertThat(article.description()).isNotBlank().doesNotContain("<");
        });
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
        List<Article> articles = client.parse(NewsSource.GUARDIAN, fixture("guardian-business.xml"));

        assertThat(articles)
                .as("타이포그래픽 아포스트로피(’)를 쓰는 제목이 섞여 있다")
                .anySatisfy(article -> assertThat(article.title()).contains("’"));
    }

    @Test
    @DisplayName("망가진 XML은 FeedParseException으로 감싼다 — 소스별 서킷브레이커가 셀 수 있도록")
    void wrapsMalformedXmlAsFeedParseException() {
        assertThatThrownBy(() -> client.parse(NewsSource.GUARDIAN, "not xml at all"))
                .isInstanceOf(FeedParseException.class)
                .hasMessageContaining("GUARDIAN");
    }

    /**
     * <b>항목 링크의 과반</b>이 그 매체 것인지로 확인한다.
     *
     * <p>파일 어딘가에 그 문자열이 있는지만 보면 이름값을 못 한다 — WSJ 피드에
     * 그 매체 링크가 하나만 섞여 있어도 통과한다. 반대로 전부를 요구할 수도 없다: 피드가
     * 남의 매체 기사를 실어 나를 수 있다.
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

    private String fixture(String name) {
        return TestFixtures.text("fixtures/" + name);
    }
}
