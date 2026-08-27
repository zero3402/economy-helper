package io.saiden.economyhelper.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 브리핑의 코인 다섯 자리와 경제 다섯 자리를 누가 받는지 고정한다.
 *
 * <p>가장 값싼 실수가 <b>부분 문자열 오검출</b>이라, 그 방어를 테스트로 못 박는 것이 이 파일의
 * 절반이다 — {@code defi}가 {@code deficit}에 걸리면 재정적자 기사가 코인 자리를 먹는다.
 */
class NewsCategoryTest {

    private static final Instant AT = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    @DisplayName("코인만 싣기로 한 피드에서 왔으면 코인이다 — 내용을 보지 않는다")
    void articlesFromACryptoOnlyFeedAreCrypto() {
        assertThat(NewsCategory.of(article(NewsSource.INVESTING_CRYPTO, "Bitcoin falls to $79K", null)))
                .isEqualTo(NewsCategory.CRYPTO);
        assertThat(NewsCategory.of(article(NewsSource.COINTELEGRAPH, "Here's what happened today", null)))
                .isEqualTo(NewsCategory.CRYPTO);
    }

    @Test
    @DisplayName("코인 매체가 낸 일반 기사도 코인이다 — 알고 고른 대가다")
    void aGeneralStoryFromACryptoOutletStillCountsAsCrypto() {
        // 실측(2026-08-27): CoinDesk 피드가 엔비디아 실적 기사를 실었다. 코인 매체의
        // 시각으로 읽히므로 받아들인다 — 반대(코인 기사가 경제로 새는 것)가 더 나쁘다
        assertThat(NewsCategory.of(article(NewsSource.COINDESK,
                "Nvidia shares rise after earnings top estimates",
                "The tech bellwether reported fiscal second-quarter results after the bell.")))
                .isEqualTo(NewsCategory.CRYPTO);
    }

    @Test
    @DisplayName("일반 피드의 코인 기사도 코인이다 — 드물다는 뜻이지 없다는 뜻이 아니다")
    void cryptoArticlesFromGeneralFeedsAreCryptoToo() {
        assertThat(NewsCategory.of(article(NewsSource.YAHOO_FINANCE,
                "Bitcoin ETF inflows hit a record", null)))
                .isEqualTo(NewsCategory.CRYPTO);
        assertThat(NewsCategory.of(article(NewsSource.CNBC,
                "Ethereum staking yields slip", null)))
                .isEqualTo(NewsCategory.CRYPTO);
        assertThat(NewsCategory.of(article(NewsSource.BBC,
                "Regulator eyes stablecoin rules", null)))
                .isEqualTo(NewsCategory.CRYPTO);
    }

    @Test
    @DisplayName("제목이 아니라 요약문에만 있어도 코인이다 — 매칭 대상이 Article.text()다")
    void looksAtTheDescriptionToo() {
        assertThat(NewsCategory.of(article(NewsSource.CNBC, "Shinhan teams up with Visa",
                "The bank will test stablecoin issuance and redemption.")))
                .isEqualTo(NewsCategory.CRYPTO);
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다 — 낱말 목록은 소문자이고 본문은 그렇지 않다")
    void isCaseInsensitive() {
        assertThat(NewsCategory.of(article(NewsSource.CNBC, "BITCOIN hits a record", null)))
                .isEqualTo(NewsCategory.CRYPTO);
    }

    @Test
    @DisplayName("코인 낱말이 없으면 경제다")
    void everythingElseIsEconomy() {
        assertThat(NewsCategory.of(article(NewsSource.CNBC,
                "Fed signals a rate cut in September", null)))
                .isEqualTo(NewsCategory.ECONOMY);
        assertThat(NewsCategory.of(article(NewsSource.BBC,
                "Qantas full-year profit falls 14%", null)))
                .isEqualTo(NewsCategory.ECONOMY);
    }

    @Test
    @DisplayName("부분 문자열에 속지 않는다 — deficit·whether·solution·ripple이 코인이 되면 안 된다")
    void doesNotFallForSubstringsOfOrdinaryWords() {
        // 짧은 티커(defi·eth·sol)와 흔한 낱말(ripple·coin)을 목록에서 뺀 이유가 이것이다.
        // KeywordGroup.matches는 부분 문자열 대조라 낱말 경계를 보지 않는다
        assertThat(NewsCategory.of(article(NewsSource.CNBC,
                "Budget deficit widens whether or not the solution holds", null)))
                .as("deficit·whether·solution")
                .isEqualTo(NewsCategory.ECONOMY);
        assertThat(NewsCategory.of(article(NewsSource.BBC,
                "Tariff ripple effects coincide with a coinage shortage", null)))
                .as("ripple·coincide·coinage")
                .isEqualTo(NewsCategory.ECONOMY);
    }

    private static Article article(NewsSource source, String title, String description) {
        return new Article(source, title, description,
                "https://example.com/" + source + "/" + title.hashCode(), AT, 0);
    }
}
