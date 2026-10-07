package io.saiden.economyhelper.testsupport;

import io.saiden.economyhelper.config.EconomyHelperProperties.AccuWeather;
import io.saiden.economyhelper.config.EconomyHelperProperties.Binance;
import io.saiden.economyhelper.config.EconomyHelperProperties.CacheTtl;
import io.saiden.economyhelper.config.EconomyHelperProperties.DataGo;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.config.EconomyHelperProperties.Fmp;
import io.saiden.economyhelper.config.EconomyHelperProperties.Frankfurter;
import io.saiden.economyhelper.config.EconomyHelperProperties.Gemini;
import io.saiden.economyhelper.config.EconomyHelperProperties.HackerNews;
import io.saiden.economyhelper.config.EconomyHelperProperties.HttpTimeout;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.KeepWarm;
import io.saiden.economyhelper.config.EconomyHelperProperties.Kexim;
import io.saiden.economyhelper.config.EconomyHelperProperties.Kis;
import io.saiden.economyhelper.config.EconomyHelperProperties.KisIndex;
import io.saiden.economyhelper.config.EconomyHelperProperties.Kma;
import io.saiden.economyhelper.config.EconomyHelperProperties.Market;
import io.saiden.economyhelper.config.EconomyHelperProperties.OpenMeteo;
import io.saiden.economyhelper.config.EconomyHelperProperties.Polygon;
import io.saiden.economyhelper.config.EconomyHelperProperties.Ranking;
import io.saiden.economyhelper.config.EconomyHelperProperties.Telegram;
import io.saiden.economyhelper.config.EconomyHelperProperties.Translation;
import io.saiden.economyhelper.config.EconomyHelperProperties.Upbit;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.config.EconomyHelperProperties.Warmup;
import io.saiden.economyhelper.config.EconomyHelperProperties.Weather;
import io.saiden.economyhelper.config.EconomyHelperProperties.WeatherLocation;
import io.saiden.economyhelper.config.EconomyHelperProperties.Weights;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.news.domain.NewsSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 테스트용 {@link EconomyHelperProperties} 조립기 — <b>컴포넌트가 늘어도 한 곳만 고친다.</b>
 *
 * <p>필요한 것만 채우고 나머지는 {@code null}이 된다 — 각 테스트가 <b>무엇에 의존하는지</b>가 인자
 * 목록이 아니라 메서드 이름으로 드러나고, 컴포넌트가 늘어도 테스트 파일들을 고칠 일이 없다.
 * 그래서 setter는 묶음(레코드)이 아니라 <b>값 하나씩</b>이다. 묶음을 통째로 받으면 쓰지도 않는
 * 자리를 {@code null}로 나열하게 되고, 그 줄이 곧 컴포넌트가 늘 때 깨지는 줄이다.
 *
 * <p>⚠️ {@code null}이 그대로 남는 것이 요점이다. 이 레코드는 {@code @ConfigurationProperties}로
 * 바인딩되므로 실제로는 안 쓰는 묶음이 {@code null}인 것이 정상이고, 테스트가 그 사실에
 * 기대는 자리가 있다({@code FeedFetcher}는 {@code digest}를 안 본다).
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 채운 것만 담고 나머지는 {@code null}로 둔다 — 순서를 외울 일이 없어진다.
     *
     * <p>묶음 하나는 그 안의 값을 하나라도 채웠을 때만 만들어진다. 아무것도 안 채운 묶음은
     * 빈 레코드가 아니라 {@code null}이다 — 바인딩이 안 된 상태가 그 모습이기 때문이다.
     */
    public static final class Builder {

        private Map<NewsSource, Feed> feeds;
        private CacheTtl cacheTtl;
        private List<HttpTimeout> httpTimeouts;

        private RankingValues ranking;
        private DigestValues digest;
        private WeatherValues weather;
        private MarketValues market;
        private TelegramValues telegram;
        private Gemini gemini;
        private KeepWarm keepWarm;
        private Warmup warmup;

        private Builder() {
        }

        public Builder feeds(Map<NewsSource, Feed> feeds) {
            this.feeds = feeds;
            return this;
        }

        public Builder cacheTtl(CacheTtl cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        public Builder httpTimeouts(List<HttpTimeout> httpTimeouts) {
            this.httpTimeouts = httpTimeouts;
            return this;
        }

        // ── ranking ──────────────────────────────────────────────────────────

        public Builder weights(Weights weights) {
            ranking().weights = weights;
            return this;
        }

        public Builder recencyHalfLife(Duration recencyHalfLife) {
            ranking().recencyHalfLife = recencyHalfLife;
            return this;
        }

        public Builder maxAge(Duration maxAge) {
            ranking().maxAge = maxAge;
            return this;
        }

        public Builder hackerNews(String baseUrl, Duration window, int hitsPerPage) {
            ranking().hackerNews = new HackerNews(baseUrl, window, hitsPerPage);
            return this;
        }

        // ── digest ───────────────────────────────────────────────────────────

        public Builder digestZone(String zone) {
            digest().zone = zone;
            return this;
        }

        public Builder sentHistoryTtl(Duration sentHistoryTtl) {
            digest().sentHistoryTtl = sentHistoryTtl;
            return this;
        }

        public Builder indices(List<Index> indices) {
            digest().indices = indices;
            return this;
        }

        public Builder stocks(List<String> stocks) {
            digest().stocks = stocks;
            return this;
        }

        public Builder cryptos(List<String> cryptos) {
            digest().cryptos = cryptos;
            return this;
        }

        public Builder usSymbols(List<UsSymbol> usSymbols) {
            digest().usSymbols = usSymbols;
            return this;
        }

        public Builder newsWindow(Duration window) {
            digest().window = window;
            return this;
        }

        public Builder llmCandidates(int llmCandidates) {
            digest().llmCandidates = llmCandidates;
            return this;
        }

        public Builder relevanceThreshold(double relevanceThreshold) {
            digest().relevanceThreshold = relevanceThreshold;
            return this;
        }

        public Builder searchResults(int searchResults) {
            digest().searchResults = searchResults;
            return this;
        }

        public Builder cryptoResults(int cryptoResults) {
            digest().cryptoResults = cryptoResults;
            return this;
        }

        public Builder economyResults(int economyResults) {
            digest().economyResults = economyResults;
            return this;
        }

        // ── weather ──────────────────────────────────────────────────────────

        public Builder weatherZone(String zone) {
            weather().zone = zone;
            return this;
        }

        public Builder locations(List<WeatherLocation> locations) {
            weather().locations = locations;
            return this;
        }

        public Builder kma(String baseUrl, String apiKey) {
            weather().kma = new Kma(baseUrl, apiKey);
            return this;
        }

        public Builder accuWeather(String baseUrl, String apiKey) {
            weather().accuWeather = new AccuWeather(baseUrl, apiKey);
            return this;
        }

        /** 예보·재분석·지명 검색이 한 호스트인 테스트용 — 셋이 갈려야 하는 자리는 아래를 쓴다. */
        public Builder openMeteo(String baseUrl) {
            return openMeteo(baseUrl, baseUrl, baseUrl);
        }

        public Builder openMeteo(String baseUrl, String archiveBaseUrl, String geocodingBaseUrl) {
            weather().openMeteo = new OpenMeteo(baseUrl, archiveBaseUrl, geocodingBaseUrl);
            return this;
        }

        // ── market ───────────────────────────────────────────────────────────

        public Builder kisBaseUrl(String baseUrl) {
            market().kis().baseUrl = baseUrl;
            return this;
        }

        public Builder kisCredentials(String appKey, String appSecret) {
            market().kis().appKey = appKey;
            market().kis().appSecret = appSecret;
            return this;
        }

        public Builder kisMasterBaseUrl(String masterBaseUrl) {
            market().kis().masterBaseUrl = masterBaseUrl;
            return this;
        }

        public Builder kisPacing(Duration minInterval, Duration maxWait) {
            market().kis().minInterval = minInterval;
            market().kis().maxWait = maxWait;
            return this;
        }

        public Builder kisUsIndices(List<KisIndex> usIndices) {
            market().kis().usIndices = usIndices;
            return this;
        }

        public Builder upbit(String baseUrl) {
            market().upbit = new Upbit(baseUrl);
            return this;
        }

        public Builder binance(String baseUrl, String fallbackBaseUrl) {
            market().binance = new Binance(baseUrl, fallbackBaseUrl);
            return this;
        }

        public Builder dataGo(String baseUrl, String apiKey) {
            market().dataGo = new DataGo(baseUrl, apiKey);
            return this;
        }

        public Builder fmp(String baseUrl, String apiKey, int dailyLimit) {
            market().fmp = new Fmp(baseUrl, apiKey, dailyLimit);
            return this;
        }

        public Builder polygon(String baseUrl, String apiKey) {
            market().polygon = new Polygon(baseUrl, apiKey);
            return this;
        }

        public Builder frankfurter(String baseUrl) {
            market().frankfurter = new Frankfurter(baseUrl);
            return this;
        }

        public Builder kexim(String baseUrl, String apiKey) {
            market().kexim = new Kexim(baseUrl, apiKey);
            return this;
        }

        // ── telegram · gemini · 그 밖 ─────────────────────────────────────────

        public Builder telegramBaseUrl(String baseUrl) {
            telegram().baseUrl = baseUrl;
            return this;
        }

        public Builder botToken(String botToken) {
            telegram().botToken = botToken;
            return this;
        }

        public Builder chatId(String chatId) {
            telegram().chatId = chatId;
            return this;
        }

        public Builder noticeTopicId(String noticeTopicId) {
            telegram().noticeTopicId = noticeTopicId;
            return this;
        }

        public Builder searchTopicId(String searchTopicId) {
            telegram().searchTopicId = searchTopicId;
            return this;
        }

        public Builder webhookSecret(String webhookSecret) {
            telegram().webhookSecret = webhookSecret;
            return this;
        }

        public Builder telegramMinInterval(Duration minInterval) {
            telegram().minInterval = minInterval;
            return this;
        }

        public Builder gemini(String baseUrl, String apiKey, String model) {
            this.gemini = new Gemini(baseUrl, apiKey, model);
            return this;
        }

        public Builder keepWarmUrl(String url) {
            this.keepWarm = new KeepWarm(url);
            return this;
        }

        public Builder warmup(boolean enabled) {
            this.warmup = new Warmup(enabled);
            return this;
        }

        public EconomyHelperProperties build() {
            return new EconomyHelperProperties(
                    feeds,
                    ranking == null ? null : ranking.build(),
                    digest == null ? null : digest.build(),
                    cacheTtl,
                    weather == null ? null : weather.build(),
                    market == null ? null : market.build(),
                    httpTimeouts,
                    telegram == null ? null : telegram.build(),
                    gemini == null ? null : new Translation(gemini),
                    keepWarm,
                    warmup);
        }

        private RankingValues ranking() {
            if (ranking == null) {
                ranking = new RankingValues();
            }
            return ranking;
        }

        private DigestValues digest() {
            if (digest == null) {
                digest = new DigestValues();
            }
            return digest;
        }

        private WeatherValues weather() {
            if (weather == null) {
                weather = new WeatherValues();
            }
            return weather;
        }

        private MarketValues market() {
            if (market == null) {
                market = new MarketValues();
            }
            return market;
        }

        private TelegramValues telegram() {
            if (telegram == null) {
                telegram = new TelegramValues();
            }
            return telegram;
        }
    }

    /**
     * 어느 출처도 실제로 부르지 않는 설정 — 모든 주소가 <b>닿지 않는 곳</b>이다.
     *
     * <p>어댑터를 상속해 조회 메서드를 덮어쓴 가짜가 쓴다. 그런 가짜는 생성자만 통과하면 되고,
     * 주소가 진짜면 덮어쓰기를 빠뜨린 날 테스트가 느려지는 것이 아니라 <b>바깥으로 나간다</b>.
     */
    public static EconomyHelperProperties offline() {
        String nowhere = "https://example.invalid";
        return builder()
                .kisBaseUrl(nowhere).kisMasterBaseUrl(nowhere)
                .upbit(nowhere).binance(nowhere, "")
                .dataGo(nowhere, "").fmp(nowhere, "", 0).polygon(nowhere, "")
                .frankfurter(nowhere).kexim(nowhere, "")
                .kma(nowhere, "").accuWeather(nowhere, "").openMeteo(nowhere)
                .telegramBaseUrl(nowhere)
                .gemini(nowhere, "", "test-model")
                .hackerNews(nowhere, Duration.ofDays(7), 100)
                .build();
    }

    /**
     * 모든 성분이 같은 값인 TTL 묶음 — 캐시 설정만 보는 테스트가 값 자체는 안 본다.
     *
     * <p><b>인자를 손으로 나열하지 않는다</b> — 개수를 아는 유일한 곳은 레코드 자신이므로 거기서 읽는다.
     * 캐시가 늘 때마다 여기를 고칠 일이 없다.
     */
    public static CacheTtl everyTtl(Duration any) {
        var constructor = CacheTtl.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        java.util.Arrays.fill(args, any);
        try {
            return (CacheTtl) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("CacheTtl을 만들 수 없다 — 성분 타입이 Duration이 아닌가?", e);
        }
    }

    // ── 묶음별 임시 보관함 — 레코드가 불변이라 조립 중에는 이쪽에 담는다 ──────────────

    private static final class RankingValues {
        private Weights weights;
        private Duration recencyHalfLife;
        private Duration maxAge;
        private HackerNews hackerNews;

        private Ranking build() {
            return new Ranking(weights, recencyHalfLife, maxAge, hackerNews);
        }
    }

    private static final class DigestValues {
        private String zone;
        private Duration sentHistoryTtl;
        private List<Index> indices;
        private List<String> stocks;
        private List<String> cryptos;
        private List<UsSymbol> usSymbols;
        private Duration window;
        private int llmCandidates;
        private double relevanceThreshold;
        private int searchResults;
        private int cryptoResults;
        private int economyResults;

        private Digest build() {
            return new Digest(zone, sentHistoryTtl, indices, stocks, cryptos, usSymbols,
                    window, llmCandidates, relevanceThreshold,
                    searchResults, cryptoResults, economyResults);
        }
    }

    private static final class WeatherValues {
        private String zone;
        private List<WeatherLocation> locations;
        private Kma kma;
        private AccuWeather accuWeather;
        private OpenMeteo openMeteo;

        private Weather build() {
            return new Weather(zone, locations, kma, accuWeather, openMeteo);
        }
    }

    private static final class MarketValues {
        private KisValues kis;
        private Upbit upbit;
        private Binance binance;
        private DataGo dataGo;
        private Fmp fmp;
        private Polygon polygon;
        private Frankfurter frankfurter;
        private Kexim kexim;

        private KisValues kis() {
            if (kis == null) {
                kis = new KisValues();
            }
            return kis;
        }

        private Market build() {
            return new Market(kis == null ? null : kis.build(), upbit, binance, dataGo, fmp,
                    polygon, frankfurter, kexim);
        }
    }

    private static final class KisValues {
        private String baseUrl;
        private String appKey;
        private String appSecret;
        private String masterBaseUrl;
        private Duration minInterval;
        private Duration maxWait;
        private List<KisIndex> usIndices;

        private Kis build() {
            return new Kis(baseUrl, appKey, appSecret, masterBaseUrl,
                    minInterval, maxWait, usIndices);
        }
    }

    private static final class TelegramValues {
        private String baseUrl;
        private String botToken;
        private String chatId;
        private String noticeTopicId;
        private String searchTopicId;
        private String webhookSecret;
        private Duration minInterval;

        private Telegram build() {
            return new Telegram(baseUrl, botToken, chatId, noticeTopicId, searchTopicId,
                    webhookSecret, minInterval);
        }
    }
}
