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
 * <p><b>KIS가 토큰을 거절하면 버린다</b>({@link #invalidate()}). 무효 토큰에 401이 아니라
 * HTTP 500이 오므로 호출자가 그걸 알아보고 불러 준다({@code KisHeaders.isInvalidToken}).
 * <b>이것이 없던 동안 죽은 토큰이 기록된 만료까지 최대 24시간 남아 모든 KIS 호출이 실패했다.</b>
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

    /**
     * "이 시각 뒤에 다시 발급해도 된다" 도장. <b>죽은 토큰을 버렸다는 사실을 인스턴스끼리
     * 공유하는 자리</b>이기도 하다 — 이게 없으면 인스턴스마다 따로 재발급을 시도한다.
     */
    private static final String REISSUE_AFTER_KEY = "kis:token:reissue-after";

    /** 발급을 직렬화하는 락. 동시 발급은 서로를 무효화한다 — 그 상태를 우리가 만들지 않는다. */
    private static final String LOCK_KEY = "kis:token:issuing";

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

    /**
     * <b>발급 뒤 이 시간 안의 재요청에는 KIS가 같은 토큰을 돌려준다</b>(실측 — 클래스 javadoc의
     * 네 번째 성질). 그 규칙이 <b>죽은 토큰에도 걸린다</b>는 것이 함정이다: 캐시를 지우고 다시
     * 발급해도 같은 죽은 토큰이 온다. 그래서 무효를 알아차려도 이만큼은 기다려야 하고,
     * 기다리지 않으면 알림톡만 한 번 더 가고 결과는 같다.
     */
    private static final Duration SAME_TOKEN_WINDOW = Duration.ofHours(6);

    /** 발급 락을 쥐고 있는 시간. 발급 한 번은 1초대이므로 넉넉하고, 죽어도 곧 풀린다. */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final String appKey;
    private final String appSecret;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final KisThrottle throttle;

    /** Redis가 죽었을 때 쓸 사본. 발급이 비싸서 들고 있는다. */
    private volatile Cached memory;

    public KisTokenStore(RestClient.Builder builder,
                         @Value("${economy-helper.market.kis.base-url}") String baseUrl,
                         @Value("${economy-helper.market.kis.app-key:}") String appKey,
                         @Value("${economy-helper.market.kis.app-secret:}") String appSecret,
                         StringRedisTemplate redis,
                         Clock clock,
                         KisThrottle throttle) {
        this.restClient = builder.baseUrl(baseUrl).build();
        // ⚠️ **끝의 줄바꿈을 뗀다.** 대시보드나 .env에 붙여 넣은 값은 끝에 개행·공백이 붙기 쉽고,
        //    그대로 실으면 토큰 발급이 403 EGW00105(「유효하지 않은 AppSecret」)로 떨어진다 —
        //    그건 키가 틀렸다는 뜻이 아니라 **우리가 값을 잘못 실었다**는 뜻이라 진단이 어긋난다
        //    (실측으로 그렇게 오진해 설정을 실전 도메인으로 바꿨다가 되돌린 적이 있다).
        //    KIS는 환율·국내 주식·미국 주식의 1순위라 이 한 글자가 셋을 함께 죽인다.
        //    TelegramWebhookController가 웹훅 secret에 같은 것을 하고 있다
        this.appKey = trimmed(appKey);
        this.appSecret = trimmed(appSecret);
        this.redis = redis;
        this.clock = clock;
        this.throttle = throttle;
    }

    /** 붙여 넣기가 남긴 개행·공백을 뗀다. {@code null}은 빈 문자열로 — 키가 없는 것과 같다. */
    private static String trimmed(String key) {
        return key == null ? "" : key.trim();
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
        // 죽은 토큰을 버린 직후다. 이 창 안에서는 발급해도 같은 죽은 토큰이 오므로
        // 부르지 않고 던진다 — 던져야 상위 서비스가 다음 출처로 넘어간다
        Instant reissueAfter = reissueAfter();
        if (reissueAfter != null && now.isBefore(reissueAfter)) {
            throw new IllegalStateException(
                    "KIS 토큰이 무효로 확인돼 버렸습니다 — " + Duration.between(now, reissueAfter).toMinutes()
                            + "분 뒤에 다시 발급합니다 (6시간 안에는 같은 죽은 토큰이 돌아옵니다)");
        }
        return issue(now);
    }

    /**
     * <b>KIS가 이 토큰을 거절했다</b>({@code msg_cd=EGW00121}) — 들고 있는 것을 버린다.
     *
     * <p><b>이것이 없어서 {@code /stock 유아이패스}가 하루 종일 빈손이었다.</b> 무효 토큰에
     * KIS는 401이 아니라 <b>HTTP 500</b>을 주고, 호출자는 그 이유를 로그에 적고 다음 거래소로
     * 넘어갔다. 그동안 <b>토큰은 기록된 만료 시각까지(최대 24시간) 그대로 남아</b> 모든 KIS
     * 호출이 같은 이유로 실패했다. 미국 종목은 2순위(FMP)가 심볼별 허용목록이라
     * {@code PATH}·{@code ORCL}·{@code SNOW}를 402로 막으므로 그 창 동안 통째로 빈손이다.
     *
     * <p><b>즉시 재발급하지 않는다.</b> {@link #SAME_TOKEN_WINDOW} 안에는 같은 죽은 토큰이
     * 오기 때문이다. 대신 "언제 뒤에 발급해도 되는지"를 Redis에 남기고, 그 시각이 지나면
     * {@link #token()}의 평상시 경로가 새로 받아 스스로 낫는다.
     *
     * <p>⚠️ <b>{@code EGW00304}(잘못된 앱시크릿)에는 부르지 않는다.</b> 그것도 500으로 오지만
     * 재발급으로 낫지 않는다 — 사람이 키를 고쳐야 한다. 버리면 멀쩡한 토큰을 잃고 알림톡만
     * 한 번 더 간다. 판정은 {@link KisHeaders#isInvalidToken}이 한다.
     */
    public void invalidate() {
        Instant now = clock.instant();
        Cached dead = memory != null ? memory : readAny();
        // 발급 시각을 알면 정확히 그때부터 6시간이다. 모르면(옛 형식) 지금부터 세어
        // 규칙을 어기지 않는 쪽으로 기운다 — 늦게 낫는 것이 알림톡을 헛되이 쓰는 것보다 낫다
        Instant issuedAt = dead == null || dead.issuedAt() == null ? now : dead.issuedAt();
        Instant reissueAfter = issuedAt.plus(SAME_TOKEN_WINDOW);

        memory = null;
        try {
            redis.delete(KEY);
        } catch (RuntimeException e) {
            // 공유에 실패해도 이 인스턴스는 사본을 버렸다. 다른 인스턴스가 제 차례에 겪는다
            log.warn("[kis] 무효 토큰 삭제(Redis) 실패 — 이 인스턴스만 버립니다: {}", e.toString());
        }
        markReissueAfter(reissueAfter, now);
        log.error("[kis] 토큰이 무효입니다(EGW00121) — 버렸습니다. {}부터 다시 발급합니다. "
                + "재발급을 앞당겨도 낫지 않습니다: 발급 6시간 안의 재요청에는 KIS가 같은 죽은 "
                + "토큰을 돌려줍니다", reissueAfter);
    }

    /** 재발급 허용 시각. 없거나 못 읽으면 {@code null} — 그때는 막지 않는다. */
    private Instant reissueAfter() {
        try {
            String stored = redis.opsForValue().get(REISSUE_AFTER_KEY);
            return stored == null ? null : Instant.ofEpochSecond(Long.parseLong(stored.trim()));
        } catch (RuntimeException e) {
            // 못 읽으면 막지 않는다 — 도장이 깨졌다고 KIS를 영영 못 부르게 하지 않는다
            return null;
        }
    }

    /** 만료를 따지지 않고 든 것을 그대로 — 버릴 때는 죽은 것의 발급 시각이 필요하다. */
    private Cached readAny() {
        try {
            return Cached.parse(redis.opsForValue().get(KEY));
        } catch (RuntimeException e) {
            return null;
        }
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
        // ⚠️ 인스턴스가 둘이면 동시 발급이 서로를 무효화한다 — 앱키당 활성 토큰이 하나이고
        //    1분 안의 두 번째 발급이 뒤의 것을 죽인다. 그 상태를 우리 손으로 만들지 않는다.
        //    락을 못 잡으면 기다리지 않고 던진다: 상대가 곧 Redis에 넣을 것이고, 그때까지
        //    이 요청은 다음 출처로 넘어가는 편이 사용자를 덜 기다리게 한다
        if (!acquireIssuingLock()) {
            throw new IllegalStateException(
                    "KIS 토큰을 다른 인스턴스가 발급하는 중입니다 — 동시 발급은 서로를 무효화합니다");
        }
        try {
            // 락을 잡는 사이 상대가 넣었을 수 있다. 여기서 한 번 더 보는 것이 알림톡 한 통이다
            Cached shared = readShared(clock.instant());
            if (shared != null) {
                memory = shared;
                return shared.token();
            }
            // ⚠️ 쿨다운도 여기서 다시 본다. token()의 검사는 락 **밖**이라, 둘이 함께 통과한 뒤
            //    첫 번째가 발급에 실패하며 도장을 찍어도(만료 역전·무효 확인) 두 번째는 그것을
            //    못 본 채 락을 잡는다. 그러면 몇 초 사이에 발급이 두 번 나가 **1분 1회 제한을
            //    지키려고 만든 이 설계가 스스로 그것을 어긴다** — 알림톡도 그만큼 간다.
            //    락 밖의 검사는 줄을 안 세우기 위한 것이고, 진짜 판정은 이 안쪽이다
            Instant blockedUntil = reissueAfter();
            if (blockedUntil != null && clock.instant().isBefore(blockedUntil)) {
                throw new IllegalStateException(
                        "KIS 토큰 재발급이 " + blockedUntil + "까지 막혀 있습니다 — "
                                + "6시간 안에는 같은 죽은 토큰이 돌아옵니다");
            }
            Issued issued = request();
            if (issued == null || issued.accessToken() == null || issued.accessToken().isBlank()) {
                throw new IllegalStateException("KIS 토큰 응답에 접근토큰이 없습니다");
            }
            Cached fresh = new Cached(issued.accessToken(), expiryOf(issued, now), now);
            // ⚠️ 만료가 **이미 지난** 토큰이 온다. 실측(2026-08-19)으로 응답의 만료 시각이
            //    요청 시각보다 20분 일렀다 — 죽은 토큰을 돌려줄 때의 모양이다. 이걸 그냥
            //    담으면 usableAt()이 즉시 거짓이 되어 **호출마다 발급을 시도**하고, 그건
            //    1분 1회 제한을 우리가 어기는 길이다(알림톡도 그만큼 간다). 창을 세우고 던진다.
            //    ⚠️ MARGIN(10분)으로 재지 않는다 — 5분 남은 토큰은 5분 동안 진짜로 쓸 수 있고,
            //    그걸 죽었다고 하면 수명 끝에 걸린 정상 발급까지 막는다. 재는 것은 '지났는가'다
            if (!fresh.expiresAt().isAfter(now)) {
                markReissueAfter(now.plus(SAME_TOKEN_WINDOW), now);
                throw new IllegalStateException(
                        "KIS가 이미 만료된 토큰을 줬습니다 (만료 " + fresh.expiresAt()
                                + ") — 앞서 발급한 토큰이 죽었다는 뜻입니다. 6시간 뒤에 다시 받습니다");
            }
            memory = fresh;
            writeShared(fresh, now);
            log.info("[kis] 접근토큰을 발급했습니다 — 만료 {}", fresh.expiresAt());
            return fresh.token();
        } finally {
            releaseIssuingLock();
        }
    }

    /**
     * @return 락을 잡았는가. <b>Redis가 죽으면 참을 준다</b> — 그때는 인스턴스가 서로를 못 보므로
     *         막을 방법이 없고, 발급을 아예 못 하게 하면 Redis 장애가 KIS 장애로 번진다
     *         ({@code FmpQuotaGuard}가 카운터를 못 읽을 때 통과시키는 것과 같은 방향이다)
     */
    private boolean acquireIssuingLock() {
        try {
            return !Boolean.FALSE.equals(
                    redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL));
        } catch (RuntimeException e) {
            log.warn("[kis] 발급 락(Redis) 실패 — 락 없이 발급합니다: {}", e.toString());
            return true;
        }
    }

    private void releaseIssuingLock() {
        try {
            redis.delete(LOCK_KEY);
        } catch (RuntimeException e) {
            // TTL이 곧 풀어 준다
            log.debug("[kis] 발급 락 해제 실패: {}", e.toString());
        }
    }

    /** 재발급 허용 시각을 남긴다. {@link #invalidate()}와 만료 역전이 함께 쓴다. */
    private void markReissueAfter(Instant reissueAfter, Instant now) {
        Duration ttl = Duration.between(now, reissueAfter);
        if (!ttl.isPositive()) {
            return;
        }
        try {
            redis.opsForValue().set(REISSUE_AFTER_KEY,
                    Long.toString(reissueAfter.getEpochSecond()), ttl);
        } catch (RuntimeException e) {
            log.warn("[kis] 재발급 시각 표시(Redis) 실패: {}", e.toString());
        }
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
        // ⚠️ **발급도 같은 앱키의 호출이다.** 예전에는 이 자리에 문이 없어도 가려져 있었다 —
        //    KisTokenStore·KisFxClient·KisStockApi가 각자 다른 커넥션 풀을 썼기 때문이다.
        //    HttpTimeouts가 (connect, read) 쌍마다 풀을 하나로 묶으면서 셋이 처음으로 풀을
        //    공유하는데, 그러면 토큰 캐시가 빈 조회에서 토큰 POST와 시세 GET이 **간격 없이
        //    같은 커넥션으로** 나간다. KisThrottle의 실측 표가 정확히 그 모양을 거절한다
        //    (재사용 커넥션 · 0.2초 간격 → HTTP 500 초당 거래건수 초과).
        //    대가는 24시간에 한 번 1초다.
        throttle.pace();
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

    /**
     * 토큰과 만료, 그리고 <b>발급 시각</b>. Redis에는 {@code 토큰|만료초|발급초} 한 줄로 담는다.
     *
     * <p><b>발급 시각을 담는 이유는 {@link #invalidate()} 하나다.</b> 무효를 알아차렸을 때
     * "언제부터 6시간인가"를 알아야 하는데, 그 기준이 발급 시각이다. 없으면 알아차린 시각부터
     * 세어 최대 6시간을 더 기다리게 된다.
     *
     * <p>⚠️ <b>두 칸짜리 옛 형식을 그대로 읽는다.</b> 배포 순간 Redis에 이미 들어 있는 값이
     * 그 모양이고, 못 읽으면 {@code null}이 되어 <b>멀쩡한 토큰을 버리고 재발급</b>한다 —
     * 1분 1회 제한과 알림톡이 걸린 자리에서 그건 비싼 실수다.
     */
    private record Cached(String token, Instant expiresAt, Instant issuedAt) {

        boolean usableAt(Instant now) {
            return now.plus(MARGIN).isBefore(expiresAt);
        }

        String serialized() {
            return token + '|' + expiresAt.getEpochSecond()
                    + '|' + (issuedAt == null ? "" : Long.toString(issuedAt.getEpochSecond()));
        }

        static Cached parse(String stored) {
            if (stored == null) {
                return null;
            }
            String[] parts = stored.split("\\|");
            // 토큰 자체에 |가 없다는 보장이 없어 앞에서 자르지 않는다 — 뒤 두 칸만 숫자다
            if (parts.length < 2) {
                return null;
            }
            try {
                Instant issuedAt = null;
                int expiryAt = parts.length - 1;
                if (parts.length >= 3) {
                    expiryAt = parts.length - 2;
                    String tail = parts[parts.length - 1];
                    issuedAt = tail.isBlank() ? null : Instant.ofEpochSecond(Long.parseLong(tail));
                }
                Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[expiryAt]));
                String token = String.join("|", java.util.Arrays.copyOfRange(parts, 0, expiryAt));
                return token.isBlank() ? null : new Cached(token, expiresAt, issuedAt);
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
