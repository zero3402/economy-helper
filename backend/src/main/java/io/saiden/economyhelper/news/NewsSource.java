package io.saiden.economyhelper.news;

/**
 * 수집 대상 매체.
 *
 * <p>Reuters만 {@link FeedType#GOOGLE_NEWS}인데, 공식 RSS를 폐지했기 때문이다
 * (직접 호출하면 401/404). Google News 검색 피드를 프록시로 쓰며, 그 피드는
 * description이 링크 마크업뿐이라 제목만 신뢰할 수 있다.
 */
public enum NewsSource {

    BLOOMBERG("Bloomberg", FeedType.RSS),
    REUTERS("Reuters", FeedType.GOOGLE_NEWS),
    FT("Financial Times", FeedType.RSS),
    ECONOMIST("The Economist", FeedType.RSS),
    COINDESK("CoinDesk", FeedType.RSS);

    private final String displayName;
    private final FeedType feedType;

    NewsSource(String displayName, FeedType feedType) {
        this.displayName = displayName;
        this.feedType = feedType;
    }

    /** 텔레그램 메시지에 노출할 이름. */
    public String displayName() {
        return displayName;
    }

    public FeedType feedType() {
        return feedType;
    }
}
