package io.saiden.economyhelper.config;

import io.saiden.economyhelper.news.domain.FeedType;
import io.saiden.economyhelper.news.domain.NewsSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code application.yml}의 {@code economy-helper.*} 바인딩.
 *
 * <p>피드 URL과 랭킹 가중치를 코드 밖에 두는 이유는 운영하며 조정할 값들이기 때문이다 —
 * 특히 가중치는 실제 발송 결과를 보고 재조정하게 된다.
 *
 * <h2>⚠️ 왜 여기 목록이 많고 {@code Map}이 없는가</h2>
 *
 * <p><b>relaxed binding이 Map 키에서 점·{@code ^}·한글을 걸러낸다.</b> 그래서 키에 그런 문자가
 * 들어가는 것은 전부 <b>목록</b>이다 — {@code http-timeouts}(호스트에 점),
 * {@code us-indices}·{@code us-symbols}({@code ^IXIC}), {@code weather.locations}(한글 역 이름),
 * {@code digest.indices}(한글 지수명). 별칭 표가 Map이었다가 <b>조용히 사라진 적이 있다</b> —
 * 오류가 아니라 빈 Map이 되므로 런타임에야 드러난다.
 *
 * <p>아래 레코드들은 이 규칙을 되풀어 적지 않는다. 한 곳에서 오는 것이 요점이다.
 *
 * <h2>없을 때 무엇이 되는가</h2>
 *
 * <p>⚠️ <b>값 하나를 두 경로로 읽지 않는다.</b> 여기 있는 키를 {@code @Value}로 다시 읽으면
 * 결측 의미가 갈린다({@code @Value}는 거기 적은 기본값, 레코드는 {@code null}) — 그러면
 * 같은 설정이 자리마다 다른 값이 된다.
 *
 * <p>값이 설정에 없으면 성분은 {@code null}이다. {@code null}이 답이 아닌 자리에는
 * {@link DefaultValue}로 <b>기본값을 적어 둔다</b> — 그 자리들은 전부 yml에도 값이 있으므로
 * 기본값은 「yml에서 그 줄이 사라져도 전과 같이 돈다」는 보험이다.
 *
 * <p>기본값이 없는 것(각 출처의 {@code base-url}과 {@code gemini.model})은 <b>없으면 안 되는 값</b>이다.
 * 빠지면 {@code null}이 그대로 흘러가 조용히 엉뚱한 요청이 나가므로,
 * {@code EconomyHelperPropertiesTest}가 실제 바인딩에서 그것들이 차 있는지 본다 —
 * {@code cache-ttl}을 {@code CacheConfigTest}로, 타임아웃 호스트를 {@code HttpTimeoutsTest}로
 * 막은 것과 같은 함정이고 같은 대응이다.
 */
