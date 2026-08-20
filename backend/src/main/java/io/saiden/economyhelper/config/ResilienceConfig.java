package io.saiden.economyhelper.config;

import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import io.saiden.economyhelper.support.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 재시도가 <b>조용히 성공하는 것</b>을 막는다.
 *
 * <p><b>이것이 재시도를 들이면서 함께 생기는 유일한 새 위험이다.</b> 3회째에 성공한 호출은
 * 흔적을 남기지 않는다 — 값이 캐시에 들어가고, 화면은 멀쩡하고, 사용자는 조금 늦은 것도
 * 모른다. 상대가 3분의 2 확률로 죽어 가는데 <b>아무도 모르는 상태</b>가 된다. 재시도가 없던
 * 동안에는 그런 상대가 곧 실패로 드러나 로그에 남았다.
 *
 * <p>그래서 <b>재시도 후 성공만 WARN</b>이다. 다 쓰고 실패한 것은 여기서 남기지 않는다 —
 * 호출부가 이미 남긴다({@code FxService}·{@code WeatherService}·{@code FeedFetcher}). 한 실패에
 * 두 줄이 되면 로그를 읽는 사람이 두 번 놀란다. 도메인 태그({@code [fx]}·{@code [weather]})도
 * 호출부의 것이다 — {@code Failover} javadoc이 적어 둔 그 규칙이다.
 *
 * <p><b>메트릭은 더할 것이 없다.</b> {@code resilience4j-micrometer}와 액추에이터가 이미 있고
 * {@code metrics}가 이미 노출돼 있어, 인스턴스가 생기는 순간
 * {@code /actuator/metrics/resilience4j.retry.calls}의 {@code kind=successful_with_retry}가
 * 열린다. <b>거기가 0이 아니면 그 출처가 나빠지고 있다는 뜻</b>이고, 그것이 이 작업의 실제
 * 산출물이다. 새 코드도 새 의존성도 없다.
 *
 * <p>이 클래스가 생기기 전에는 {@code ResilienceConfigTest}만 있고 짝이 없었다 — 설정이 전부
 * yml이라 그랬다. 규칙은 여전히 yml에 있고 여기 있는 것은 <b>그 규칙이 일할 때 보이게 하는
 * 일</b>뿐이다.
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    RegistryEventConsumer<Retry> retryLogging() {
        return new RegistryEventConsumer<>() {

            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> added) {
                attach(added.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> removed) {
                // 걷어낸 인스턴스에 할 일은 없다 — 퍼블리셔가 함께 사라진다
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> replaced) {
                attach(replaced.getNewEntry());
            }
        };
    }

    private static void attach(Retry retry) {
        retry.getEventPublisher()
                // 다시 부르기 직전. 지연이 어디서 났는지 되짚을 때 필요하다
                .onRetry(event -> log.info("[retry] {} {}번째 시도 — {} 뒤에 다시 부릅니다: {}",
                        event.getName(), event.getNumberOfRetryAttempts() + 1,
                        event.getWaitInterval(), FailureReason.of(event.getLastThrowable())))
                // ⚠️ 이 한 줄이 이 클래스의 존재 이유다. 답은 나갔지만 상대는 멀쩡하지 않다
                .onSuccess(event -> log.warn(
                        "[retry] {}가 {}번 만에 성공했습니다 — 답은 나갔지만 이 출처가 나빠지고 "
                                + "있습니다. 마지막 실패 이유: {}",
                        event.getName(), event.getNumberOfRetryAttempts() + 1,
                        FailureReason.of(event.getLastThrowable())));
    }
}
