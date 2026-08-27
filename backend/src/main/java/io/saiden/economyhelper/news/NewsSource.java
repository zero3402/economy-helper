package io.saiden.economyhelper.news;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 수집 대상 매체 — <b>전부 전문 무료다.</b>
 *
 * <p><b>페이월 매체는 넣지 않는다.</b> 링크를 눌러도 못 읽는 기사는 좋은 답이 아니다.
 * 점수로 뒤에 미루는 것으로는 부족하다 — 유료 기사가 뚜렷이 높으면 결국 그게 나간다.
 * 그래서 값으로 가르지 않고 <b>목록 자체에서 뺀다.</b> 새 매체를 더할 때도 같은 기준이다:
 * 전문이 무료가 아니면 넣지 않는다.
 *
 * <p>페이월 우회(archive.ph 따위)로 유료 매체를 되살리지 않는다. 매체의 수익 모델을
 * 우회하는 일이고, 그런 서비스는 언제 막혀도 이상하지 않아 기반으로 삼을 수 없다.
 *
 * <p><b>경제 다섯 + 코인 둘이다.</b> 경제 쪽 다섯은 <b>글로벌 점유율 순</b>이고, 코인 쪽은
 * 코인 전문 매체 둘이다. 더 늘리지 않는 이유는 비용이다 — 항목마다 피드 수집 + HN 조회 +
 * Gemini 관련도 채점이 한 번씩 붙는다.
 *
 * <p><b>매체는 일곱이지만 항목은 여덟이다.</b> Investing.com만 본 섹션과 암호화폐 섹션 둘을
 * 단다 — 매체를 늘린 것이 아니라 그 매체 안에서 섹션을 하나 더 여는 것이라 표시 이름이 같다.
 * 코인 기사는 금융 일반 피드에 거의 안 실려서, 하류에서 걸러 낼 것이 아니라 상류에서
 * 그 섹션을 열어야 들어온다.
 *
 * <p><b>왜 코인 전문 매체를 둘 더 세웠는가.</b> 브리핑이 코인 뉴스 <b>다섯 건</b>을 요구하는데
 * Investing.com 암호화폐 섹션 하나로는 못 채운다 — 실측(2026-08-27)으로 그 피드는 10건 중
 * <b>24시간 안이 6건</b>이었고(나머지는 이틀·사흘 전) 그중 일부는 보도자료성이라 재테크
 * 관련도 문턱에 걸린다. CoinDesk 25건 · Cointelegraph 30건을 더해 코인 풀이 60건대가 된다.
 *
 * <p>새 매체도 <b>같은 문을 통과했다</b>: 앱의 User-Agent로 둘 다 200이고, 전문이 무료이며
 * 로그인·구독 벽이 없다. 요약문은 {@code RssFeedClient.clean()}이 태그를 걷어내면 평문만
 * 남고(Cointelegraph는 {@code <p><img>} 마크업으로 시작한다), {@code <author>}가 자사
 * 기자라 {@code syndicatedFromPaywall}에도 걸리지 않는다.
 *
 * <p><b>매체마다 자기 주소를 들고 있다.</b> 무료 매체를 골라 놓아도 <b>그 매체의 피드가
 * 남의 기사를 실어 나른다</b> — Yahoo 피드는 48건 중 8건이 wsj.com·investors.com이었고
 * 둘 다 페이월이다(2026-08-14 실측). 그래서 <b>그 매체 피드에서는 그 매체 기사만 쓴다.</b>
 * 페이월 매체 목록을 따로 관리하지 않는 이유가 이것이다 — 그 목록은 세상의 모든 유료
 * 매체를 쫓아다녀야 끝나지만, 이쪽은 우리가 고른 다섯만 알면 된다.
 *
 * <p><b>피드 형식은 여기 두지 않는다.</b> 파서를 고르는 쪽은 {@code application.yml}의
 * {@code type}을 읽는다. 같은 사실을 두 곳에 두면 언젠가 서로 어긋난다.
 * AP만 {@code google-news}인데 공식 RSS가 없어 구글 뉴스 검색 피드를 프록시로 쓰기 때문이다.
 * 그 피드는 description이 링크 마크업뿐이라 제목만 신뢰할 수 있다.
 */
public enum NewsSource {