@ConfigurationProperties(prefix = "economy-helper")
public record EconomyHelperProperties(
        Map<NewsSource, Feed> feeds, Ranking ranking, Digest digest, CacheTtl cacheTtl,
        Weather weather, Market market, List<HttpTimeout> httpTimeouts,
        Telegram telegram, Translation translation, @DefaultValue KeepWarm keepWarm,
        @DefaultValue Warmup warmup) {

    /**
     * 출처 하나의 타임아웃 — <b>키가 호스트다.</b>
     *
     * <p><b>왜 설정 이름이 아니라 호스트인가.</b> Boot 4에는 손으로 만든 {@code RestClient}용
     * <b>이름별</b> 타임아웃이 없다. {@code spring.http.clients}는 평평한 전역 블록 하나이고
     * (의존성 jar의 설정 메타데이터로 확인: {@code connect-timeout}·{@code read-timeout}·
     * {@code redirects}·{@code cookie-handling}·{@code ssl.bundle}·{@code imperative.factory}뿐),
     * 키별 형태는 {@code spring.http.serviceclient.*}인데 그건 {@code @ImportHttpServices}
     * 인터페이스 클라이언트 전용이다. 그래서 키를 우리가 준다.
     *
     * <p>호스트가 그 키로 맞는 이유는 <b>경계가 이미 그렇게 그려져 있어서</b>다 — Open-Meteo
     * 셋이 호스트 셋이고 브레이커도 셋, AccuWeather 둘이 호스트 하나이고 브레이커도 하나,
     * KIS 셋이 호스트 하나이고 앱키도 간격 문도 하나다. 새 개념을 만들지 않는다.
     *
     * <p>⚠️ <b>여기 없는 호스트는 조용히 전역 기본값이 된다.</b> 오타가 오류를 내지 않는다는
     * 뜻이라 {@code HttpTimeoutsTest}가 이 목록의 호스트가 실재하는 {@code base-url}인지 본다 —
     * {@code cache-ttl}이 {@code CacheConfigTest}로, 리미터가 {@code ResilienceConfigTest}로
     * 막은 것과 같은 함정이고 같은 대응이다.
     *
     * @param host    포트 없는 호스트만. {@code URI.getHost()}가 포트를 떼고 오기 때문이다
     * @param connect 연결까지. 콜드 DNS가 실측 1.3초(BBC)까지 가므로 초 단위로 둔다
     * @param read    응답을 다 받기까지. <b>출처마다 다른 것이 이 값이다</b>
     */
    public record HttpTimeout(String host, Duration connect, Duration read) {}

    /** 시세·환율 출처들 — <b>출처 하나가 레코드 하나</b>다. */
    public record Market(Kis kis, Upbit upbit, Binance binance, DataGo dataGo, Fmp fmp,
                         Polygon polygon, Frankfurter frankfurter, Kexim kexim) {}

    /**
     * 한국투자증권 — 환율·국내 주식·미국 주식의 1순위. <b>셋이 같은 앱키·같은 호스트·같은 간격 문</b>이라
     * 레코드도 하나다.
     *
     * @param masterBaseUrl 종목 마스터 파일이 있는 곳. 키가 없고 모의·실전 구분도 없어
     *                      {@code base-url}과 다른 호스트다
     * @param minInterval   호출 사이 최소 간격. 모의 계정이 초당 1건이라 기본 1초다 —
     *                      실전 계정은 초당 20건이므로 낮춰 잡을 수 있다({@code base-url}과 함께 바꾼다)
     * @param maxWait       줄 서는 것까지 이 시간을 넘기면 기다리지 않고 던진다
     * @param usIndices     미국 지수의 KIS 심볼 표. <b>브리핑 목록과 갈라 둔다</b> — 겸하면 표에 없는
     *                      심볼을 KIS가 통째로 거절한다. 목록은 "브리핑에 넣을 것", 이 표는 "KIS가 아는 이름"이다
     */
    public record Kis(String baseUrl,
                      @DefaultValue("") String appKey, @DefaultValue("") String appSecret,
                      String masterBaseUrl,
                      @DefaultValue("1s") Duration minInterval,
                      @DefaultValue("20s") Duration maxWait,
                      List<KisIndex> usIndices) {}

    /** 업비트 — 국내 코인 시세. 인증이 없다. */
    public record Upbit(String baseUrl) {}

    /**
     * 바이낸스 — 글로벌 코인 시세.
     *
     * @param fallbackBaseUrl 451(지역 차단) 전용 우회로. <b>비우면 우회 자체가 없다</b>
     */
    public record Binance(String baseUrl, @DefaultValue("") String fallbackBaseUrl) {}

    /** 공공데이터포털 — 국내 주식·ETF의 2순위. 키는 기상청과 같은 {@code DATA_API_KEY}다. */
    public record DataGo(String baseUrl, @DefaultValue("") String apiKey) {}

    /**
     * FMP — 미국 시세·전망의 2순위.
     *
     * @param dailyLimit 무료 한도가 하루 250회인데 응답에 레이트리밋 헤더가 없어 우리가 센다.
     *                   여유를 두고 240에서 멈춘다
     */
    public record Fmp(String baseUrl, @DefaultValue("") String apiKey,
                      @DefaultValue("240") int dailyLimit) {}

    /** Polygon — 미국 배당의 1순위. 환경변수 이름이 {@code MASSIVE_API_KEY}다. */
    public record Polygon(String baseUrl, @DefaultValue("") String apiKey) {}

    /** Frankfurter — 유럽중앙은행 고시 환율. 인증도 IP 제한도 없다. */
    public record Frankfurter(String baseUrl) {}

    /** 수출입은행 — 환율 3순위. 하루 1,000회 한도가 있다. */
    public record Kexim(String baseUrl, @DefaultValue("") String apiKey) {}

    /**
     * 지수 하나의 KIS 심볼.
     *
     * @param symbol    LLM·FMP가 쓰는 표기 {@code ^IXIC}
     * @param kisSymbol KIS가 아는 이름 {@code COMP}. <b>규칙이 없어 표가 유일한 길이다</b>
     */
    public record KisIndex(String symbol, String kisSymbol) {}

    /** {@code type}이 어느 파서를 쓸지 정한다 — AP만 GOOGLE_NEWS다. */
    public record Feed(String url, FeedType type) {}

    /**
     * @param maxAge 이보다 오래된 기사는 수집 단계에서 버린다. 신선도 가중치는 랭킹 네 항 중
     *               하나일 뿐이라 피드 앞자리에 놓인 옛 기사를 못 막는다
     */
    public record Ranking(Weights weights, Duration recencyHalfLife,
                          @DefaultValue("3d") Duration maxAge, HackerNews hackerNews) {}

    /**
     * Hacker News — 매체가 조회수·댓글을 공개하지 않아 무료로 얻을 수 있는 유일한 실측 반응이다.
     *
     * @param window 이 기간 안의 글만 반응으로 센다
     */
    public record HackerNews(String baseUrl, @DefaultValue("7d") Duration window,
                             @DefaultValue("100") int hitsPerPage) {}

    /** 합이 1일 필요는 없다. {@code PopularityScorer}가 합으로 나눠 정규화한다. */
    public record Weights(double feedRank, double recency, double keywordMatch, double buzz) {}

    /**
     * @param sentHistoryTtl  발송 완료 표시를 남겨 두는 기간. 슬롯 키에 날짜가 들어 있으므로
     *                        길어도 다음 발송을 막지 않는다 — 무한정 쌓이지 않게만 하면 된다.
     * @param usSymbols       브리핑에 넣을 미국 심볼과 화면에 쓸 이름. 지수(^IXIC·^GSPC)와
     *                        종목(NVDA·AAPL)이 같은 엔드포인트라 한 목록으로 둔다
     * @param indices         브리핑에 넣을 지수. 출처마다 조회 키가 달라 이름과 코드를 함께 든다
     *                        ({@link Index} 참조)
     * @param window          뉴스 신선도 창 — 알람과 검색이 같은 값을 쓴다. 날짜(KST 달력)가
     *                        아니라 경과 시간이다
     * @param llmCandidates   매체별로 LLM에 넘길 후보 수
     * @param relevanceThreshold 이 미만이면 재테크 뉴스가 아닌 것으로 보고 그 매체를 이번 발송에서 뺀다
     * @param searchResults   {@code /news} 검색이 보여줄 건수
     * @param cryptoResults   브리핑 뉴스의 코인 건수. 모자란 무리는 다른 무리로 메우지 않는다
     * @param economyResults  브리핑 뉴스의 경제 건수
     *
     * <p><b>{@code cron}은 담지 않는다.</b> yml 키는 살아 있지만 {@code @Scheduled}의 SpEL
     * 문자열이 직접 읽으므로({@code "${economy-helper.digest.cron}"}) 자바 쪽에서 꺼내는 곳이
     * 없다. {@code zone}은 두 방식 모두로 읽혀서 남는다.
     */
    public record Digest(String zone, Duration sentHistoryTtl,
                         List<Index> indices, List<String> stocks, List<String> cryptos,
                         List<UsSymbol> usSymbols,
                         @DefaultValue("24h") Duration window,
                         @DefaultValue("8") int llmCandidates,
                         @DefaultValue("0.4") double relevanceThreshold,
                         @DefaultValue("5") int searchResults,
                         @DefaultValue("5") int cryptoResults,
                         @DefaultValue("5") int economyResults) {}

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
     * <p><b>KIS 조회 키는 담지 않는다</b>({@link Kis#usIndices} 참고) — 지수 표는
     * {@code market.kis.us-indices}에 따로 있고, 종목 거래소는 {@code KisStockApi}가 직접 찾는다.
     *
     * @param symbol 2순위(FMP)와 LLM이 쓰는 표기. {@code ^IXIC} · {@code AAPL}
     * @param name   화면에 쓸 한국어 이름. <b>미국 종목 응답에는 이름이 아예 없다</b>
     *               (KIS는 {@code rsym="DNASAAPL"}뿐이고 FMP는 영문명을 준다)
     */
    public record UsSymbol(String symbol, String name) {

        /**
         * 지수인가 — {@code ^IXIC}는 지수고 {@code AAPL}은 종목이다.
         *
         * <p>지수는 차트 경로가 다르고 종목은 전망이 붙는다 — 접두 {@code ^}를 두 곳에 적지 않으려고
         * 심볼 자신이 답한다.
         */
        public boolean isIndex() {
            return isIndex(symbol);
        }

        /** 심볼 글자만 손에 있을 때 — 일봉 경로가 그 상태로 판별해야 한다. */
        public static boolean isIndex(String symbol) {
            return symbol != null && symbol.startsWith("^");
        }
    }

    /**
     * 캐시별 만료 시간.
     *
     * <p>값을 주지 않으면 Redis 캐시는 <b>만료 없이</b> 저장한다 — 피드가 영구 캐시되면
     * 발송 창(09~10시, 10분마다 틱) 안에서 같은 기사가 되풀이되고 다음 날 브리핑까지
     * 어제 기사가 남는다. 그래서 캐시마다 명시한다.
     *
     * @param query 한국어 검색어 → 영어 표현 대응. 이건 낡지 않으므로 길게 잡는다
     */
    public record CacheTtl(Duration usDividend,
                           Duration feed, Duration translation, Duration buzz, Duration query,
                           Duration relevance, Duration upbitMarkets, Duration cryptoPrice,
                           Duration binancePrice, Duration stockResolve, Duration cryptoResolve,
                           Duration stockPrice, Duration usQuote, Duration kisQuote,
                           Duration fx, Duration fxKexim, Duration fxKis,
                           Duration weather, Duration geocode, Duration accuLocation,
                           Duration weatherResolve, Duration precipitationHours,
                           Duration kisOutlook, Duration usOutlook, Duration fxSeries,
                           Duration cryptoSeries, Duration stockSeries, Duration krListings) {}

    /**
     * 오전 8시 날씨 알람.
     *
     * <p><b>지역을 좌표로 박는다.</b> 지오코딩은 역 이름을 못 찾는다 — {@code 서현}을 물으면
     * 김포시 서현이 1순위로 나온다(실측). 덤으로 이 경로가 지오코딩을 아예 타지 않게 되어,
     * 지명 검색이 죽어도 아침 알람은 나간다. 브리핑이 종목코드를 박아 LLM을 안 타는 것과
     * 같은 구조다.
     *
     * <p><b>{@code cron}은 담지 않는다.</b> yml 키는 살아 있지만 {@code @Scheduled}의 SpEL
     * 문자열이 직접 읽으므로({@code "${economy-helper.weather.cron}"}) 자바 쪽에서 꺼내는
     * 곳이 없다 — {@code Digest}와 같다.
     */
    public record Weather(String zone, List<WeatherLocation> locations,
                          Kma kma, AccuWeather accuWeather, OpenMeteo openMeteo) {}

    /** 알람에 넣을 지점 하나. */
    public record WeatherLocation(String name, double latitude, double longitude) {}

    /** 기상청 동네예보 — 국내 1순위. 키는 공공데이터포털과 같은 {@code DATA_API_KEY}다. */
    public record Kma(String baseUrl, @DefaultValue("") String apiKey) {}

    /** AccuWeather — 국외 1순위이자 국내 2순위. 무료 등급이 하루 50회다. */
    public record AccuWeather(String baseUrl, @DefaultValue("") String apiKey) {}

    /**
     * Open-Meteo — 2순위와 과거. <b>호스트가 셋이다</b>(예보·재분석·지명 검색).
     *
     * @param archiveBaseUrl   지나간 날(ERA5 재분석). 격자가 달라 출처 이름도 따로 적는다
     * @param geocodingBaseUrl 지명 검색. 이중화 상대가 없다
     */
    public record OpenMeteo(String baseUrl, String archiveBaseUrl, String geocodingBaseUrl) {}

    /**
     * 텔레그램 — 정기 발송과 명령을 받는 유일한 창구.
     *
     * @param chatId         정기 발송 대상이자 명령을 받아 줄 유일한 채팅방. 다른 곳에서 온 명령은 무시한다
     * @param noticeTopicId  브리핑을 보낼 포럼 토픽. 비우면 토픽을 지정하지 않는다
     * @param searchTopicId  명령을 받을 포럼 토픽. 비우면 모든 토픽을 받는다
     * @param webhookSecret  {@code setWebhook}에 준 것과 같은 값. 비어 있으면 검증하지 않는다
     * @param minInterval    같은 방에 연달아 보낼 때 <b>발송 시작 사이</b>의 최소 간격
     */
    public record Telegram(String baseUrl,
                           @DefaultValue("") String botToken, @DefaultValue("") String chatId,
                           @DefaultValue("") String noticeTopicId,
                           @DefaultValue("") String searchTopicId,
                           @DefaultValue("") String webhookSecret,
                           @DefaultValue("1s") Duration minInterval) {}

    public record Translation(Gemini gemini) {}

    /**
     * Gemini — 검색어 해석·번역·관련도.
     *
     * @param model 버전을 고정하지 않고 별칭을 쓴다 — 무료 티어 모델은 조용히 은퇴한다
     */
    public record Gemini(String baseUrl, @DefaultValue("") String apiKey, String model) {}

    /**
     * 무활동으로 잠드는 호스트에서 깨어 있기 위한 자체 핑.
     *
     * <p><b>{@code cron}은 담지 않는다</b> — {@link Digest}와 같은 이유로 {@code @Scheduled}의
     * SpEL 문자열이 직접 읽는다.
     *
     * @param url 비우면 이 기능은 없는 것과 같다. 반드시 공개 주소여야 한다
     */
    public record KeepWarm(@DefaultValue("") String url) {}

    /** 기동 때 종목 색인 데우기. 테스트는 꺼 둔다 — 실제 파일 호스트를 부르지 않게. */
    public record Warmup(@DefaultValue("true") boolean enabled) {}

}
