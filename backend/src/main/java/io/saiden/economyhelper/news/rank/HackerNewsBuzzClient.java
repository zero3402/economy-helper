package io.saiden.economyhelper.news.rank;

import io.saiden.economyhelper.news.Article;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 기사 목록에 붙일 HN 실측 반응을 모은다.
 *
 * <p>매체 어디도 조회수·댓글 수를 공개하지 않아(Most Read 페이지도 전부 403/404)
 * HN이 유일하게 얻을 수 있는 <b>실측 댓글 수</b>다. 다만 커버리지가 20~30%,
 * CoinDesk는 사실상 0이라 대부분의 기사는 여기 없다 — 없으면 그냥 빠지고
 * {@link PopularityScorer}가 나머지 세 지표로 순위를 매긴다.
 */
@Component
public class HackerNewsBuzzClient {

    private final HackerNewsApi api;
    private final Duration window;

    public HackerNewsBuzzClient(
            HackerNewsApi api,
            @Value("${economy-helper.ranking.hacker-news.window:7d}") Duration window) {
        this.api = api;
        this.window = window;
    }

    /**
     * {@code 기사 링크 -> points + num_comments}. HN에 없는 기사는 아예 담기지 않는다
     * ({@link PopularityScorer}가 0으로 본다).
     */
    public Map<String, Integer> buzzByLink(List<Article> articles, Instant now) {
        if (articles == null || articles.isEmpty()) {
            return Map.of();
        }

        Set<String> domains = new HashSet<>();
        for (Article article : articles) {
            String domain = domainOf(article.link());
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }

        Instant since = now.minus(window);
        Map<String, Integer> byNormalizedUrl = new HashMap<>();
        for (String domain : domains) {
            byNormalizedUrl.putAll(api.storiesForDomain(domain, since));
        }

        Map<String, Integer> byLink = new HashMap<>();
        for (Article article : articles) {
            Integer buzz = byNormalizedUrl.get(normalizeUrl(article.link()));
            if (buzz != null && buzz > 0) {
                byLink.put(article.link(), buzz);
            }
        }
        return Map.copyOf(byLink);
    }

    /**
     * 같은 기사를 가리키는 URL이 HN과 피드에서 다르게 생겼을 수 있어 맞춰 준다 —
     * 스킴, {@code www.}, 쿼리스트링(추적 파라미터), 프래그먼트, 끝 슬래시를 떼고 소문자로 만든다.
     */
    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String s = url.trim().toLowerCase(Locale.ROOT);

        int fragment = s.indexOf('#');
        if (fragment >= 0) {
            s = s.substring(0, fragment);
        }
        int query = s.indexOf('?');
        if (query >= 0) {
            s = s.substring(0, query);
        }

        s = s.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 정규화한 URL에서 호스트만 뽑는다. HN 조회 단위다. */
    static String domainOf(String url) {
        String normalized = normalizeUrl(url);
        int slash = normalized.indexOf('/');
        return slash < 0 ? normalized : normalized.substring(0, slash);
    }
}
