package io.saiden.economyhelper.market.kexim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 실제로 호출해 확인한 함정들을 고정한다.
 *
 * <p>이 API는 <b>에러도 HTTP 200으로</b> 준다. 상태코드만 보면 인증 실패도 성공으로 읽힌다 —
 * 실측에서 키 없이 부르니 {@code 200} + {@code [{"result":3,...}]}이 왔다.
 */
class KeximFxClientTest {

    /** 2026-08-12 수요일 12:00 KST. 실측을 뜬 시점과 같게 맞춘다. */
    private static final Instant NOW = Instant.parse("2026-08-12T03:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private WireMockServer server;
    private KeximFxClient client;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor(server.port());
        client = new KeximFxClient(RestClient.builder(), server.baseUrl(), "test-key",
                Clock.fixed(NOW, SEOUL));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stub(String searchdate, String body) {
        server.stubFor(get(urlPathEqualTo("/site/program/financial/exchangeJSON"))
                .withQueryParam("searchdate", WireMock.equalTo(searchdate))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static String usdBody(String dealBasisRate) {
        return """
                [{"result":1,"cur_unit":"USD","cur_nm":"미국 달러","deal_bas_r":"%s"},
                 {"result":1,"cur_unit":"JPY(100)","cur_nm":"일본 옌","deal_bas_r":"960.5"}]
                """.formatted(dealBasisRate);
    }

    @Test
    @DisplayName("USD 매매기준율을 뽑는다 — JPY 같은 다른 통화에 걸리면 안 된다")
    void extractsUsdRate() {
        stub("20260812", usdBody("1,415"));

        FxRate rate = client.usdToKrw();

        assertThat(rate.rate()).isEqualByComparingTo("1415");
        assertThat(rate.base()).isEqualTo("USD");
        assertThat(rate.quote()).isEqualTo("KRW");
        assertThat(rate.source()).isEqualTo(FxSource.KEXIM);
    }

    @Test
    @DisplayName("콤마 낀 값의 두 형태를 모두 파싱한다 — 실측에서 소수점 유무가 갈렸다")
    void parsesBothNumberFormats() {
        // 20260812는 "1,415"(소수점 없음), 20260810은 "1,420.1"(있음)이었다
        assertThat(KeximFxClient.parse("1,415")).isEqualByComparingTo("1415");
        assertThat(KeximFxClient.parse("1,420.1")).isEqualByComparingTo("1420.1");
        assertThat(KeximFxClient.parse(" 1,234.56 ")).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("빈 배열이면 하루씩 물려 되짚는다 — 주말·공휴일·11시 이전이 그렇다")
    void fallsBackToPreviousBusinessDay() {
        stub("20260812", "[]");   // 아직 고시 전
        stub("20260811", "[]");   // 가정: 휴일
        stub("20260810", usdBody("1,420.1"));

        FxRate rate = client.usdToKrw();

        assertThat(rate.rate()).isEqualByComparingTo("1420.1");
        // 찾은 날짜가 값에 담겨야 메시지에서 "며칠 전 값"임을 밝힐 수 있다
        assertThat(LocalDate.ofInstant(rate.asOf(), SEOUL)).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    @DisplayName("7일을 되짚어도 없으면 포기한다 — 무한히 과거로 가면 한도를 태운다")
    void givesUpAfterLookbackLimit() {
        server.stubFor(get(urlPathEqualTo("/site/program/financial/exchangeJSON"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("[]")));

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7일");

        server.verify(7, getRequestedFor(urlPathEqualTo("/site/program/financial/exchangeJSON")));
    }

    @Test
    @DisplayName("에러가 HTTP 200으로 와도 잡아낸다 — result를 봐야 한다")
    void detectsErrorsDeliveredWithStatus200() {
        stub("20260812", "[{\"result\":3,\"cur_unit\":null,\"deal_bas_r\":null}]");

        assertThatThrownBy(() -> client.usdToKrw())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("인증 실패");
    }

    @Test
    @DisplayName("일일 한도 초과(result=4)를 다른 오류와 구분해 알린다 — 캐시 설정을 봐야 한다는 신호다")
    void reportsDailyQuotaExceeded() {
        stub("20260812", "[{\"result\":4}]");

        assertThatThrownBy(() -> client.usdToKrw()).hasMessageContaining("한도");
    }

    @Test
    @DisplayName("DATA 코드 오류(result=2)도 실패로 다룬다")
    void reportsDataCodeError() {
        stub("20260812", "[{\"result\":2}]");

        assertThatThrownBy(() -> client.usdToKrw()).hasMessageContaining("DATA");
    }

    @Test
    @DisplayName("예외 메시지에 authkey가 새지 않는다 — 이 API는 키를 URL에 싣는다")
    void neverLeaksAuthKeyInExceptions() {
        server.stubFor(get(urlPathEqualTo("/site/program/financial/exchangeJSON"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.usdToKrw())
                .hasMessageNotContaining("test-key")
                .hasMessageNotContaining("authkey");
    }

    @Test
    @DisplayName("응답에 USD가 없으면 실패로 다룬다 — 엉뚱한 통화를 돌려주는 것보다 낫다")
    void failsWhenUsdMissing() {
        stub("20260812", "[{\"result\":1,\"cur_unit\":\"JPY(100)\",\"deal_bas_r\":\"960.5\"}]");

        assertThatThrownBy(() -> client.usdToKrw()).hasMessageContaining("USD");
    }
}
