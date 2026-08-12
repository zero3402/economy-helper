package io.saiden.economyhelper.market.upbit;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.text.QueryNormalizer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 업비트 원화 마켓에서 뽑은 이름들로 고정한다. 특히 <b>비트·이더·리플</b>은
 * 이름만 좁혀서는 오답이 앞에 오는 것이 실측으로 확인된 사례라 후보가 여럿으로
 * 나와야 정상이다 — 고르는 건 {@code CryptoService}가 거래대금으로 한다.
 */
class UpbitMarketIndexTest {

    private static final List<UpbitMarket> MARKETS = List.of(
            UpbitMarket.of("KRW-BTC", "비트코인", "Bitcoin"),
            UpbitMarket.of("KRW-BCH", "비트코인캐시", "Bitcoin Cash"),
            UpbitMarket.of("KRW-TAO", "비트텐서", "Bittensor"),
            UpbitMarket.of("KRW-ARB", "아비트럼", "Arbitrum"),
            UpbitMarket.of("KRW-ETH", "이더리움", "Ethereum"),
            UpbitMarket.of("KRW-ETC", "이더리움클래식", "Ethereum Classic"),
            UpbitMarket.of("KRW-ETHFI", "이더파이", "ether.fi"),
            UpbitMarket.of("KRW-XRP", "엑스알피(리플)", "XRP"),
            UpbitMarket.of("KRW-RLUSD", "리플유에스디", "Ripple USD"),
            UpbitMarket.of("KRW-DOGE", "도지코인", "Dogecoin"));

    private static List<String> found(String query) {
        return UpbitMarketIndex.candidates(QueryNormalizer.forLookup(query), MARKETS).stream()
                .map(UpbitMarket::market)
                .toList();
    }

    @Test
    @DisplayName("한글명·영문명·심볼 어느 쪽으로 쳐도 같은 마켓 하나로 간다")
    void resolvesAnyLanguageToOneMarket() {
        for (String query : List.of("비트코인", "bitcoin", "Bitcoin", "BTC", "btc", " btc ")) {
            assertThat(found(query)).as("입력 '%s'", query).containsExactly("KRW-BTC");
        }
    }

    @Test
    @DisplayName("군더더기가 붙어도 찾는다")
    void ignoresNoiseWords() {
        assertThat(found("비트코인 얼마")).containsExactly("KRW-BTC");
        assertThat(found("지금 이더리움 시세")).containsExactly("KRW-ETH");
    }

    @Test
    @DisplayName("정확히 일치하는 게 있으면 접두 일치는 보지 않는다 — 비트코인에 비트코인캐시가 끼면 안 된다")
    void exactMatchWinsOverPrefix() {
        assertThat(found("비트코인")).containsExactly("KRW-BTC");
        assertThat(found("이더리움")).containsExactly("KRW-ETH");
    }

    @Test
    @DisplayName("약칭은 후보를 여럿 준다 — 여기서 고르면 실측상 전부 오답이었다")
    void ambiguousQueriesReturnAllCandidates() {
        // 이름만으로는 비트코인캐시·비트텐서·아비트럼이 함께 걸린다
        assertThat(found("비트")).hasSizeGreaterThan(1).contains("KRW-BTC");
        // '이더'는 이더파이·이더리움클래식이 함께 걸린다
        assertThat(found("이더")).hasSizeGreaterThan(1).contains("KRW-ETH");
    }

    @Test
    @DisplayName("접두와 부분을 한 단계로 본다 — 나누면 리플이 XRP를 놓친다")
    void prefixAndContainsMustBeOneStage() {
        // '리플유에스디'는 접두로, '엑스알피(리플)'은 부분으로 걸린다.
        // 단계를 나누면 접두에서 멈춰 정답인 XRP가 거래대금 비교에 올라가지도 못한다
        assertThat(found("리플")).contains("KRW-XRP", "KRW-RLUSD");
    }

    @Test
    @DisplayName("걸리는 게 없으면 빈 목록")
    void returnsEmptyWhenNothingMatches() {
        assertThat(found("없는코인zzz")).isEmpty();
        assertThat(found("")).isEmpty();
        assertThat(found(null)).isEmpty();
    }
}
