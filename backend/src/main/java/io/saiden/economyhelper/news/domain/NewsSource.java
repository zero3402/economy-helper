package io.saiden.economyhelper.news.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 수집 대상 매체 — <b>전부 전문 무료이고, 자체 편집국이 직접 쓴 기사를 낸다.</b>
 *
 * <p><b>페이월 매체는 넣지 않는다.</b> 링크를 눌러도 못 읽는 기사는 좋은 답이 아니다.
 * 점수로 뒤에 미루는 것으로는 부족하다 — 유료 기사가 뚜렷이 높으면 결국 그게 나간다.
 * 그래서 값으로 가르지 않고 <b>목록 자체에서 뺀다.</b> 페이월 우회(archive.ph 따위)로 유료 매체를
 * 되살리지 않는다.
 *
 * <p><b>남의 기사를 모아 싣는 곳은 넣지 않는다.</b> 재게재·속보 요약·홍보성 기사가 섞이는
 * 매체(Yahoo Finance·Investing.com·Cointelegraph)를 2026-11-24에 뺐다. 브리핑 열 자리는 그대로
 * 두고 그 자리를 채우는 매체의 질로 고른다(ADR-0014).
 *
 * <p><b>경제 다섯 + 코인 셋이다.</b> 더 늘리지 않는 이유는 비용이다 — 매체마다 피드 수집 +
 * HN 조회가 붙고, 관련도 프롬프트에 실리는 제목이 는다.
 *
 * <p><b>매체마다 자기 주소를 들고 있다.</b> 무료 매체의 피드도 <b>남의 기사를 실어 나를 수
 * 있다</b>(옛 Yahoo 피드는 48건 중 8건이 wsj.com·investors.com이었다). 그래서 <b>그 매체
 * 피드에서는 그 매체 기사만 쓴다.</b> 페이월 매체 목록을 따로 관리하지 않는 이유가 이것이다 —
 * 그 목록은 세상의 모든 유료 매체를 쫓아다녀야 끝나지만, 이쪽은 우리가 고른 매체만 알면 된다.
 *
 * <p><b>피드 형식은 여기 두지 않는다.</b> 파서를 고르는 쪽은 {@code application.yml}의
 * {@code type}을 읽는다. 같은 사실을 두 곳에 두면 언젠가 서로 어긋난다.
 * AP만 {@code google-news}인데 공식 RSS가 없어 구글 뉴스 검색 피드를 프록시로 쓰기 때문이다.
 * 그 피드는 description이 링크 마크업뿐이라 제목만 신뢰할 수 있다.
 */
public enum NewsSource {

    CNBC("CNBC", "cnbc.com"),
    // BBC는 영국 안팎으로 bbc.co.uk와 bbc.com을 함께 쓴다
    BBC("BBC Business", "bbc.co.uk", "bbc.com"),
    /** 공식 RSS. 피드 주소는 {@code /uk/business/rss}다 — {@code /business/rss}는 302라 따라가지 않으면 빈손이다. */
    GUARDIAN("The Guardian", "theguardian.com"),
    /** RSS 1.0(RDF)이고 날짜가 {@code dc:date} ISO-8601이다. 링크에 {@code ?maca=} 꼬리가 붙는다. */
    DW("DW", "dw.com"),
    /**
     * 공식 RSS가 없어 구글 뉴스 검색 피드를 프록시로 쓴다. 링크가
     * {@code news.google.com/rss/articles/...} 불투명 주소라 목적지를 볼 수 없다 —
     * 대신 <b>피드 쿼리 자체가 {@code site:apnews.com}으로 묶여 있어</b> 목적지가 항상 AP다.
     * 여기서 걸러 낼 것이 없으므로 구글 주소를 그대로 자기 주소로 삼는다.
     */
    AP("AP News", "news.google.com"),
    /** 코인 전문 매체. 코인만 싣지는 않는다 — 그 대가는 {@link #cryptoSection()}. */
    COINDESK("CoinDesk", "coindesk.com"),
    /** 코인 전문 매체. 기사 페이지는 앱 UA에 403이지만 피드만 받으므로 상관없다 — 우회하지 않는다. */
    THE_BLOCK("The Block", "theblock.co"),
    /**
     * 코인 매체지만 <b>코인 전용 피드로 치지 않는다</b>({@link #cryptoSection()}이 거짓). 실측(2026-11-24)
     * 37건 중 14건이 코인 낱말 없는 AI·엔비디아·양자 기사였다 — 피드로 코인이라 치면 그것들이 코인
     * 자리를 먹는다. 그래서 일반 피드처럼 낱말로 가른다. 대가로 낱말이 없는 코인 기사 몇(37건 중 3건:
     * 모네로 토큰 해킹·FTX·무기한 선물)이 경제 무리로 간다.
     */
    DECRYPT("Decrypt", "decrypt.co");

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
     * 않는다. 그래서 AP는 반응 항을 <b>구조적으로</b> 0으로 받고, 조회도 하지 않는다.
     *
     * <p>구글 리다이렉트를 풀어 실주소를 얻을 수는 있지만 기사마다 조회가 한 번 더 붙는다 —
     * 반응 항 하나에 수집 비용을 두 배로 내지 않는다.
     */
    public boolean opaqueLinks() {
        return this == AP;
    }

    /**
     * <b>코인 기사만 싣기로 하고 연 자리인가.</b>
     *
     * <p>참인 것은 CoinDesk와 The Block이다 — 코인 매체라도 Decrypt는 아니다({@link #DECRYPT}). {@code NewsCategory}가
     * 이 값을 첫 번째 규칙으로 쓴다 — 그 피드에서 온 기사는 내용을 보지 않고 코인으로 센다.
     *
     * <p><b>대가를 알고 고른 것이다.</b> 코인 매체가 낸 일반 기사(실측: CoinDesk의
     * {@code "Nvidia shares rise after earnings top estimates"})가 코인 무리에 앉는다. 반대 방향
     * (코인 기사가 경제 무리로 새는 것)이 더 나쁘다.
     */
    public boolean cryptoSection() {
        return this == COINDESK || this == THE_BLOCK;
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
