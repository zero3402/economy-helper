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
        Weather weather, Market market) {

    /**
     * {@code market.*} 중 <b>구조가 있는 것만</b> 여기로 묶는다. 나머지(업비트·바이낸스·
     * 공공데이터포털·FMP·수출입은행의 base-url·키)는 값 하나씩이라 {@code @Value}가 그대로 읽는다.
     *
     * <p>⚠️ KIS의 {@code base-url}·{@code app-key}·{@code app-secret}도 여기 있었는데
     * <b>아무도 레코드로 읽지 않았다</b> — 전부 {@code @Value}로 읽는다({@code KisHeaders}·
     * {@code KisTokenStore}·{@code KisFxClient}·{@code KisStockApi}). 위 규칙을 스스로 어긴
     * 자리였고, 두 경로의 결측 의미가 달라 위험하기도 했다({@code @Value}는 {@code ""},
     * 레코드는 {@code null}). 구조가 있는 {@code us-indices}만 남긴다.
     */
    public record Market(Kis kis) {}

    /**
     * @param usIndices 미국 지수의 KIS 심볼 표. <b>브리핑 목록과 갈라 둔다</b> —
     *                  {@code digest.us-symbols}가 이 표를 겸하던 때가 있었는데, 그러면
     *                  표에 없는 심볼을 KIS가 통째로 거절해 {@code /stock 유아이패스}가
     *                  빈손이 됐다. 목록은 "브리핑에 넣을 것", 이 표는 "KIS가 아는 이름"이다
     */
    public record Kis(List<KisIndex> usIndices) {}

    /**
     * 지수 하나의 KIS 심볼.
     *
     * <p><b>{@code Map}이 아니라 목록이다.</b> 키에 {@code ^}가 들어가는데 relaxed binding이
     * Map 키에서 그런 문자를 걸러낸다 — {@code us-symbols}가 같은 이유로 목록이다.
     *
     * @param symbol    LLM·FMP가 쓰는 표기 {@code ^IXIC}
     * @param kisSymbol KIS가 아는 이름 {@code COMP}. <b>규칙이 없어 표가 유일한 길이다</b>
     */
    public record KisIndex(String symbol, String kisSymbol) {}


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
     * @param indices         브리핑에 넣을 지수. 출처마다 조회 키가 달라 이름과 코드를 함께 든다
     *                        ({@link Index} 참조)
     *
     * <p><b>{@code cron}은 담지 않는다.</b> yml 키는 살아 있지만 {@code @Scheduled}의 SpEL
     * 문자열이 직접 읽으므로({@code "${economy-helper.digest.cron}"}) 자바 쪽에서 꺼내는 곳이
     * 없다. {@code zone}은 두 방식 모두로 읽혀서 남는다.
     */
    public record Digest(String zone, Duration sentHistoryTtl,
                         List<Index> indices, List<String> stocks, List<String> cryptos,
                         List<UsSymbol> usSymbols) {}

    /**
     * 브리핑에 넣을 국내 지수 하나.
     *
     * <p><b>출처마다 조회 키가 다르다.</b> 공공데이터포털은 이름으로만 찾고
     * ({@code MarketIndexApi.searchByName}), 한국투자증권은 이름을 아예 못 받고 업종코드를
     * 요구한다 — 코스피 {@code 0001}, 코스닥 {@code 1001}. 그래서 둘을 함께 든다.
     *
     * <p>화면에 쓰는 것도 {@code name}이다. KIS 응답의 지수명은 코스피가 <b>{@code "종합"}</b>,
     * 코스닥이 <b>{@code "KOSDAQ"}</b>으로 와서(실측) 어느 쪽도 그대로 쓸 수 없다.
     *
     * @param code {@code null}이면 KIS는 그 지수를 맡지 못하고 2순위로 넘어간다
     */
    public record Index(String name, String code) {

        public boolean hasCode() {
            return code != null && !code.isBlank();
        }
    }

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
     *
     * <p><b>KIS 조회 키는 담지 않는다.</b> 담던 때가 있었는데, 그러면 이 목록이 브리핑 목록과
     * <b>KIS 대응표</b>를 겸하게 되고 표에 없는 심볼을 KIS가 통째로 거절했다 —
     * {@code /stock 유아이패스}·{@code 오라클}이 그래서 빈손이었다(2순위 FMP가 그 심볼들을
     * 402로 막는다). 지금은 지수 표가 {@code market.kis.us-indices}에 따로 있고,
     * 종목 거래소는 {@code KisStockApi}가 직접 찾는다.
     *
     * @param symbol 2순위(FMP)와 LLM이 쓰는 표기. {@code ^IXIC} · {@code AAPL}
     * @param name   화면에 쓸 한국어 이름. <b>미국 종목 응답에는 이름이 아예 없다</b>
     *               (KIS는 {@code rsym="DNASAAPL"}뿐이고 FMP는 영문명을 준다)
     */
    public record UsSymbol(String symbol, String name) {}

    /**
     * 캐시별 만료 시간.
     *
     * <p>값을 주지 않으면 Redis 캐시는 <b>만료 없이</b> 저장한다 — 피드가 영구 캐시되면
     * 오전 9시와 오후 9시 발송이 같은 기사로 나간다. 그래서 캐시마다 명시한다.
     *
     * <p>⚠️ 이 블록은 예전에 <b>둘로 나뉘어 있었고 앞의 것이 버려졌다.</b> javadoc을 연달아
     * 두면 마지막 것만 붙는데, 하필 버려지는 쪽에 위 경고가 적혀 있었다 —
     * {@code TelegramWebhookController}가 스스로 적어 둔 그 함정에 이 파일이 걸려 있었다.
     *
     * @param query 한국어 검색어 → 영어 표현 대응. 이건 낡지 않으므로 길게 잡는다
     */
    public record CacheTtl(Duration feed, Duration translation, Duration buzz, Duration query,
                           Duration relevance, Duration upbitMarkets, Duration cryptoPrice,
                           Duration binancePrice, Duration stockResolve, Duration cryptoResolve,
                           Duration stockPrice, Duration usQuote, Duration kisQuote,
                           Duration fx, Duration fxKexim, Duration fxKis,
                           Duration weather, Duration geocode, Duration accuLocation,
                           Duration weatherResolve) {}

    /**
     * 오전 6시 날씨 알람.
     *
     * <p><b>지역을 좌표로 박는다.</b> 지오코딩은 역 이름을 못 찾는다 — {@code 서현}을 물으면
     * 김포시 서현이 1순위로 나온다(실측). 덤으로 이 경로가 지오코딩을 아예 타지 않게 되어,
     * 지명 검색이 죽어도 아침 알람은 나간다. 브리핑이 종목코드를 박아 LLM을 안 타는 것과
     * 같은 구조다.
     *
     * <p><b>{@code cron}은 담지 않는다.</b> yml 키는 살아 있지만 {@code @Scheduled}의 SpEL
     * 문자열이 직접 읽으므로({@code "${economy-helper.weather.cron}"}) 자바 쪽에서 꺼내는
     * 곳이 없다 — {@code Digest}와 같다. 예전에는 여기 {@code @param cron}이 적혀 있었는데
     * 레코드에 없는 성분이라 아무것도 설명하지 않았다.
     */
    public record Weather(String zone, List<WeatherLocation> locations) {}

    /**
     * 알람에 넣을 지점 하나.
     *
     * <p><b>{@code Map}이 아니라 목록이다.</b> 키가 한글이면 relaxed binding이 걸러낸다 —
     * 6단계에서 별칭 표가 그렇게 조용히 사라진 적이 있다. 값(List 원소)이면 한글이어도 안전하다.
     */
    public record WeatherLocation(String name, double latitude, double longitude) {}
}
