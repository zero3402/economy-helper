package io.saiden.economyhelper.news.rank;

import io.saiden.economyhelper.news.Article;
import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.support.FailureReason;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 기사 목록에 붙일 HN 실측 반응을 모은다.
 *
 * <p>매체 어디도 조회수·댓글 수를 공개하지 않아(Most Read 페이지도 전부 403/404)
 * HN이 유일하게 얻을 수 있는 <b>실측 댓글 수</b>다. 다만 커버리지가 20~30%,
 * 금융 매체는 사실상 0이라 대부분의 기사는 여기 없다 — 없으면 그냥 빠지고
 * {@link PopularityScorer}가 나머지 세 지표로 순위를 매긴다.
 */
@Component
public class HackerNewsBuzzClient {

    private static final Logger log = LoggerFactory.getLogger(HackerNewsBuzzClient.class);

    private final HackerNewsApi api;
    private final Duration window;

    public HackerNewsBuzzClient(
            HackerNewsApi api,
            @Value("${economy-helper.ranking.hacker-news.window:7d}") Duration window) {
        this.api = api;
        this.window = window;
    }

    /**
     * 도메인 하나의 buzz — <b>실패는 빈 맵으로 강등한다.</b>
     *
     * <p>⚠️ 강등이 여기 있는 이유가 있다. 예전에는 HackerNewsApi가 스스로 삼켜 빈 맵을
     * 돌려줬는데, 그러면 그 메서드의 {@code @CircuitBreaker}가 <b>정상 반환을 보고 성공을
     * 센다</b> — 실패율이 영원히 0이라 브레이커가 절대 열리지 않았다. 한 칸 위인
     * 여기서 잡으면 브레이커가 실패를 먼저 세고, 열린 뒤에는
     * CallNotPermittedException이 같은 자리에 걸려 같은 강등이 일어난다.
     * 즉 사용자에게 보이는 결과는 그대로이고 브레이커만 살아난다.
     * 도메인마다 잡는 것도 뜻이 있다 — 바깥에서 한 번 잡으면 첫 실패가 뒤의 매체를 통째로 버린다
     * ({@code Concurrently.map}은 실패를 감추지 않으므로 여기서 잡아야 한다).
     */
    private Map<String, Integer> buzzOf(String domain, Instant since) {
        try {
            return api.storiesForDomain(domain, since);
        } catch (RuntimeException e) {
            log.warn("[{}] HN 조회 실패 — 이 도메인의 buzz를 0으로 강등합니다: {}",
                    domain, FailureReason.of(e));
            return Map.of();
        }
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
            // 링크가 목적지를 감추는 매체는 조회해 봐야 못 맞힌다 — AP의 구글 뉴스 프록시
            // 주소는 HN에 올라간 실제 AP 주소와 절대 같아지지 않는다. 예전에는 그
            // news.google.com을 도메인으로 물어 브리핑마다 헛호출을 한 번 태웠다
            if (article.source() != null && article.source().opaqueLinks()) {
                continue;
            }
            String domain = domainOf(article.link());
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }

        Instant since = now.minus(window);
        // 도메인마다 한 호출이고 서로를 모른다 — 겹친다. /news 검색은 여덟 피드의 기사를 한 번에
        // 넘기므로 도메인이 일곱까지 되고, 콜드 응답이 1.6초(117KB)라 순차면 최대 10초를 기다렸다.
        // hackerNews에는 리미터가 없고 브레이커만 있어 겹쳐도 막을 것이 없다
        Map<String, Integer> byNormalizedUrl = new HashMap<>();
        Concurrently.map(List.copyOf(domains), domain -> buzzOf(domain, since))
                .forEach(byNormalizedUrl::putAll);

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
