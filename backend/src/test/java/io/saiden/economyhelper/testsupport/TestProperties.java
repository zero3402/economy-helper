package io.saiden.economyhelper.testsupport;

import io.saiden.economyhelper.config.EconomyHelperProperties.CacheTtl;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.config.EconomyHelperProperties.HttpTimeout;
import io.saiden.economyhelper.config.EconomyHelperProperties.Market;
import io.saiden.economyhelper.config.EconomyHelperProperties.Ranking;
import io.saiden.economyhelper.config.EconomyHelperProperties.Weather;
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
 *
 * <p>⚠️ {@code null}이 그대로 남는 것이 요점이다. 이 레코드는 {@code @ConfigurationProperties}로
 * 바인딩되므로 실제로는 안 쓰는 묶음이 {@code null}인 것이 정상이고, 테스트가 그 사실에
 * 기대는 자리가 있다({@code FeedFetcher}는 {@code digest}를 안 본다).
 */
public final class TestProperties {

    private TestProperties() {
    }

    /** 아무것도 안 채운 것. 무엇을 읽는지 이미 아는 테스트가 쓴다. */
    public static EconomyHelperProperties minimal() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 채운 것만 담고 나머지는 {@code null}로 둔다 — 순서를 외울 일이 없어진다. */
    public static final class Builder {

        private Map<NewsSource, Feed> feeds;
        private Ranking ranking;
        private Digest digest;
        private CacheTtl cacheTtl;
        private Weather weather;
        private Market market;
        private List<HttpTimeout> httpTimeouts;

        private Builder() {
        }

        public Builder feeds(Map<NewsSource, Feed> feeds) {
            this.feeds = feeds;
            return this;
        }

        public Builder ranking(Ranking ranking) {
            this.ranking = ranking;
            return this;
        }

        public Builder digest(Digest digest) {
            this.digest = digest;
            return this;
        }

        public Builder cacheTtl(CacheTtl cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        public Builder weather(Weather weather) {
            this.weather = weather;
            return this;
        }

        public Builder market(Market market) {
            this.market = market;
            return this;
        }

        public Builder httpTimeouts(List<HttpTimeout> httpTimeouts) {
            this.httpTimeouts = httpTimeouts;
            return this;
        }

        public EconomyHelperProperties build() {
            return new EconomyHelperProperties(
                    feeds, ranking, digest, cacheTtl, weather, market, httpTimeouts);
        }
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
}
