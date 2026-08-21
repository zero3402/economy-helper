package io.saiden.economyhelper.support;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.CacheTtl;
import io.saiden.economyhelper.config.EconomyHelperProperties.Digest;
import io.saiden.economyhelper.config.EconomyHelperProperties.Feed;
import io.saiden.economyhelper.config.EconomyHelperProperties.HttpTimeout;
import io.saiden.economyhelper.config.EconomyHelperProperties.Market;
import io.saiden.economyhelper.config.EconomyHelperProperties.Ranking;
import io.saiden.economyhelper.config.EconomyHelperProperties.Weather;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 테스트용 {@link EconomyHelperProperties} 조립기 — <b>컴포넌트가 늘어도 한 곳만 고친다.</b>
 *
 * <p><b>왜 필요했나.</b> 레코드에 컴포넌트 하나({@code httpTimeouts})를 더했더니 <b>테스트 여섯
 * 파일을 전부</b> 고쳐야 했다. 전부 똑같은 편집이었다 — 인자 목록 끝에 {@code null} 하나 붙이기.
 * 그 편집은 아무것도 검증하지 않으면서 리뷰를 어지럽히고, 한 곳을 빠뜨리면 컴파일이 깨진다.
 * 게다가 {@code FeedFetcherTest}에서는 인자마다 붙어 있던 설명 주석이 새 {@code null} 때문에
 * 한 칸씩 밀려 <b>조용히 틀린 말</b>이 됐다.
 *
 * <p>이름 있는 메서드로 부르면 그 문제가 사라진다. 필요한 것만 채우고 나머지는 여기서 {@code null}이
 * 된다 — 각 테스트가 <b>무엇에 의존하는지</b>가 인자 목록이 아니라 메서드 이름으로 드러난다.
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
     * <p><b>인자를 손으로 나열하지 않는다.</b> 예전에는 {@code any}를 스물한 번 적고 javadoc에
     * 「스물한 개」라고 써 뒀는데, 캐시가 하나 늘 때마다 <b>둘 다</b> 고쳐야 했고 실제로
     * {@code kis-outlook}을 더할 때 컴파일이 깨졌다. 개수를 아는 유일한 곳은 레코드 자신이므로
     * 거기서 읽는다 — 「늘 때마다 여러 곳을 고쳐야 하는 값은 반드시 낡는다」는 규칙 그대로다.
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
