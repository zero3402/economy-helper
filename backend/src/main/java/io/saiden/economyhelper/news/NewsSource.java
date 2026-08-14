package io.saiden.economyhelper.news;

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
 * <p><b>피드 형식은 여기 두지 않는다.</b> 파서를 고르는 쪽은 {@code application.yml}의
 * {@code type}을 읽는다. 같은 사실을 두 곳에 두면 언젠가 서로 어긋난다.
 * AP만 {@code google-news}인데 공식 RSS가 없기 때문이다 — Reuters가 쓰던 방식 그대로고,
 * 그 피드는 description이 링크 마크업뿐이라 제목만 신뢰할 수 있다.
 */
public enum NewsSource {

    YAHOO_FINANCE("Yahoo Finance"),
    INVESTING("Investing.com"),
    CNBC("CNBC"),
    BBC("BBC Business"),
    AP("AP News");

    private final String displayName;

    NewsSource(String displayName) {
        this.displayName = displayName;
    }

    /** 텔레그램 메시지에 노출할 이름. */
    public String displayName() {
        return displayName;
    }
}
