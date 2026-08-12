package io.saiden.economyhelper.market.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 토스증권 OAuth2 액세스 토큰을 관리한다 — {@code /fx}와 {@code /stock}이 공유한다.
 *
 * <p><b>보호는 애노테이션이 아니라 프로그래매틱 서킷브레이커로 건다.</b> {@code token()}이
 * {@code issue()}를 부르는 건 자기 호출이라 프록시를 타지 않는다 — 애노테이션을 붙여 봐야
 * 조용히 무력화된다({@code FeedFetcher}가 같은 이유로 {@code circuitBreaker("feed-"+source)}를
 * 직접 쓴다). 레이트리미터는 걸지 않는다: 토큰은 인스턴스당 하루 한 번 발급되므로
 * {@code AUTH} 그룹(초당 5회)에 닿을 일이 없다. 정작 위험한 건 <b>발급이 계속 실패할 때</b>
 * 매 요청이 재발급을 시도하는 것이고, 그건 브레이커가 막는다.
 *
 * <p><b>Redis가 아니라 메모리에 든다.</b> 실측 {@code expires_in}이 86,399초(24시간)라
 * 인스턴스당 하루 한 번 발급하면 되고, replicas가 둘이어도 하루 2회다 —
 * {@code AUTH} 한도에 영향이 없다. 캐시 TTL과 실제 만료가 어긋날 위험을 만들 이유가 없어
 * <b>서버가 준 {@code expires_in}을 그대로 쓴다.</b>
 */
@Component
public class TossTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TossTokenProvider.class);

    /** 만료 직전에 쓰다 401을 맞지 않도록 앞당겨 갱신한다. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final CircuitBreaker breaker;

    /** 발급된 토큰과 그 만료 시각. 여러 스레드가 동시에 읽는다. */
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public TossTokenProvider(RestClient.Builder builder,
                             @Value("${economy-helper.market.toss.base-url}") String baseUrl,
                             @Value("${economy-helper.market.toss.client-id:}") String clientId,
                             @Value("${economy-helper.market.toss.client-secret:}") String clientSecret,
                             Clock clock,
                             CircuitBreakerRegistry circuitBreakers) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
        this.breaker = circuitBreakers.circuitBreaker("tossAuth");
    }

    /**
     * 유효한 토큰. 없거나 만료가 임박했으면 새로 받는다.
     *
     * <p>동시에 여러 스레드가 들어오면 발급이 겹칠 수 있다. 락을 걸지 않는 이유는
     * 겹쳐도 <b>둘 다 유효한 토큰</b>을 받고 {@code AUTH} 한도(초당 5회)에도 여유가 있어서다 —
     * 락으로 얻을 게 없다.
     */
    public String token() {
        CachedToken current = cached.get();
        if (current != null && current.isValidAt(clock.instant())) {
            return current.accessToken();
        }
        CachedToken issued = breaker.executeSupplier(this::issue);
        cached.set(issued);
        return issued.accessToken();
    }

    /**
     * 들고 있는 토큰을 버린다 — <b>401을 받은 호출자가 부른다.</b>
     *
     * <p>서버가 우리보다 먼저 토큰을 무효화할 수 있다(키 재발급, 세션 정리). 그 경우
     * 만료 시각만 믿으면 계속 죽은 토큰을 보낸다.
     */
    public void invalidate() {
        cached.set(null);
    }

    private CachedToken issue() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        TokenResponse response = restClient.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            // 토큰 값은 절대 로그에 남기지 않는다 — 남으면 그 자체로 자격증명 유출이다
            throw new IllegalStateException("토스 토큰 응답에 access_token이 없습니다");
        }

        long expiresIn = response.expiresIn() == null ? 0 : response.expiresIn();
        Instant expiresAt = clock.instant().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
        log.info("[toss] 액세스 토큰 발급 — {}초 유효", expiresIn);
        return new CachedToken(response.accessToken(), expiresAt);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") Long expiresIn) {}
}
