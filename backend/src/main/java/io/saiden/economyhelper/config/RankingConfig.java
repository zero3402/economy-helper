package io.saiden.economyhelper.config;

import io.saiden.economyhelper.config.EconomyHelperProperties.Weights;
import io.saiden.economyhelper.news.rank.PopularityScorer;
import io.saiden.economyhelper.news.rank.RankingWeights;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RankingConfig {

    /**
     * {@link PopularityScorer}는 Spring을 모르는 순수 클래스라 여기서 조립해 준다 —
     * 덕분에 테스트에서는 컨텍스트 없이 {@code new}로 만들 수 있다.
     */
    @Bean
    public PopularityScorer popularityScorer(EconomyHelperProperties properties) {
        Weights weights = properties.ranking().weights();
        return new PopularityScorer(
                new RankingWeights(
                        weights.feedRank(),
                        weights.recency(),
                        weights.keywordMatch(),
                        weights.buzz()),
                properties.ranking().recencyHalfLife());
    }

    /** 시간을 주입 가능하게 둬야 랭킹·정기 발송을 결정적으로 테스트할 수 있다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
