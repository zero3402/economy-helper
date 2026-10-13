package io.saiden.economyhelper.config;

import io.saiden.economyhelper.shared.support.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝나면 Redis에 PING을 한 번 보내 붙었는지 로그로 남긴다.
 *
 * <p>Lettuce는 첫 명령 때에야 연결한다 — 이것 없이는 첫 캐시 실패 경고가 나기 전까지 주소·인증이
 * 틀린 줄 모른다. <b>못 붙어도 기동은 막지 않는다.</b> 명령 답장은 Redis 없이도 나간다.
 *
 * <p>⚠️ 다만 꺼지는 것이 캐시만이 아니다 — 경고가 그 셋을 다 말해야 한다.
 * <ul>
 *   <li>캐시({@link CacheErrorConfig}) — 미스로 보고 출처를 매번 부른다. 한도는 리미터·브레이커가 맡는다
 *   <li>웹훅 중복 가리기({@code ProcessedUpdates}의 {@code update_id}) — 재전송에 같은 답이 또 나갈 수 있다
 *   <li>정기 발송 — 발송 기록({@code DigestSlot})과 ShedLock 잠금이 Redis라 브리핑·날씨 알람이 나가지 않는다
 * </ul>
 *
 * <p>PING이 기동 준비(readiness)보다 먼저 돈다 — 닿지 않는 주소면 연결 타임아웃만큼 준비가 늦는다.
 * 그 상한이 {@code spring.data.redis.connect-timeout}이다({@code application.yml}).
 */
@Component
public class RedisConnectionLog {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionLog.class);

    private final RedisConnectionFactory factory;

    public RedisConnectionLog(RedisConnectionFactory factory) {
        this.factory = factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        String address = address();
        try (RedisConnection connection = factory.getConnection()) {
            log.info("[redis] 연결됨 {} ({})", address, connection.ping());
        } catch (RuntimeException e) {
            log.warn("[redis] 연결 실패 {} — 캐시 없이 가고, 웹훅 중복(update_id)을 가리지 못하며, "
                    + "정기 발송(발송 기록·ShedLock)이 나가지 않습니다: {}", address, FailureReason.of(e));
        }
    }

    /** 주소만 남긴다 — 비밀번호·사용자 이름은 남기지 않는다. */
    private String address() {
        if (factory instanceof LettuceConnectionFactory lettuce) {
            return lettuce.getHostName() + ":" + lettuce.getPort() + (lettuce.isUseSsl() ? " (TLS)" : "");
        }
        return "";
    }
}
