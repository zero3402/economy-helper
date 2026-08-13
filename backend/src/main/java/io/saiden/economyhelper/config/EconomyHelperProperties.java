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
        Map<NewsSource, Feed> feeds, Ranking ranking, Digest digest, CacheTtl cacheTtl, Market market) {

    /** 시세 API들. 키는 여기 두지 않는다 — 환경변수로만 주입한다. */
    public record Market(Upbit upbit, Kexim kexim, DataGo dataGo, Frankfurter frankfurter, Fmp fmp) {}

    /**
     * Financial Modeling Prep — 미국 주식·지수 현재가.
     *
     * <p>무료 티어는 <b>하루 250회, 미국 거래소 전용</b>이다(실측: 한국 심볼·환율·스크리너는 402).
     * 응답에 레이트리밋 헤더가 없어 우리가 센다 — {@code daily-limit}이 그 기준이다.
     */
    public record Fmp(String baseUrl, String apiKey, Integer dailyLimit) {}

    public record Upbit(String baseUrl) {}

    public record Kexim(String baseUrl, String apiKey) {}

    /** 유럽중앙은행 고시 환율. 인증이 없어 배포에서도 그대로 돈다. */
    public record Frankfurter(String baseUrl) {}

    /**
     * 공공데이터포털. 발급된 키는 <b>이미 URL 인코딩된 형태</b>다 —
     * 한 번 더 인코딩하면 "등록되지 않은 서비스키" 403이 난다.
     */
    public record DataGo(String baseUrl, String apiKey) {}


    /** {@code type}이 어느 파서를 쓸지 정한다 — Reuters만 GOOGLE_NEWS다. */
    public record Feed(String url, FeedType type) {}

    public record Ranking(Weights weights, Duration recencyHalfLife) {}

    /** 합이 1일 필요는 없다. {@code PopularityScorer}가 합으로 나눠 정규화한다. */
    public record Weights(double feedRank, double recency, double keywordMatch, double buzz) {}

    /**
     * @param sentHistoryTtl  발송 완료 표시를 남겨 두는 기간. 다음 슬롯(12시간 뒤)보다 넉넉히 길면
     *                        되고, 무한정 쌓이지 않게만 하면 된다.
     * @param usSymbols       브리핑에 넣을 미국 심볼. 지수(^IXIC·^GSPC)와 종목(NVDA·AAPL)이
     *                        같은 엔드포인트라 한 목록으로 둔다
     * @param indices         브리핑에 넣을 지수명. 종목({@code stocks})은 코드로 박지만 지수에는
     *                        코드가 없어 이름으로 쓴다 — {@code MarketIndexApi}가 이름으로만 찾는다
     */
    public record Digest(String zone, String cron, Duration sentHistoryTtl,
                         List<String> indices, List<String> stocks, List<String> cryptos,
                         List<String> usSymbols) {}

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
                           Duration stockResolve, Duration stockPrice, Duration usQuote,
                           Duration fx, Duration fxKexim) {}
}
