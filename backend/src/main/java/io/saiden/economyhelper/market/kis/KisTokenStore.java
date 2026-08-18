package io.saiden.economyhelper.market.kis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 한국투자증권 접근토큰 — <b>캐시가 최적화가 아니라 필수다.</b>
 *
 * <p>실제로 발급해 확인한 성질이 넷이다.
 *
 * <ul>
 *   <li><b>유효기간이 24시간</b>이다({@code expires_in=86400}, 실측)
 *   <li><b>1분에 한 번만 발급된다.</b> 요청마다 발급하면 차단된다
 *   <li><b>발급마다 계정주에게 알림톡이 간다.</b> 차단이 아니라 이것이 더 큰 문제다 —
 *       요청마다 발급하면 사용자 휴대폰이 하루 종일 울린다
 *   <li>6시간 안에 다시 요청하면 <b>같은 토큰</b>을 준다. 즉 재발급은 이득이 없다
 * </ul>
 *
 * <p><b>만료는 {@code access_token_token_expired}로 읽는다.</b> {@code expires_in}은 기준 시각이
 * 없어 "언제부터 86400초인지"를 알 수 없다 — 서버는 만료로 보는데 우리는 유효로 보는 어긋남이
 * 보고돼 있다(수천 초 규모). 저쪽이 준 절대 시각을 쓰는 편이 정확하다. 그 문자열은
 * {@code yyyy-MM-dd HH:mm:ss} 꼴이고 <b>표준시대가 없다</b> — KST로 읽는다.
 *
 * <p><b>Redis에 두는 이유는 인스턴스가 늘어도 토큰이 하나여야 하기 때문이다</b>
 * ({@code FmpQuotaGuard}가 쿼터 카운터를 Redis에 두는 것과 같다).
 *
 * <p><b>Redis가 죽으면 프로세스 사본을 쓴다 — {@code FmpQuotaGuard}와 반대 방향이다.</b>
 * 쿼터 카운터는 못 읽으면 그냥 호출을 통과시킨다(한도 초과의 대가가 작다). 여기는 반대로
 * 발급이 비싸다(1분 제한 + 알림톡). 그래서 Redis가 안 되면 메모리에 든 것을 쓰고, 둘 다 없을
 * 때만 발급한다.
 */
@Component
public class KisTokenStore {

    private static final Logger log = LoggerFactory.getLogger(KisTokenStore.class);

    private static final String KEY = "kis:token";
    private static final String PATH = "/oauth2/tokenP";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 만료 직전을 유효로 보지 않는다.
     *
     * <p>발급이 1분에 한 번뿐이라, 만료 순간에 걸려 실패하면 그 뒤 1분이 통째로 막힌다.
     * 미리 갈아 두는 편이 싸다.
     */
    private static final Duration MARGIN = Duration.ofMinutes(10);

    private final RestClient restClient;
    private final String appKey;
    private final String appSecret;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /** Redis가 죽었을 때 쓸 사본. 발급이 비싸서 들고 있는다. */
    private volatile Cached memory;

