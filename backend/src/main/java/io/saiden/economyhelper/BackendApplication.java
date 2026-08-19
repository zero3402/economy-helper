package io.saiden.economyhelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.core.Ordered;

/**
 * <b>{@code @EnableCaching}에 order를 준다</b> — 캐시가 회복탄력성 애스펙트보다 <b>바깥</b>이어야 한다.
 *
 * <p>기본값으로 두면 반대가 된다. 실측한 order는 이렇다:
 *
 * <ul>
 *   <li>{@code circuitBreakerAspectOrder} = {@code LOWEST_PRECEDENCE - 4} = 2147483643
 *   <li>{@code rateLimiterAspectOrder} = {@code LOWEST_PRECEDENCE - 3} = 2147483644
 *   <li>캐시 어드바이저(기본) = {@code LOWEST_PRECEDENCE} = 2147483647
 * </ul>
 *
 * 값이 작을수록 바깥이므로 <b>브레이커 → 리미터 → 캐시</b> 순이었고, 그래서 두 가지가 깨졌다:
 *
 * <ol>
 *   <li><b>캐시 히트가 리미터 퍼밋을 태웠다.</b> KIS는 초당 1건이라 브리핑의 KIS 조회 9회가
 *       전부 캐시에 있어도 ~8초를 기다렸다 — 캐시가 아무것도 아껴 주지 않는 상태다.
 *   <li><b>브레이커가 열리면 캐시된 값조차 못 읽었다.</b> {@code accu-location} 30일 캐시를
 *       "하루 50회 한도의 실질 방어"라고 적어 뒀는데, 그 방어가 브레이커 앞에서 무력해진다.
 * </ol>
 *
 * <p>캐시를 가장 바깥에 두면 히트가 두 애스펙트를 아예 타지 않는다. 미스일 때만 리미터와
 * 브레이커를 거치는데, 그게 원래 의도다 — 둘은 <b>외부 호출</b>을 보호하는 장치이고 캐시
 * 히트는 외부 호출이 아니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 10)
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
