package io.saiden.economyhelper.market.kis;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Clock;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
@WireMockTest.WireMockOptions(http2PlainDisabled = true)
class KisTokenStoreTest extends WireMockTest {

    private static final String PATH = "/oauth2/tokenP";
    /** KST 2026-08-18 17:00. */
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    private void stub(String body) {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** Redis 없이(널) 만든다 — 그 경로가 실제로 도는지가 이 클래스의 요점이다. */
    @Test
    @DisplayName("발급 본문의 앱시크릿에서 개행을 뗀다 — 안 떼면 403 EGW00105이고 진단이 어긋난다")
    void trimsCredentialsBeforeIssuing() {
        // ⚠️ CLAUDE.md가 EGW00105를 「키가 틀린 것이 아닐 수 있다 — 끝의 줄바꿈부터 뗀다」로
        //    적어 두고 실측 중에 그것을 「모의/실전 도메인이 안 맞는다」로 오진한 기록까지
        //    남겼는데, 그 remedy가 코드에 없었다. 붙여 넣은 값에 개행이 붙으면
        //    환율·국내 주식·미국 주식의 1순위가 한꺼번에 죽는다
        server.stubFor(post(urlPathEqualTo("/oauth2/tokenP")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + KisFixtures.TOKEN
                        + "\",\"access_token_token_expired\":\"2026-08-26 12:00:00\"}")));

        new KisTokenStore(RestClient.builder(), server.baseUrl(), "key\n", "secret  \n",
                null, Clock.fixed(NOW, ZoneOffset.UTC), KisFixtures.unpaced()).token();

        server.verify(postRequestedFor(urlPathEqualTo("/oauth2/tokenP"))
                .withRequestBody(WireMock.matchingJsonPath("$.appkey", WireMock.equalTo("key")))
                .withRequestBody(WireMock.matchingJsonPath("$.appsecret", WireMock.equalTo("secret"))));
    }

    private KisTokenStore store(Instant now) {
        return new KisTokenStore(RestClient.builder(), server.baseUrl(), "key", "secret",
                null, Clock.fixed(now, ZoneOffset.UTC), KisFixtures.unpaced());
    }

    private KisTokenStore store(Instant now, FakeRedis redis) {
        return new KisTokenStore(RestClient.builder(), server.baseUrl(), "key", "secret",
                redis.template(), Clock.fixed(now, ZoneOffset.UTC), KisFixtures.unpaced());
    }

    /**
     * 맵 하나짜리 Redis. <b>TTL은 재현하지 않는다</b> — 이 클래스가 보는 것은 "무엇이
     * 들어가고 무엇이 지워지는가"이고, 만료는 시계를 앞으로 돌려 확인한다.
     *
     * <p>{@code ValueOperations}는 메서드가 수십 개인데 우리가 쓰는 것은 셋이다. 그래서
     * 프록시로 그 셋만 답한다 — 나머지를 손으로 구현하면 읽을 것만 늘고 뜻이 흐려진다.
     */
    private static final class FakeRedis {

        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        @SuppressWarnings("unchecked")
        StringRedisTemplate template() {
            ValueOperations<String, String> ops = (ValueOperations<String, String>) Proxy
                    .newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {ValueOperations.class}, (proxy, method, args) ->
                            switch (method.getName()) {
                                case "get" -> values.get((String) args[0]);
                                case "set" -> {
                                    values.put((String) args[0], (String) args[1]);
                                    yield null;
                                }
                                case "setIfAbsent" ->
                                        values.putIfAbsent((String) args[0], (String) args[1]) == null;
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
            return new StringRedisTemplate() {
                @Override
                public ValueOperations<String, String> opsForValue() {
                    return ops;
                }

                @Override
                public Boolean delete(String key) {
                    return values.remove(key) != null;
                }
            };
        }
    }

