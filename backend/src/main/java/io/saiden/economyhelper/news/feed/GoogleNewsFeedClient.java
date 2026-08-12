package io.saiden.economyhelper.news.feed;

import com.rometools.rome.feed.synd.SyndEntry;
import io.saiden.economyhelper.news.FeedType;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Google News 검색 피드 파서 — 현재는 Reuters 프록시로만 쓴다.
 *
 * <p>표준 RSS 2.0이라 파싱 골격은 {@link RssFeedClient}와 같지만 Google이 두 곳에 손을 댄다:
 *
 * <ul>
 *   <li>제목이 {@code "헤드라인 - Reuters"} 형태다 — 매체명 꼬리를 뗀다
 *   <li>description이 요약문이 아니라 Google 리다이렉트 링크 마크업이다 — 버린다
 * </ul>
 *
 * <p>그래서 이 소스의 기사는 번역 입력이 제목 한 줄뿐이다.
 */
@Component
public class GoogleNewsFeedClient extends RssFeedClient {

    /**
     * 꼬리의 {@code " - 매체명"}. 매체명 자체에는 하이픈이 없다고 보고 마지막 구분자만 자른다
     * — 헤드라인 중간의 하이픈(예: "US-China")을 건드리지 않기 위해서다.
     */
    private static final Pattern OUTLET_SUFFIX = Pattern.compile("\\s+-\\s+[^-]{1,40}$");

    @Override
    public FeedType type() {
        return FeedType.GOOGLE_NEWS;
    }

    @Override
    protected String normalizeTitle(String title) {
        return OUTLET_SUFFIX.matcher(title).replaceFirst("").trim();
    }

    @Override
    protected String extractDescription(SyndEntry entry) {
        // 리다이렉트 링크 마크업뿐이라 번역 근거로 쓸 수 없다.
        return null;
    }
}
