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
 * 이 시간이 지나면 락이 강제로 풀린다. 잡의 최악 실행 시간(피드 여섯 + 번역 + KIS 아홉 번의
 * 간격 대기)보다
 * 넉넉히 길어야 한다. 반대쪽 걱정은 없다 — 슬롯 키에 날짜가 들어 있어({@code DigestSlot})
 * 내일 키는 애초에 다른 키다. 그래서 이력이 오래 남아도 다음 발송을 막지 못한다.
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
