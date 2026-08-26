package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>테스트가 스케줄 잡을 띄우지 않는다</b>는 것을 못 박는다.
 *
 * <p><b>왜 필요한가.</b> {@code @EnableScheduling}이 여섯 {@code @SpringBootTest} 컨텍스트에서
 * 살아 있고 {@code @DirtiesContext}가 없어 그 컨텍스트가 포크 JVM이 끝날 때까지 캐시에 남는다.
 * 그리고 그 컨텍스트의 base-url은 <b>운영 실주소</b>다. 그래서 운영 크론(브리핑 09~10시 ·
 * 날씨 06~07시 KST)이 테스트 도중 발화해 <b>키가 필요 없는 곳으로 실제 호출이 나갔다</b> —
 * 업비트·바이낸스·Frankfurter·Open-Meteo 셋·HN Algolia·RSS 여섯 곳.
 *
 * <p>막는 것은 {@code src/test/resources/application.properties}의 크론 셋이다. 그 파일이
 * 지워지거나 키 이름이 바뀌면 <b>아무 테스트도 안 깨지고</b> 조용히 다시 호출이 나가므로,
 * 이 그물을 둔다 — {@code CacheConfigTest}·{@code ResilienceConfigTest}와 같은 자리다.
 *
 * <p>⚠️ <b>등록된 작업 수를 본다</b>(속성 값이 아니라). 값만 보면 「{@code -}가 정말 등록을
 * 막는가」를 안 지킨다 — 스프링이 그 관례를 바꾸는 날 이 그물이 뚫린다.
 */
@SpringBootTest
class SchedulingOffInTestsTest {

    @DynamicPropertySource
    static void noRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired ScheduledAnnotationBeanPostProcessor scheduled;
    @Autowired Environment environment;

    @Test
    @DisplayName("크론 셋이 다 꺼져 있다 — 안 끄면 테스트가 실제 외부 호출을 낸다")
    void disablesEveryCron() {
        assertThat(environment.getProperty("economy-helper.digest.cron")).isEqualTo("-");
        assertThat(environment.getProperty("economy-helper.weather.cron")).isEqualTo("-");
        assertThat(environment.getProperty("economy-helper.keep-warm.cron")).isEqualTo("-");
    }

    @Test
    @DisplayName("등록된 스케줄 작업이 하나도 없다 — 값이 아니라 결과를 본다")
    void registersNoScheduledTask() {
        assertThat(scheduled.getScheduledTasks())
                .as("하나라도 등록되면 그 크론이 테스트 도중 발화한다")
                .isEmpty();
    }

    @Test
    @DisplayName("그래도 본 application.yml은 살아 있다 — .properties는 겹치는 키만 이긴다")
    void keepsTheRealConfiguration() {
        assertThat(environment.getProperty("economy-helper.weather.zone"))
                .as("test/application.yml로 뒀다면 본 yml이 통째로 가려져 이 값이 없다")
                .isEqualTo("Asia/Seoul");
        assertThat(environment.getProperty("economy-helper.market.kis.min-interval"))
                .isEqualTo("1s");
    }
}
