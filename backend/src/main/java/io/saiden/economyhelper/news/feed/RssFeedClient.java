package io.saiden.economyhelper.news.feed;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 표준 RSS 2.0 파서 — Yahoo Finance, Investing.com, CNBC, BBC.
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
     * 항목을 통째로 버린다. <b>HTTP는 200이므로 아무 데도 오류가 안 뜨고 그 매체만 조용히
     * 사라진다</b> — 실제로 그렇게 되어 있었다(10건 받아 0건).
     */
    private static final Pattern BARE_PUB_DATE = Pattern.compile(
            "<pubDate>\\s*(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})\\s*</pubDate>");

    @Override
    public FeedType type() {
        return FeedType.RSS;
    }

    @Override
    public List<Article> parse(NewsSource source, Reader xml) {
        SyndFeed feed;
        try {
            SyndFeedInput input = new SyndFeedInput();
            // 외부 엔티티 주입 차단. Rome 기본값이지만 피드는 외부 입력이라 명시한다.
            input.setAllowDoctypes(false);
            feed = input.build(new StringReader(normalizePubDates(readAll(xml))));
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
        return new Article(source, title, extractDescription(entry), link, publishedAt, rank);
    }

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
        String stripped = HTML_TAG.matcher(raw).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    private static Instant toInstant(Date date) {
        return date.toInstant();
    }

    private static String readAll(Reader xml) throws IOException {
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[8192];
        for (int read; (read = xml.read(buffer)) != -1; ) {
            body.append(buffer, 0, read);
        }
        return body.toString();
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
        Matcher matcher = BARE_PUB_DATE.matcher(xml);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            ZonedDateTime at = ZonedDateTime.of(
                    Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)),
                    0, ZoneOffset.UTC);
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(
                    "<pubDate>" + DateTimeFormatter.RFC_1123_DATE_TIME.format(at) + "</pubDate>"));
        }
        return matcher.appendTail(normalized).toString();
    }
}
