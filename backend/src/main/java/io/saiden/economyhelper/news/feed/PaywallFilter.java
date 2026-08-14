package io.saiden.economyhelper.news.feed;

import io.saiden.economyhelper.news.Article;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 페이월 매체로 가는 링크를 걸러낸다.
 *
 * <p><b>왜 필요한가.</b> 매체를 고른 기준이 "전문 무료"인데
 * <b>Yahoo 피드가 남의 매체 기사를 실어 나른다.</b> 2026-08-14 실측으로 48건 중
 * {@code wsj.com} 1건, {@code investors.com} 7건이었다. 링크를 눌러도 못 읽는 기사는
 * 답이 아니라는 것이 {@code CLAUDE.md}의 기준이고, 그 기준은 피드를 고를 때뿐 아니라
 * 피드가 실어 온 것에도 적용돼야 한다.
 *
 * <p><b>판정 근거는 추측이 아니다.</b> {@code investors.com} 기사에서
 * {@code "isAccessibleForFree": false}를 확인했다 — schema.org 표준 표시이고 매체가
 * 스스로 붙인 것이다. {@code wsj.com}은 본문이 767바이트만 내려왔다. 우리 다섯 매체의
 * 기사에는 그 표시가 없다.
 *
 * <p><b>목록으로 막지, 매번 눌러 보지 않는다.</b> 기사마다 원문을 받아 표시를 읽으면
 * 정확하지만 수집 한 번에 수십 번의 외부 호출이 붙는다 — 응답 속도를 1분에서 되돌린
 * 작업을 그대로 무르는 셈이다. 페이월은 매체 단위 정책이라 호스트로 충분하다.
 *
 * <p><b>구글 뉴스(AP)는 못 거른다.</b> 링크가 {@code news.google.com/rss/articles/...}
 * 불투명 주소라 목적지를 알 수 없다. 다만 그 피드의 쿼리가 {@code site:apnews.com}으로
 * 묶여 있어 목적지가 항상 AP다 — 거를 것이 없다.
 */
final class PaywallFilter {

    /**
     * 막을 매체.
     *
     * <p>앞의 셋은 <b>Yahoo가 실제로 실어 보낸 것을 확인</b>했거나(WSJ·IBD) 같은
     * 발행사(Barron's는 WSJ와 함께 다우존스다). 뒤의 넷은 {@code CLAUDE.md}가 페이월로
     * 지목해 매체 목록에서 뺀 곳이다 — <b>지금 피드에 안 보인다고 빼 두면 Yahoo가 내일
     * 실어 보냈을 때 그대로 통과한다.</b> 뺀 이유가 여기에도 그대로 적용된다.
     */
    private static final Set<String> BLOCKED = Set.of(
            "wsj.com", "investors.com", "barrons.com",
            "bloomberg.com", "reuters.com", "ft.com", "economist.com");

    private PaywallFilter() {
    }

    static List<Article> apply(List<Article> articles) {
        return articles.stream().filter(article -> !blocks(article.link())).toList();
    }

    /**
     * 이 링크가 막힌 매체로 가는가.
     *
     * <p>정확히 같거나 서브도메인일 때만 막는다. {@code endsWith("wsj.com")}만 쓰면
     * {@code notwsj.com}까지 걸리므로 점을 붙여 본다.
     *
     * <p>주소를 못 읽으면 <b>통과시킨다.</b> 못 읽는 주소는 페이월이라는 근거가 아니고,
     * 여기서 막으면 파싱이 애매한 기사가 조용히 사라진다.
     */
    static boolean blocks(String link) {
        String host;
        try {
            host = URI.create(link).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return BLOCKED.stream()
                .anyMatch(blocked -> normalized.equals(blocked) || normalized.endsWith("." + blocked));
    }
}
