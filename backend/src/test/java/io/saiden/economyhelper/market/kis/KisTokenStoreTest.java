package io.saiden.economyhelper.market.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 토큰 재사용이 <b>지켜지는지</b>를 못 박는다.
 *
 * <p>여기가 깨지면 조용히 넘어가지 않는다 — KIS는 <b>1분에 한 번만</b> 발급하고
 * <b>발급마다 계정주에게 알림톡을 보낸다.</b> 요청마다 발급하면 차단되고 사용자 휴대폰이 울린다.
 *
 * <p>{@code redis}에 {@code null}을 넣어 <b>Redis가 죽은 상황</b>을 만든다. 그때도 프로세스
 * 사본으로 돌아야 한다 — 발급이 비싸서 {@code FmpQuotaGuard}와 반대 방향으로 판단한 자리다.
 */
class KisTokenStoreTest {

    private static final String PATH = "/oauth2/tokenP";
    /** KST 2026-08-18 17:00. */
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        // h2c를 끈다 — JDK HttpClient가 HTTP/2를 먼저 시도하는데 WireMock의 평문 h2 구현과
        // 맞지 않아 POST 본문이 "no bytes"로 떨어진다. TelegramClientTest가 같은 이유로 끈다
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .http2PlainDisabled(true));
        server.start();
        WireMock.configureFor(server.port());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stub(String body) {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** Redis 없이(널) 만든다 — 그 경로가 실제로 도는지가 이 클래스의 요점이다. */
    private KisTokenStore store(Instant now) {
        return new KisTokenStore(RestClient.builder(), server.baseUrl(), "key", "secret",
                null, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발급한 토큰을 그대로 준다 — Redis가 죽어도 메모리 사본으로 돈다")
    void issuesAndReturnsTheToken() {
        stub("""
                {"access_token":"tok-1","token_type":"Bearer","expires_in":86400,
                 "access_token_token_expired":"2026-08-19 16:56:34"}
                """);

        assertThat(store(NOW).token()).isEqualTo("tok-1");
    }

    @Test
    @DisplayName("두 번 물어도 한 번만 발급한다 — 1분 1회 제한과 알림톡 때문에 재사용이 필수다")
    void reusesTheTokenWithinItsLifetime() {
        stub("""
                {"access_token":"tok-1","access_token_token_expired":"2026-08-19 16:56:34"}
                """);
        KisTokenStore store = store(NOW);

        assertThat(store.token()).isEqualTo("tok-1");
        assertThat(store.token()).isEqualTo("tok-1");

        server.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("만료가 임박하면 다시 받는다 — 만료 순간에 걸리면 그 뒤 1분이 통째로 막힌다")
    void refreshesBeforeTheExpiryMargin() {
        // 만료가 KST 17:05 = 5분 뒤. 여유(10분) 안이라 유효로 보지 않는다
        stub("""
                {"access_token":"tok-1","access_token_token_expired":"2026-08-18 17:05:00"}
                """);
        KisTokenStore store = store(NOW);

        store.token();
        store.token();

        server.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("만료를 access_token_token_expired로 읽는다 — expires_in은 기준 시각이 없어 못 믿는다")
    void readsTheAbsoluteExpiryNotTheDuration() {
        // expires_in은 24시간이라 말하지만 절대 시각은 6분 뒤다. 절대 시각을 믿어야 재발급한다
        stub("""
                {"access_token":"tok-1","expires_in":86400,
                 "access_token_token_expired":"2026-08-18 17:06:00"}
                """);
        KisTokenStore store = store(NOW);

        store.token();
        store.token();

        server.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("만료 형식이 깨지면 1시간만 믿는다 — 24시간을 믿으면 그동안 401을 맞고도 안 고친다")
    void fallsBackToAShortLifetimeOnAnUnreadableExpiry() {
        stub("""
                {"access_token":"tok-1","access_token_token_expired":"어제"}
                """);
        KisTokenStore store = store(NOW);

        assertThat(store.token()).isEqualTo("tok-1");
        store.token();

        server.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("접근토큰이 없는 응답은 실패다 — 빈 토큰으로 시세를 부르면 401만 돌아온다")
    void throwsWhenTheResponseCarriesNoToken() {
        stub("{\"error_description\":\"invalid\"}");

        assertThatThrownBy(() -> store(NOW).token())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("접근토큰");
    }

    @Test
    @DisplayName("키가 없으면 발급조차 안 한다 — 1분에 한 번뿐인 발급을 헛되이 쓰지 않는다")
    void skipsIssuingWithoutKeys() {
        KisTokenStore keyless = new KisTokenStore(RestClient.builder(), server.baseUrl(), "", "",
                null, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(keyless::token).hasMessageContaining("앱키");
        server.verify(0, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("예외 메시지에 응답 본문이 새지 않는다 — 이 응답에는 접근토큰이 들어 있다")
    void neverLeaksTheResponseBody() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withBody("{\"access_token\":\"leaked-token\"}")));

        assertThatThrownBy(() -> store(NOW).token())
                .hasMessageNotContaining("leaked-token")
                .as("연타하면 여기로 떨어지므로 그 사실이 메시지에 드러나야 한다")
                .hasMessageContaining("1분에 한 번");
    }
}
