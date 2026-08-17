package io.saiden.economyhelper.config;

import io.saiden.economyhelper.news.FeedType;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code application.yml}의 {@code economy-helper.*} 바인딩.
 *
 * <p>피드 URL과 랭킹 가중치를 코드 밖에 두는 이유는 운영하며 조정할 값들이기 때문이다 —
 * 특히 가중치는 실제 발송 결과를 보고 재조정하게 된다.
 */
@ConfigurationProperties(prefix = "economy-helper")
public record EconomyHelperProperties(
        Map<NewsSource, Feed> feeds, Ranking ranking, Digest digest, CacheTtl cacheTtl,
        Weather weather) {


    /** {@code type}이 어느 파서를 쓸지 정한다 — AP만 GOOGLE_NEWS다. */
    public record Feed(String url, FeedType type) {}

    public record Ranking(Weights weights, Duration recencyHalfLife) {}

    /** 합이 1일 필요는 없다. {@code PopularityScorer}가 합으로 나눠 정규화한다. */
    public record Weights(double feedRank, double recency, double keywordMatch, double buzz) {}

    /**
     * @param sentHistoryTtl  발송 완료 표시를 남겨 두는 기간. 다음 슬롯(12시간 뒤)보다 넉넉히 길면
     *                        되고, 무한정 쌓이지 않게만 하면 된다.
     * @param usSymbols       브리핑에 넣을 미국 심볼과 화면에 쓸 이름. 지수(^IXIC·^GSPC)와
     *                        종목(NVDA·AAPL)이 같은 엔드포인트라 한 목록으로 둔다
     * @param indices         브리핑에 넣을 지수명. 종목({@code stocks})은 코드로 박지만 지수에는
     *                        코드가 없어 이름으로 쓴다 — {@code MarketIndexApi}가 이름으로만 찾는다
     */
    public record Digest(String zone, String cron, Duration sentHistoryTtl,
                         List<String> indices, List<String> stocks, List<String> cryptos,
                         List<UsSymbol> usSymbols) {}

    /**
     * 브리핑용 미국 심볼 하나.
     *
     * <p><b>이름을 설정에 둔다.</b> FMP는 {@code Apple Inc.}·{@code NASDAQ Composite}처럼
     * 영문명을 주는데, 국내 종목은 공공데이터포털이 한글명을 주고 코인은 업비트가 준다 —
     * 한 화면에서 표기가 갈린다. {@code /stock} 검색은 LLM이 해석한 한국어 이름을 쓰지만
     * 브리핑은 심볼이 설정에 박혀 있어 LLM을 타지 않으므로, 그 자리를 여기서 채운다.
     *
     * <p><b>{@code Map}이 아니라 목록이다.</b> 키에 {@code ^}가 들어가는데 relaxed binding이
     * Map 키에서 그런 문자를 걸러낸다.
     */
    public record UsSymbol(String symbol, String name) {}

    /**
     * 캐시별 만료 시간.
     *
     * <p>값을 주지 않으면 Redis 캐시는 <b>만료 없이</b> 저장한다 — 피드가 영구 캐시되면
     * 오전 9시와 오후 9시 발송이 같은 기사로 나간다. 그래서 캐시마다 명시한다.
     */
    /**
     * @param query 한국어 검색어 → 영어 표현 대응. 이건 낡지 않으므로 길게 잡는다
     */
    public record CacheTtl(Duration feed, Duration translation, Duration buzz, Duration query,
                           Duration relevance, Duration upbitMarkets, Duration cryptoPrice,
                           Duration binancePrice, Duration stockResolve, Duration cryptoResolve,
                           Duration stockPrice, Duration usQuote,
                           Duration fx, Duration fxKexim,
                           Duration weather, Duration geocode, Duration weatherResolve) {}

    /**
     * 오전 6시 날씨 알람.
     *
     * <p><b>지역을 좌표로 박는다.</b> 지오코딩은 역 이름을 못 찾는다 — {@code 서현}을 물으면
     * 김포시 서현이 1순위로 나온다(실측). 덤으로 이 경로가 지오코딩을 아예 타지 않게 되어,
     * 지명 검색이 죽어도 아침 알람은 나간다. 브리핑이 종목코드를 박아 LLM을 안 타는 것과
     * 같은 구조다.
     *
     * @param cron 발송 창. 슬롯(KST 날짜)이 하루 한 번을 보장하므로 창 안에서 몇 번을 돌아도
     *             한 번만 나간다 — "정확히 6시에 깨어 있어야 한다"는 요구가 사라진다
     */
    public record Weather(String zone, String cron, List<WeatherLocation> locations) {}

    /**
     * 알람에 넣을 지점 하나.
     *
     * <p><b>{@code Map}이 아니라 목록이다.</b> 키가 한글이면 relaxed binding이 걸러낸다 —
     * 6단계에서 별칭 표가 그렇게 조용히 사라진 적이 있다. 값(List 원소)이면 한글이어도 안전하다.
     */
    public record WeatherLocation(String name, double latitude, double longitude) {}
}
