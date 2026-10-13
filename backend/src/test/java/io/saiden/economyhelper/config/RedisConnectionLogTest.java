package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * 기동 때 Redis에 붙었는지 <b>로그 한 줄로 알 수 있어야 한다.</b> Lettuce는 첫 명령 때에야 연결하므로
 * 이것 없이는 첫 캐시 실패 경고가 나기 전까지 끊긴 줄 모른다. 못 붙어도 기동은 막지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisConnectionLogTest {

    @Test
    @DisplayName("PING이 돌아오면 연결됐다고 남기고 연결을 닫는다")
    void logsConnected(CapturedOutput output) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);

        new RedisConnectionLog(factory).check();

        assertThat(output).contains("[redis] 연결됨");
        verify(connection).close();
    }

    @Test
    @DisplayName("못 붙으면 경고만 남기고 예외를 밖으로 내보내지 않는다 — 캐시 없이도 답은 나간다")
    void survivesUnreachableRedis(CapturedOutput output) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        assertThatCode(() -> new RedisConnectionLog(factory).check()).doesNotThrowAnyException();

        assertThat(output).contains("[redis] 연결 실패").doesNotContain("[redis] 연결됨");
    }

    @Test
    @DisplayName("주소는 남기되 비밀번호는 남기지 않는다")
    void logsAddressWithoutPassword(CapturedOutput output) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 1);
        config.setPassword("s3cret");
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        try {
            new RedisConnectionLog(factory).check();
        } finally {
            factory.destroy();
        }

        assertThat(output).contains("localhost:1").doesNotContain("s3cret");
    }
}
