package io.saiden.economyhelper.config;

import java.io.IOException;
import java.util.function.Predicate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 재시도 판정 — <b>본문을 읽다 끊긴 것</b>도 일시적 실패로 본다({@code application.yml}의
 * {@code retry.configs.default.retryExceptionPredicate}).
 *
 * <p><b>왜 열거({@code retryExceptions})로 안 되나.</b> Spring은 I/O 실패를 두 모양으로 감싼다.
 * 헤더를 받기 전(연결·리셋·헤더 타임아웃)은 {@code ResourceAccessException}이지만, 헤더를 받은 뒤
 * <b>본문을 읽다</b> 끊기거나 멈추면 {@code DefaultRestClient.readWithMessageConverters}가
 * <b>맨 {@link RestClientException}</b>으로 감싼다(spring-web 7.0.8 소스로 확인). 맨 타입을 열거에
 * 넣으면 그 하위인 4xx({@code HttpClientErrorException})까지 재시도되므로, 원인 사슬을 보고 고른다.
 *
 * <p>열거와 이것은 <b>「또는」</b>으로 묶이고 {@code ignoreExceptions}는 그 위에서 뺀다(resilience4j
 * {@code RetryConfig.createExceptionPredicate}). {@code baseConfig: default}로 물려받으므로 제
 * {@code retryExceptions}를 적은 인스턴스(telegram)에서도 그대로 산다.
 */
public class BodyReadFailure implements Predicate<Throwable> {

    /** 원인을 몇 겹까지 볼지 — 순환하는 사슬에서 멈추기 위해서다({@code FailureReason}과 같은 이유). */
    private static final int MAX_DEPTH = 5;

    @Override
    public boolean test(Throwable e) {
        // 상태 코드를 받은 것(4xx·5xx)은 열거가 가른다 — 여기서 넓히지 않는다
        if (!(e instanceof RestClientException) || e instanceof RestClientResponseException) {
            return false;
        }
        Throwable cause = e.getCause();
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
