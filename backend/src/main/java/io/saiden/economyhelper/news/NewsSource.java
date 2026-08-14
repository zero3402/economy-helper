package io.saiden.economyhelper.news;

/**
 * 수집 대상 매체.
 *
 * <p>Reuters만 {@link FeedType#GOOGLE_NEWS}인데, 공식 RSS를 폐지했기 때문이다
 * (직접 호출하면 401/404). Google News 검색 피드를 프록시로 쓰며, 그 피드는
 * description이 링크 마크업뿐이라 제목만 신뢰할 수 있다.
 *
 * <p><b>유료 여부를 함께 들고 있는다.</b> {@code CLAUDE.md}가 지정한 다섯 중 셋
 * (Bloomberg·FT·Economist)이 강한 페이월이라, 링크를 눌러도 못 읽는 기사가 매일 나갔다.
 * 그 셋을 빼는 대신 무료 매체를 더하고 <b>점수가 비슷하면 무료 쪽을 앞에 둔다</b> —
 * 지정된 매체 구성을 깨지 않으면서 "눌러서 읽히는" 기사가 앞에 온다.
 *
 * <p>페이월 우회(archive.ph 따위)는 붙이지 않는다. 매체의 수익 모델을 우회하는 일이고,
 * 그런 서비스는 언제 막혀도 이상하지 않아 기반으로 삼을 수 없다.
 */
public enum NewsSource {

    BLOOMBERG("Bloomberg", FeedType.RSS, true),
    REUTERS("Reuters", FeedType.GOOGLE_NEWS, true),
    FT("Financial Times", FeedType.RSS, true),
    ECONOMIST("The Economist", FeedType.RSS, true),
    COINDESK("CoinDesk", FeedType.RSS, false),
    // 아래 셋은 전문 무료이고 글로벌 트래픽 상위다. 2026-08-14 실측으로 전부 200이고
    // 각각 30·52·44건을 준다
    CNBC("CNBC", FeedType.RSS, false),
    BBC("BBC Business", FeedType.RSS, false),
    YAHOO_FINANCE("Yahoo Finance", FeedType.RSS, false);

    private final String displayName;
    private final FeedType feedType;
    private final boolean paywalled;

    NewsSource(String displayName, FeedType feedType, boolean paywalled) {
        this.displayName = displayName;
        this.feedType = feedType;
        this.paywalled = paywalled;
    }

    /** 텔레그램 메시지에 노출할 이름. */
    public String displayName() {
        return displayName;
    }

    public FeedType feedType() {
        return feedType;
    }

    /** 참이면 링크를 눌러도 대부분 못 읽는다. 순위가 비슷할 때 뒤로 미는 근거다. */
    public boolean paywalled() {
        return paywalled;
    }
}
