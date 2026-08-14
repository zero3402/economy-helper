package io.saiden.economyhelper.news;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 수집 대상 매체 — <b>전부 전문 무료다.</b>
 *
 * <p><b>페이월 매체는 넣지 않는다.</b> 예전에는 Bloomberg·Reuters·FT·Economist를 함께 봤는데,
 * 링크를 눌러도 못 읽는 기사가 매일 나갔다. 한동안 "점수가 붙으면 무료 쪽을 앞에 둔다"로
 * 완화했지만 유료 기사가 뚜렷이 높으면 여전히 그게 나갔다 —
 * <b>읽을 수 없는 기사는 좋은 답이 아니다.</b> 그래서 값으로 가르지 않고 목록에서 뺐다.
 * 새 매체를 더할 때도 같은 기준이다: 전문이 무료가 아니면 넣지 않는다.
 *
 * <p>페이월 우회(archive.ph 따위)로 유료 매체를 되살리지 않는다. 매체의 수익 모델을
 * 우회하는 일이고, 그런 서비스는 언제 막혀도 이상하지 않아 기반으로 삼을 수 없다.
 *
 * <p>다섯은 <b>글로벌 점유율 순</b>이다. 더 늘리지 않는 이유는 비용이다 — 매체마다
 * 피드 수집 + HN 조회 + Gemini 관련도 채점이 한 번씩 붙는다.
 *
 * <p><b>매체마다 자기 주소를 들고 있다.</b> 무료 매체를 골라 놓아도 <b>그 매체의 피드가
 * 남의 기사를 실어 나른다</b> — Yahoo 피드는 48건 중 8건이 wsj.com·investors.com이었고
 * 둘 다 페이월이다(2026-08-14 실측). 그래서 <b>그 매체 피드에서는 그 매체 기사만 쓴다.</b>
 * 페이월 매체 목록을 따로 관리하지 않는 이유가 이것이다 — 그 목록은 세상의 모든 유료
 * 매체를 쫓아다녀야 끝나지만, 이쪽은 우리가 고른 다섯만 알면 된다.
 *
 * <p><b>피드 형식은 여기 두지 않는다.</b> 파서를 고르는 쪽은 {@code application.yml}의
 * {@code type}을 읽는다. 같은 사실을 두 곳에 두면 언젠가 서로 어긋난다.
 * AP만 {@code google-news}인데 공식 RSS가 없기 때문이다 — Reuters가 쓰던 방식 그대로고,
 * 그 피드는 description이 링크 마크업뿐이라 제목만 신뢰할 수 있다.
 */
public enum NewsSource {

    YAHOO_FINANCE("Yahoo Finance", "yahoo.com"),
    INVESTING("Investing.com", "investing.com"),
    CNBC("CNBC", "cnbc.com"),
    // BBC는 영국 안팎으로 bbc.co.uk와 bbc.com을 함께 쓴다
    BBC("BBC Business", "bbc.co.uk", "bbc.com"),
    /**
     * 공식 RSS가 없어 구글 뉴스 검색 피드를 프록시로 쓴다. 링크가
     * {@code news.google.com/rss/articles/...} 불투명 주소라 목적지를 볼 수 없다 —
     * 대신 <b>피드 쿼리 자체가 {@code site:apnews.com}으로 묶여 있어</b> 목적지가 항상 AP다.
     * 여기서 걸러 낼 것이 없으므로 구글 주소를 그대로 자기 주소로 삼는다.
     */
    AP("AP News", "news.google.com");

    private final String displayName;
    private final Set<String> hosts;

    NewsSource(String displayName, String... hosts) {
        this.displayName = displayName;
        this.hosts = Set.of(hosts);
    }

    /** 텔레그램 메시지에 노출할 이름. */
    public String displayName() {
        return displayName;
    }

    /**
     * 이 링크가 <b>이 매체 자신의 기사</b>인가.
     *
     * <p>정확히 같거나 서브도메인일 때만 참이다({@code finance.yahoo.com}은 통과,
     * {@code notyahoo.com}은 아니다).
     *
     * <p>주소를 못 읽으면 거짓이다 — 어느 매체 것인지 모르는 링크를 그 매체 것으로
     * 칠 수는 없다. 허용 목록이라 판단이 애매하면 빼는 쪽이 맞다.
     */
    public boolean owns(String link) {
        String host;
        try {
            host = link == null ? null : URI.create(link).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return hosts.stream()
                .anyMatch(own -> normalized.equals(own) || normalized.endsWith("." + own));
    }
}
