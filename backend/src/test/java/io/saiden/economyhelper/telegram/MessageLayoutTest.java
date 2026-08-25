package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>모든 통이 이 위에 서 있는데 단위 테스트가 없었다.</b>
 *
 * <p>골든이 렌더 결과는 덮지만 <b>단위의 경계</b>는 안 덮는다 — 픽스처가 그 값을 안 지나가면
 * 그만이다. 그리고 이 클래스는 주석으로 함정을 둘이나 적어 두고 있다: {@code oneDecimal}의
 * 반올림 모드({@code NumberFormat} 기본이 {@code HALF_EVEN}인데 나머지는 {@code HALF_UP}),
 * 그리고 {@code money}가 <b>0을 떼면서도 정수는 정수로 두는</b> 것.
 *
 * <p>지난 라운드에 등락률 식이 둘이어서 {@code 0.01%p}가 갈린 것과 <b>같은 부류</b>다 —
 * 한 통 안에서 반올림 규칙이 갈리면 어느 숫자도 못 믿게 된다.
 */
class MessageLayoutTest {

    @Test
    @DisplayName("oneDecimal은 HALF_UP이다 — NumberFormat 기본(HALF_EVEN)이면 0.25가 0.2가 된다")
    void oneDecimalRoundsHalfUp() {
        assertThat(MessageLayout.oneDecimal(new BigDecimal("0.25")))
                .as("HALF_EVEN이면 0.2다 — 이 클래스의 나머지가 HALF_UP이라 갈리면 안 된다")
                .isEqualTo("0.3");
        assertThat(MessageLayout.oneDecimal(new BigDecimal("0.35"))).isEqualTo("0.4");
        assertThat(MessageLayout.oneDecimal(new BigDecimal("21"))).as("정수도 한 자리를 채운다")
                .isEqualTo("21.0");
        assertThat(MessageLayout.oneDecimal(null)).isEqualTo("-");
    }

    @Test
    @DisplayName("money는 0을 떼되 정수는 정수로 둔다 — 업비트가 scale 8로 주므로 0이 여덟 개 붙는다")
    void moneyTrimsZerosButKeepsWholeNumbersWhole() {
        assertThat(MessageLayout.money(new BigDecimal("89848000.00000000")))
                .as("떼지 않으면 0이 여덟 개 붙는다").isEqualTo("89,848,000");
        assertThat(MessageLayout.money(new BigDecimal("239500"))).isEqualTo("239,500");
        assertThat(MessageLayout.money(null)).isEqualTo("-");
    }

    @Test
    @DisplayName("소수가 남으면 최소 두 자리로 적는다 — 0.5를 「0.5」로 적으면 값처럼 안 보인다")
    void moneyKeepsAtLeastTwoDecimalsWhenThereAreAny() {
        assertThat(MessageLayout.money(new BigDecimal("232.14"))).isEqualTo("232.14");
        assertThat(MessageLayout.money(new BigDecimal("0.99906000"))).isEqualTo("0.99906");
        assertThat(MessageLayout.money(new BigDecimal("1.5")))
                .as("한 자리만 남아도 두 자리로 채운다").isEqualTo("1.50");
    }

    @Test
    @DisplayName("change는 이모지로 방향을 내고 0.00%는 값이다 — 모른다는 빈 문자열이다")
    void changeCarriesDirectionAndTellsZeroFromUnknown() {
        assertThat(MessageLayout.change(new BigDecimal("2.31"))).isEqualTo("🔴 +2.31%");
        assertThat(MessageLayout.change(new BigDecimal("-1.26"))).isEqualTo("🔵 -1.26%");
        assertThat(MessageLayout.change(BigDecimal.ZERO))
                .as("보합은 값이다 — 이모지를 붙이지 않는다").isEqualTo("0.00%");
        assertThat(MessageLayout.change(null))
                .as("모른다는 것은 빈 문자열이고, 호출부가 그 줄을 뺀다").isEmpty();
        assertThat(MessageLayout.change(new BigDecimal("0.005")))
                .as("반올림해서 0이 되면 보합이다 — 「🔴 +0.01%」로 부풀리지 않는다")
                .isEqualTo("🔴 +0.01%");
    }

    @Test
    @DisplayName("krw는 1원 미만만 소수를 남긴다 — 원화에 소수점을 적을 일이 없다")
    void krwKeepsDecimalsOnlyBelowOneWon() {
        FxRate fx = new FxRate("USD", "KRW", new BigDecimal("1412.17"), FxSource.KIS,
                Instant.parse("2026-08-25T00:00:00Z"));

        assertThat(MessageLayout.krw(new BigDecimal("232.14"), fx))
                .as("232.14 × 1412.17 = 327,821.14... → 327,821")
                .isEqualByComparingTo("327821");
        assertThat(MessageLayout.krw(new BigDecimal("0.0001"), fx))
                .as("1원 미만은 0원이 되면 값을 잃는다").isEqualByComparingTo("0.14");
    }

    @Test
    @DisplayName("sources는 줄로 쌓고 HTML을 이스케이프한다 — 중복 접기는 호출부의 일이다")
    void sourcesStacksAndEscapes() {
        // ⚠️ 처음에 「중복을 접는다」로 짐작하고 썼다가 틀렸다. distinct()는 호출부에 있다
        //    (WeatherFormatter.sourcesOf · StockFormatter). 이 함수는 쌓고 이스케이프만 한다 —
        //    그 경계를 여기 적어 두지 않으면 다음 사람이 같은 짐작을 한다
        assertThat(MessageLayout.sources(Stream.of("AccuWeather", "Open-Meteo")))
                .isEqualTo("AccuWeather\nOpen-Meteo");
        assertThat(MessageLayout.sources(Stream.of("AccuWeather", "AccuWeather")))
                .as("접지 않는다 — 호출부가 distinct()로 접는다")
                .isEqualTo("AccuWeather\nAccuWeather");
        assertThat(MessageLayout.sources(Stream.of("A & B")))
                .as("이스케이프하지 않으면 텔레그램이 통째로 거절한다")
                .isEqualTo("A &amp; B");
    }
}