    public KisTokenStore(RestClient.Builder builder,
                         @Value("${economy-helper.market.kis.base-url}") String baseUrl,
                         @Value("${economy-helper.market.kis.app-key:}") String appKey,
                         @Value("${economy-helper.market.kis.app-secret:}") String appSecret,
                         StringRedisTemplate redis,
                         Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * @return 아직 유효한 접근토큰
     * @throws IllegalStateException 키가 없거나 발급이 실패했을 때. <b>던져야</b> 상위
     *                               서비스({@code FxService}·{@code StockService})가 다음 출처로
     *                               넘어간다
     */
    public String token() {
        Instant now = clock.instant();

        Cached cached = memory;
        if (cached != null && cached.usableAt(now)) {
            return cached.token();
        }
        Cached shared = readShared(now);
        if (shared != null) {
            memory = shared;
            return shared.token();
        }
        return issue(now);
    }

    /** Redis에 든 것. 못 읽으면 {@code null} — 장애를 여기서 삼키고 메모리·발급으로 넘어간다. */
    private Cached readShared(Instant now) {
        try {
            String stored = redis.opsForValue().get(KEY);
            Cached parsed = Cached.parse(stored);
            return parsed != null && parsed.usableAt(now) ? parsed : null;
        } catch (RuntimeException e) {
            log.warn("[kis] 토큰 캐시(Redis) 조회 실패 — 메모리 사본으로 넘어갑니다: {}", e.toString());
            return null;
        }
    }

    private String issue(Instant now) {
        if (appKey.isBlank() || appSecret.isBlank()) {
            throw new IllegalStateException("KIS 앱키가 없습니다");
        }
        Issued issued = request();
        if (issued == null || issued.accessToken() == null || issued.accessToken().isBlank()) {
            throw new IllegalStateException("KIS 토큰 응답에 접근토큰이 없습니다");
        }
        Cached fresh = new Cached(issued.accessToken(), expiryOf(issued, now));
        memory = fresh;
        writeShared(fresh, now);
        log.info("[kis] 접근토큰을 발급했습니다 — 만료 {}", fresh.expiresAt());
        return fresh.token();
    }

    /**
     * {@code access_token_token_expired}를 KST로 읽는다. 못 읽으면 보수적으로 1시간만 믿는다 —
     * 형식이 바뀌었는데 24시간을 믿으면 그동안 401을 맞으면서도 재발급하지 않는다.
     */
    private Instant expiryOf(Issued issued, Instant now) {
        String raw = issued.expiredAt();
        if (raw == null || raw.isBlank()) {
            return now.plus(Duration.ofHours(1));
        }
        try {
            return LocalDateTime.parse(raw.trim(), EXPIRY).atZone(SEOUL).toInstant();
        } catch (RuntimeException e) {
            log.warn("[kis] 토큰 만료 시각을 해석하지 못했습니다 — 1시간만 씁니다: {}", raw);
            return now.plus(Duration.ofHours(1));
        }
    }

    private void writeShared(Cached fresh, Instant now) {
        Duration ttl = Duration.between(now, fresh.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(KEY, fresh.serialized(), ttl);
        } catch (RuntimeException e) {
            // 공유에 실패해도 이 인스턴스는 메모리 사본으로 돈다
            log.warn("[kis] 토큰 캐시(Redis) 저장 실패 — 이 인스턴스만 씁니다: {}", e.toString());
        }
    }

    /**
     * 예외를 <b>다시 감싸서</b> 던진다.
     *
     * <p>이 응답 본문에는 접근토큰이 들어 있다. RestClient 예외 메시지가 본문을 담을 수 있어
     * 그대로 흘리면 토큰이 로그에 남는다 — {@code FmpApi}·{@code KeximFxClient}가 URL에 실린
     * 키를 가리는 것과 같은 규칙이다.
     */
    private Issued request() {
        try {
            return restClient.post()
                    .uri(PATH)
                    .body(Map.of("grant_type", "client_credentials",
                            "appkey", appKey, "appsecret", appSecret))
                    .retrieve()
                    .body(Issued.class);
        } catch (RuntimeException e) {
            // 1분에 한 번뿐이라 연타하면 여기로 떨어진다. 그 사실을 메시지로 구분해 둔다
            log.warn("[kis] 토큰 발급 실패: {}", e.getClass().getSimpleName());
            throw new IllegalStateException(
                    "KIS 토큰 발급 실패 (1분에 한 번만 발급됩니다): " + e.getClass().getSimpleName());
        }
    }

    /** 토큰과 만료. Redis에는 {@code 토큰|만료초} 한 줄로 담는다 — 값 하나에 TTL 하나면 된다. */
    private record Cached(String token, Instant expiresAt) {

        boolean usableAt(Instant now) {
            return now.plus(MARGIN).isBefore(expiresAt);
        }

        String serialized() {
            return token + '|' + expiresAt.getEpochSecond();
        }

        static Cached parse(String stored) {
            if (stored == null) {
                return null;
            }
            int cut = stored.lastIndexOf('|');
            if (cut <= 0) {
                return null;
            }
            try {
                return new Cached(stored.substring(0, cut),
                        Instant.ofEpochSecond(Long.parseLong(stored.substring(cut + 1))));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * @param expiredAt {@code access_token_token_expired} — 표준시대 없는 KST 문자열.
     *                  {@code expires_in}은 기준 시각이 없어 쓰지 않는다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Issued(@JsonProperty("access_token") String accessToken,
                  @JsonProperty("access_token_token_expired") String expiredAt) {}
}
