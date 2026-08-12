package io.saiden.economyhelper.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정기 발송 스케줄과 분산 락.
 *
 * <p>{@code CLAUDE.md}의 이중화 요구(replicas 2) 때문에 필요하다 — 스케줄러는 모든
 * 인스턴스에서 똑같이 09시에 깨어난다. 락이 없으면 구독자는 같은 뉴스를 두 번 받는다.
 *
 * <p>{@code defaultLockAtMostFor}는 <b>인스턴스가 락을 쥔 채 죽었을 때</b>의 안전장치다.
 * 이 시간이 지나면 락이 강제로 풀린다. 잡의 최악 실행 시간(피드 5개 + 번역 5회)보다
 * 넉넉히 길어야 하고, 그렇다고 다음 슬롯(12시간 뒤)까지 남으면 안 된다.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT20M")
public class SchedulingConfig {

    @Bean
    LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider.Builder(connectionFactory)
                .environment("economy-helper")
                .build();
    }
}
