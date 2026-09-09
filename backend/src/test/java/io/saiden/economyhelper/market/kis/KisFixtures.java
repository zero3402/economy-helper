package io.saiden.economyhelper.market.kis;

import java.time.Clock;
import java.time.Duration;
import org.springframework.web.client.RestClient;

/**
 * KIS 테스트 셋이 나눠 쓰는 가짜 — <b>같은 것을 두 번 쓰지 않기 위해서다.</b>
 *
 * <p>{@code FixedToken}이 {@code KisStockApiTest}와 {@code KisFxClientTest}에 <b>따로</b> 있었다.
 * 뒤엣것은 앞엣것에서 {@code invalidated} 플래그만 뺀 판이었고, javadoc 한 줄까지 같았다.
 * 그 값을 증명한 편집이 있다 — {@code KisThrottle}이 생성자에 붙던 날, <b>똑같은 두 줄 수정을
 * 두 파일에</b> 해야 했다.
 */
final class KisFixtures {

    /** 실측 응답에서 가져온 모양. 값 자체에 뜻은 없다 — 헤더에 실려 새지 않는지가 관심사다. */
    static final String TOKEN = "secret-token-1234";

    private KisFixtures() {
    }

    /**
     * 기다리지 않는 문. 간격을 세는 것은 실물 계정의 제약이라 테스트에서는 뺀다 —
     * 규칙 자체는 {@code KisThrottleTest}가 본다. 예전에는 {@code KisFixtures.unpaced()}으로
     * <b>main에</b> 있었다: 테스트만 부르는 코드가 운영 클래스에 살고 있었다.
     */
    static KisThrottle unpaced() {
        return new KisThrottle(Duration.ZERO, Duration.ZERO);
    }

    /**
     * 발급을 흉내 내지 않는 토큰 저장소 — <b>토큰 재사용 규칙은 {@code KisTokenStoreTest}가 본다.</b>
     *
     * <p>{@code invalidated}를 기록한다. 무효 토큰({@code EGW00121})을 알아차렸을 때 버리는지가
     * KIS 클라이언트 둘의 계약이고, 어느 쪽이 먼저 알아차려도 같은 일을 해야 한다.
     */
    static final class FixedToken extends KisTokenStore {

        private boolean invalidated;

        FixedToken(Clock clock) {
            super(RestClient.builder(), "http://localhost:1", "key", "secret", null, clock,
                    KisFixtures.unpaced());
        }

        @Override
        public String token() {
            return TOKEN;
        }

        @Override
        public void invalidate() {
            invalidated = true;
        }

        boolean invalidated() {
            return invalidated;
        }
    }

    /**
     * 테스트용 {@link KisCall} — <b>네 인자를 네 파일에 적지 않기 위해서다.</b>
     *
     * <p>{@code KisCall}이 생기기 전에는 클라이언트 셋의 생성자가 저마다
     * {@code (builder, baseUrl, tokens, headers, throttle, …)}을 받아, 간격 문이 붙던 날
     * <b>같은 수정을 세 파일에</b> 해야 했다({@link FixedToken}의 javadoc이 그 기록이다).
     * 이제 그 조립이 한 줄이다.
     *
     * @param throttle 간격을 세는 문. 대개 {@link #unpaced()}이고, 거절을 단언하는 테스트만
     *                 제 것을 넣는다
     */
    static KisCall call(String baseUrl, KisTokenStore tokens, KisThrottle throttle) {
        return new KisCall(RestClient.builder(), baseUrl, tokens,
                new KisHeaders("key", "secret"), throttle);
    }

    /** 간격을 안 세는 판 — 대부분의 테스트가 이것을 쓴다. */
    static KisCall call(String baseUrl, KisTokenStore tokens) {
        return call(baseUrl, tokens, unpaced());
    }
}
