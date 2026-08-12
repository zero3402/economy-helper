package io.saiden.economyhelper.news.feed;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.io.Reader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 표준 RSS 2.0 파서 — Bloomberg, FT, Economist, CoinDesk.
 *
 * <p>Rome이 매체별 스키마 차이는 흡수하지만 공백까지 정리해 주지는 않는다.
 * Economist는 CDATA를 개행과 들여쓰기로 감싸 내려주므로
 * ({@code <title>\n  <![CDATA[..]]>\n</title>}) 값을 반드시 정규화해야 한다.
 */
@Component
public class RssFeedClient implements FeedClient {

    private static final Logger log = LoggerFactory.getLogger(RssFeedClient.class);

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
            feed = input.build(xml);
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
}
