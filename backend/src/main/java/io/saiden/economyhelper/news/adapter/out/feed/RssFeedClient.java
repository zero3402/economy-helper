package io.saiden.economyhelper.news.adapter.out.feed;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.FeedType;
import io.saiden.economyhelper.news.domain.NewsSource;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * 표준 RSS 2.0 파서 — AP(구글 뉴스 프록시)를 뺀 매체 전부.
 *
 * <p>Rome이 매체별 스키마 차이는 흡수하지만 <b>규격 위반까지 흡수하지는 않는다.</b>
 * 값은 CDATA 주변 공백을 걷어내야 하고({@link #clean}), 날짜는 RFC 822를 어긴 매체가
 * 있어 넘기기 전에 되돌려야 한다({@link #normalizePubDates}).
 */
@Component
public class RssFeedClient implements FeedClient {

    private static final Logger log = LoggerFactory.getLogger(RssFeedClient.class);

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 시간대가 빠진 {@code <pubDate>2026-08-14 07:54:20</pubDate>} — Investing.com이 이 모양이다.
     *
     * <p>RSS 2.0은 pubDate를 RFC 822로 규정하지만 Investing은 지키지 않는다. Rome은 이 값을
     * 파싱하지 못해 {@code getPublishedDate()}에 {@code null}을 주고, 그러면 {@link #toArticle}이
     * 항목을 통째로 버린다. <b>HTTP는 200이라 아무 데도 오류가 뜨지 않고 그 매체만 조용히
     * 사라진다</b> — 그래서 넘기기 전에 되돌린다.
     */
    private static final Pattern BARE_PUB_DATE = Pattern.compile(
            "<pubDate>\\s*(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2})\\s*</pubDate>");

    /** {@link #BARE_PUB_DATE}가 잡은 값. STRICT라 {@code 02-30}·{@code 24:00}을 날짜로 만들어 내지 않는다. */
    private static final DateTimeFormatter BARE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd[ ]['T']HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    @Override
    public FeedType type() {
        return FeedType.RSS;
    }

    @Override
    public List<Article> parse(NewsSource source, String xml) {
        SyndFeed feed;
        try {
            SyndFeedInput input = new SyndFeedInput();
            // 외부 엔티티 주입 차단. Rome 기본값이지만 피드는 외부 입력이라 명시한다.
            input.setAllowDoctypes(false);
            feed = input.build(new StringReader(normalizePubDates(xml)));
        } catch (Exception e) {
            throw new FeedParseException(source, "피드 XML을 파싱할 수 없습니다", e);
        }

        List<SyndEntry> entries = feed.getEntries();
        List<Article> articles = new ArrayList<>(entries.size());
        for (SyndEntry entry : entries) {
            // rank는 살아남은 개수 기준이다 — 건너뛴 항목이 순위에 구멍을 내지 않는다.
            Article article = toArticle(source, entry, articles.size());
            if (article != null) {
                articles.add(article);
            }
        }
        return List.copyOf(articles);
    }

    /** 필수 필드가 빠진 항목은 건너뛴다. 한 건의 결함으로 피드 전체를 잃지 않기 위해서다. */
    private Article toArticle(NewsSource source, SyndEntry entry, int rank) {
        String title = normalizeTitle(clean(entry.getTitle()));
        String link = entry.getLink() == null ? null : entry.getLink().trim();
        Instant publishedAt = entry.getPublishedDate() == null
                ? null
                : toInstant(entry.getPublishedDate());

        if (title.isBlank() || link == null || link.isBlank() || publishedAt == null) {
            log.warn("[{}] 필수 필드가 없어 항목을 건너뜁니다 (title={}, link={}, pubDate={})",
                    source, !title.isBlank(), link != null && !link.isBlank(), publishedAt != null);
            return null;
        }
        if (syndicatedFromPaywall(entry.getAuthor())) {
            log.debug("[{}] 페이월 매체의 재게재본이라 건너뜁니다 (author={})", source, entry.getAuthor());
            return null;
        }
        return new Article(source, title, extractDescription(entry), link, publishedAt, rank);
    }

    /**
     * 이 항목이 <b>페이월 매체의 기사를 얹어 놓은 것</b>인가.
     *
     * <p><b>호스트 필터만으로는 못 막는다.</b> {@link NewsSource#owns}는 링크가 그 매체를 떠났는지를
     * 보는데, Investing.com은 Reuters·Bloomberg 기사를 <b>자기 도메인에 얹어</b> 낸다 —
     * 주소가 {@code investing.com}이라 허용 목록을 그대로 통과한다(2026-08-17 실측 피드의
     * 첫 항목이 {@code <author>Reuters</author>}였다). 그런 재게재본은 가입 벽이 붙는 일이 있어
     * "링크를 눌러도 못 읽는 기사는 답이 아니다"라는 이 프로젝트의 기준에 걸린다.
     *
     * <p>그래서 두 겹이다 — <b>밖으로 나가는 링크는 호스트가, 안에 얹힌 남의 기사는 여기가</b> 막는다.
     *
     * <p>목록이 짧은 이유는 {@link NewsSource}의 주석과 같다 — <b>우리가 고른 피드에 실제로
     * 실려 오는 것</b>만 적는다.
     *
     * <p>author가 비어 있으면 거짓이다 — AP 프록시 피드가 그렇다. 호스트 필터가 이미 받치고 있어
     * 모르는 것을 버리는 쪽으로 기울일 이유가 없다.
     */
    private static boolean syndicatedFromPaywall(String author) {
        if (author == null || author.isBlank()) {
            return false;
        }
        String normalized = author.toLowerCase(Locale.ROOT);
        return PAYWALL_AUTHORS.stream().anyMatch(normalized::contains);
    }

    /** 전부 소문자로 둘 것 — 비교가 소문자로 이뤄진다. */
    private static final List<String> PAYWALL_AUTHORS = List.of(
            "reuters", "bloomberg", "dow jones", "wall street journal", "wsj",
            "barron", "financial times", "the economist");

    /** Google News처럼 제목에 매체명 꼬리가 붙는 피드가 재정의한다. */
    protected String normalizeTitle(String title) {
        return title;
    }

    /** 요약문을 쓸 수 없는 피드가 재정의해 {@code null}을 돌려준다. */
    protected String extractDescription(SyndEntry entry) {
        SyndContent description = entry.getDescription();
        if (description == null) {
            return null;
        }
        String value = clean(description.getValue());
        return value.isBlank() ? null : value;
    }

    /** CDATA 주변 공백, HTML 태그, 연속 공백을 걷어낸다. */
    protected static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        // ⚠️ 엔티티를 먼저 푼다 — 피드가 HTML을 이스케이프해 넣으면(&lt;p&gt;·S&amp;P) Rome이 한 겹만 벗긴 채 준다.
        //    안 풀면 화면이 한 번 더 이스케이프해 「S&amp;amp;P」가 찍히고, 낱말 매칭도 엉뚱한 글자를 본다.
        //    풀고 나서 태그를 걷어야 이스케이프돼 있던 태그도 같이 빠진다
        String stripped = HTML_TAG.matcher(decodeEntities(raw)).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * 이름·숫자 엔티티를 푼다({@link HtmlUtils} — HTML 4 이름 전부). 모르는 것은 그대로 둔다 — 지어 넣지 않는다.
     *
     * <p>{@code &nbsp;}는 보통 공백으로 바꾼다 — 그대로 두면 {@code \s}가 못 접어 낱말 매칭이 갈린다.
     */
    static String decodeEntities(String text) {
        return HtmlUtils.htmlUnescape(text).replace('\u00A0', ' ');
    }

    private static Instant toInstant(Date date) {
        return date.toInstant();
    }

    /**
     * 규격을 어긴 pubDate를 Rome이 읽을 수 있는 RFC 1123으로 되돌린다.
     *
     * <p><b>왜 XML을 문자열로 손보는가.</b> Rome은 pubDate를 인식은 하되 파싱에 실패하면
     * 원문을 버린다 — {@code getForeignMarkup()}에도 남지 않고 {@code Item.getPubDate()}도
     * 같은 파서를 거쳐 {@code null}이다. 즉 파싱이 끝난 뒤에는 되살릴 방법이 없어서
     * 넘기기 전에 고치는 수밖에 없다.
     *
     * <p><b>시간대는 UTC로 읽는다.</b> 피드에 시간대 표기가 아예 없어 추정이 필요한데,
     * 2026-08-14 실측에서 최신 항목이 {@code 07:54:20}이었고 그때 UTC가 {@code 08:00:47},
     * KST가 {@code 17:00:47}이었다 — 6분 전 기사이므로 UTC다. KST로 읽으면 9시간 낡은
     * 값이 되어 신선도 가중치에서 통째로 밀려난다. 값이 조용히 틀리는 쪽이 더 나쁘다.
     *
     * <p>정규식은 <b>정확히 이 모양일 때만</b> 문다. 규격을 지킨 pubDate는 손대지 않는다.
     */
    static String normalizePubDates(String xml) {
        return BARE_PUB_DATE.matcher(xml).replaceAll(match -> Matcher.quoteReplacement(rfc1123(match)));
    }

    /**
     * 잡은 pubDate 하나를 RFC 1123으로. <b>있을 수 없는 날짜면 원문 그대로 둔다</b> — Rome이 그 항목의
     * 날짜만 버리고({@link #toArticle}이 그 기사를 뺀다) 피드의 나머지는 산다.
     */
    private static String rfc1123(MatchResult match) {
        try {
            LocalDateTime at = LocalDateTime.parse(match.group(1), BARE);
            return "<pubDate>" + DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC)) + "</pubDate>";
        } catch (DateTimeParseException e) {
            return match.group();
        }
    }
}
