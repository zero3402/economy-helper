package io.saiden.economyhelper.market.frankfurter;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 폴백 환율 출처.
 *
 * <p><b>{@code /latest}가 아니라 시계열을 부른다.</b> 등락률에는 전 고시값이 필요한데
 * 시계열은 한 번에 여러 날을 주므로 호출이 늘지 않는다 — 수출입은행이 전 영업일을
 * 되짚느라 한 번 더 부르는 것과 갈리는 지점이고, 여기가 그 사실을 고정하는 자리다.
 */
class FrankfurterFxClientTest extends WireMockTest {

    /** 2026-08-14 금요일 12:00 KST. */
    private static final Instant NOW = Instant.parse("2026-08-14T03:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private FrankfurterFxClient client;

    @BeforeEach
    void resetAndBuild() {
        client = new FrankfurterFxClient(RestClient.builder(), server.baseUrl(),
                Clock.fixed(NOW, SEOUL));
    }

    private void stub(String body) {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("가장 최근 고시를 값으로, 그 앞 고시와의 차이를 등락률로 쓴다")
    void usesLatestQuoteAndComparesWithThePreviousOne() {
        // 2026-08-14 실측 응답 모양이다. 비영업일(08-08 토, 08-09 일)은 키 자체가 없다
        stub("""
                {"amount":1.0,"base":"USD","start_date":"2026-08-04","end_date":"2026-08-13",
                 "rates":{"2026-08-10":{"KRW":1416.62},"2026-08-11":{"KRW":1412.17},
                          "2026-08-12":{"KRW":1417.13},"2026-08-13":{"KRW":1420.29}}}""");

        FxRate rate = client.usdToKrw();

        assertThat(rate.rate()).isEqualByComparingTo("1420.29");
        assertThat(rate.source()).isEqualTo(FxSource.FRANKFURTER);
        // (1420.29 - 1417.13) / 1417.13 × 100 = 0.2230%
        assertThat(rate.changePercent()).isEqualByComparingTo("0.22");
        // 값의 날짜는 응답이 정한다 — 오늘로 채우면 며칠 전 값이 오늘 것으로 둔갑한다
        assertThat(LocalDate.ofInstant(rate.asOf(), SEOUL)).isEqualTo(LocalDate.of(2026, 8, 13));
    }

    @Test
    @DisplayName("한 번만 부른다 — 등락률 때문에 호출이 늘면 시계열을 쓴 이유가 사라진다")
    void asksTheServerExactlyOnce() {
        stub("""
                {"base":"USD","rates":{"2026-08-12":{"KRW":1417.13},
                                       "2026-08-13":{"KRW":1420.29}}}""");

        client.usdToKrw();

        server.verify(1, getRequestedFor(urlPathEqualTo("/v1/2026-08-04..")));
    }

    @Test
    @DisplayName("고시가 하루치뿐이면 등락률만 비운다 — 0%로 찍으면 보합이라고 거짓말이 된다")
    void leavesChangeEmptyWhenThereIsNothingToCompareWith() {
        stub("{\"base\":\"USD\",\"rates\":{\"2026-08-13\":{\"KRW\":1420.29}}}");

        FxRate rate = client.usdToKrw();

        assertThat(rate.rate()).isEqualByComparingTo("1420.29");
        assertThat(rate.changePercent()).isNull();
    }

    @Test
    @DisplayName("환율이 하나도 없으면 실패로 다룬다 — 뒤에 또 폴백이 있다")
    void failsWhenTheResponseCarriesNoRates() {
        stub("{\"base\":\"USD\",\"rates\":{}}");

        assertThatThrownBy(() -> client.usdToKrw()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("KRW가 빠진 응답도 실패로 다룬다")
    void failsWhenKrwIsMissing() {
        stub("{\"base\":\"USD\",\"rates\":{\"2026-08-13\":{\"JPY\":147.2}}}");

        assertThatThrownBy(() -> client.usdToKrw()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("일봉도 빈 rates를 실패로 다룬다 — 빈 목록이 캐시되면 환율 차트가 6시간 빠진다")
    void dailyBarsFailWhenTheResponseCarriesNoRates() {
        // ⚠️ 형제인 usdToKrw()는 isEmpty()까지 보는데 일봉은 null만 보고 있었다. 그러면 빈
        //    목록이 fx-series 캐시에 6시간 굳어 그 사이 환율 차트가 통째로 빠진다(브리핑도 이
        //    값을 쓴다). 창이 25일이라 영업일이 하나도 없을 수는 없으므로 이건 이상이다 —
        //    던져야 브레이커가 보고, 예외는 캐시되지 않아 다음 조회가 스스로 낫는다
        stub("{\"base\":\"USD\",\"rates\":{}}");

        assertThatThrownBy(() -> client.dailyBars()).isInstanceOf(IllegalStateException.class);
    }
}