    @Test
    @DisplayName("만료가 이미 지난 토큰은 담지 않고 던진다 — 담으면 호출마다 발급을 시도한다")
    void refusesATokenThatIsAlreadyExpired() {
        // 실측(2026-08-19): 죽은 토큰을 돌려줄 때 응답의 만료 시각이 요청 시각보다 20분
        // 일렀다. 그대로 담으면 usableAt()이 즉시 거짓이 되어 호출마다 발급을 시도하고,
        // 그건 1분 1회 제한을 우리 손으로 어기는 길이다 — 알림톡도 그만큼 간다
        stub("""
                {"access_token":"tok-dead","access_token_token_expired":"2026-08-18 16:40:00"}
                """);

        assertThatThrownBy(() -> store(NOW).token())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 만료된 토큰");
    }

    @Test
    @DisplayName("무효로 확인된 토큰을 버리면 6시간 동안 발급하지 않는다 — 앞당겨도 같은 죽은 토큰이 온다")
    void doesNotReissueInsideTheSameTokenWindow() {
        FakeRedis redis = new FakeRedis();
        stub("""
                {"access_token":"tok-1","access_token_token_expired":"2026-08-19 16:56:34"}
                """);
        KisTokenStore store = store(NOW, redis);
        assertThat(store.token()).isEqualTo("tok-1");

        store.invalidate();

        // 발급을 다시 시도하지 않고 던져야 상위 서비스가 다음 출처로 넘어간다
        assertThatThrownBy(store::token)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("무효로 확인돼");
        server.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(redis.values).doesNotContainKey("kis:token");
    }

    @Test
    @DisplayName("발급이 도장을 찍은 뒤에는 다시 발급하지 않는다 — 락 밖 검사만으로는 둘이 함께 통과한다")
    void neverIssuesTwiceAfterTheWindowWasStamped() {
        // ⚠️ 이것이 락 안에서 쿨다운을 다시 보는 이유다. token()의 검사는 락 **밖**이라,
        //    둘이 함께 통과한 뒤 첫 번째가 발급에 실패하며 도장을 찍어도 두 번째는 그것을
        //    못 본 채 락을 잡는다. 그러면 몇 초 사이에 발급이 두 번 나가 1분 1회 제한을
        //    지키려고 만든 이 설계가 스스로 그것을 어긴다 — 알림톡도 그만큼 간다
        FakeRedis redis = new FakeRedis();
        // 만료가 이미 지난 토큰: 첫 발급이 실패하면서 재발급 창을 세운다
        stub("""
                {"access_token":"tok-dead","access_token_token_expired":"2026-08-18 16:40:00"}
                """);

        assertThatThrownBy(() -> store(NOW, redis).token())
                .hasMessageContaining("이미 만료된 토큰");
        assertThat(redis.values).containsKey("kis:token:reissue-after");

        // 두 번째 호출 — 도장이 찍혔으므로 KIS를 부르지 않아야 한다
        assertThatThrownBy(() -> store(NOW, redis).token())
                .isInstanceOf(IllegalStateException.class);

        server.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("창이 지나면 스스로 낫는다 — 버려 둔 채로 굳지 않는다")
    void reissuesOnceTheWindowHasPassed() {
        FakeRedis redis = new FakeRedis();
        stub("""
                {"access_token":"tok-1","access_token_token_expired":"2026-08-19 16:56:34"}
                """);
        store(NOW, redis).token();
        store(NOW, redis).invalidate();

        stub("""
                {"access_token":"tok-2","access_token_token_expired":"2026-08-20 16:56:34"}
                """);
        // 발급 6시간 뒤 — 이제 KIS가 새 토큰을 준다
        assertThat(store(NOW.plusSeconds(6 * 3600 + 1), redis).token()).isEqualTo("tok-2");
    }

    @Test
    @DisplayName("두 칸짜리 옛 캐시 형식을 그대로 읽는다 — 못 읽으면 배포 순간 멀쩡한 토큰을 버린다")
    void readsTheOlderTwoFieldCacheFormat() {
        FakeRedis redis = new FakeRedis();
        // 발급 시각 칸이 붙기 전의 형식이다. 배포하는 순간 Redis에 이 모양이 들어 있다
        redis.values.put("kis:token", "tok-old|" + NOW.plusSeconds(86400).getEpochSecond());

        assertThat(store(NOW, redis).token()).isEqualTo("tok-old");
        server.verify(0, postRequestedFor(urlPathEqualTo(PATH)));
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
                null, Clock.fixed(NOW, ZoneOffset.UTC), KisFixtures.unpaced());

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
