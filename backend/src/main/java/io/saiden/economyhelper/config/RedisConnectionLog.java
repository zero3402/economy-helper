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
 * 틀린 줄 모른다. <b>못 붙어도 기동은 막지 않는다.</b> 캐시는 부가 기능이다({@link CacheErrorConfig}).
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
            log.warn("[redis] 연결 실패 {} — 캐시 없이 갑니다: {}", address, FailureReason.of(e));
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