    YAHOO_FINANCE("Yahoo Finance", "yahoo.com"),
    INVESTING("Investing.com", "investing.com"),
    /**
     * 같은 매체의 <b>암호화폐 섹션</b>이다. 매체를 늘린 것이 아니라 섹션을 하나 더 단 것이라
     * 표시 이름도 {@link #INVESTING}과 같다.
     *
     * <p><b>왜 필요한가.</b> 코인 기사가 잡히는 피드가 Yahoo뿐이었고 그마저 49건 중 3건이었다.
     * CNBC Markets·BBC Business·Investing 본 피드는 오늘자 코인 기사가 0건이다(2026-08-15 실측).
     * 그래서 {@code /news 비트코인}이 자주 빈손이었다 — 랭킹이 아니라 소스의 문제였다.
     */
    INVESTING_CRYPTO("Investing.com", "investing.com"),
    /**
     * 코인 전문 매체. 클래스 주석의 실측이 이 둘을 세운 근거다 — 암호화폐 섹션 하나로는
     * 24시간 안에 다섯 건이 안 차서, 랭킹이 아니라 <b>소스</b>를 넓혀야 했다.
     *
     * <p>본 피드가 코인만 싣는 것은 아니다 — 실측에서 CoinDesk가
     * {@code "Nvidia shares rise after earnings top estimates"}를 실었다. 그런 기사도
     * {@link #cryptoSection()} 때문에 코인 무리로 간다({@code NewsCategory} 참조).
     */
    COINDESK("CoinDesk", "coindesk.com"),
    /** 코인 전문 매체. 링크에 {@code ?utm_source=rss_feed} 꼬리가 붙지만 호스트는 자기 것이다. */
    COINTELEGRAPH("Cointelegraph", "cointelegraph.com"),
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

    /**
     * <b>링크가 목적지를 감추는 매체인가.</b>
     *
     * <p>AP만 참이다 — 공식 RSS가 없어 구글 뉴스 검색 피드를 프록시로 쓰는데, 그 링크가
     * {@code news.google.com/rss/articles/CBMi...} 불투명 주소다({@link #AP} 참조).
     *
     * <p><b>반응 점수를 매길 수 없다는 뜻이다.</b> Hacker News 조회는 정규화한 URL이
     * 정확히 일치해야 걸리는데, 이 매체의 링크는 HN에 올라간 실제 AP 주소와 절대 같아지지
     * 않는다. 그래서 AP는 랭킹 네 항 중 반응 항을 <b>구조적으로</b> 0으로 받는다 —
     * 조회해 봐야 못 맞히므로 브리핑마다 헛호출을 한 번 태우기만 했다.
     *
     * <p>구글 리다이렉트를 풀어 실주소를 얻는 방법은 있지만 기사마다 조회를 한 번 더
     * 태운다 — 반응 항 하나를 얻으려고 수집 비용을 두 배로 내지 않는다. 대신 <b>이 사실을
     * 값으로 들고 있어</b>, 다음에 이 매체를 손보는 사람이 "왜 AP만 반응이 0인가"를
     * 다시 파헤치지 않게 한다.
     */
    public boolean opaqueLinks() {
        return this == AP;
    }

    /**
     * <b>코인 기사만 싣기로 하고 연 자리인가.</b>
     *
     * <p>참인 것은 Investing.com 암호화폐 섹션과 코인 전문 매체 둘이다. {@code NewsCategory}가
     * 이 값을 첫 번째 규칙으로 쓴다 — 그 피드에서 온 기사는 내용을 보지 않고 코인으로 센다.
     *
     * <p><b>대가를 알고 고른 것이다.</b> 코인 매체가 낸 일반 기사(엔비디아 실적 따위)가 코인
     * 무리에 앉는다. 반대 방향(코인 기사가 경제 무리로 새는 것)이 더 나쁘다 — 코인 자리
     * 다섯이 안 차는 것이 이 열거형에 매체를 둘 더한 이유이기 때문이다.
     *
     * <p>{@link #opaqueLinks()}와 같은 모양으로 <b>값이 이 사실을 들고 있다.</b> 어딘가의
     * {@code switch}에 두면 매체를 더할 때 빠뜨릴 자리가 생긴다.
     */
    public boolean cryptoSection() {
        return this == INVESTING_CRYPTO || this == COINDESK || this == COINTELEGRAPH;
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
