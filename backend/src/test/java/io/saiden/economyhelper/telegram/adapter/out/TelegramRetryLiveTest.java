package io.saiden.economyhelper.telegram.adapter.out;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.testsupport.WireMockTest;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 텔레그램 재시도가 <b>실제 소켓 실패</b>에 어떻게 반응하는지 — {@code application.yml}의 열거와
 * {@link TelegramClient}의 예외 분류가 맞물려야 뜻이 산다.
 *
 * <p><b>왜 스프링 컨텍스트인가.</b> {@code telegram} 재시도 설정은 레지스트리로만 읽힌다. 프록시 대신
 * {@link Retry#decorateRunnable}로 <b>같은 인스턴스</b>를 거는 이유는, 연결 거부를 재현하려면 아무도
 * 듣지 않는 주소로 보내야 하는데 빈의 주소는 기동 때 하나로 박히기 때문이다. 애너테이션이 빈에 실제로
 * 걸렸는지는 {@code DigestIntegrationTest}가 본다.
 *
 * <p>시도 횟수는 서버 기록이 아니라 <b>세서</b> 본다 — 연결 거부는 서버에 기록이 남지 않는다.
 */
@SpringBootTest
@WireMockTest.WireMockOptions(http2PlainDisabled = true)
class TelegramRetryLiveTest extends WireMockTest {

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        // 기동 자가진단(TelegramSelfCheck)이 실제 텔레그램으로 나가지 않게 — 빈 자체는 여기서 쓰지 않는다
        registry.add("economy-helper.telegram.base-url", () -> server.baseUrl());
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired RetryRegistry retries;
    @Autowired CircuitBreakerRegistry breakers;

    @Test
    @DisplayName("연결이 거부되면 세 번 시도한다 — 요청이 나가기 전이라 다시 보내도 두 번 가지 않는다")
    void retriesWhenTheConnectionIsRefused() {
        TelegramClient client = client(RestClient.builder(), "http://localhost:1");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> Retry.decorateRunnable(retries.retry("telegram"), () -> {
            attempts.incrementAndGet();
            client.send("12345", null, null, "안녕하세요", false);
        }).run()).isInstanceOf(TelegramClient.TelegramUnavailable.class);

        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("200을 받은 뒤 본문이 깨지면 한 번만 시도한다 — 이미 전달됐을 수 있어 다시 보내면 구독자 전원에게 두 번 간다")
    void neverRetriesWhenTheOutcomeIsUnknown() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
        TelegramClient client = client(RestClient.builder(), server.baseUrl());
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> Retry.decorateRunnable(retries.retry("telegram"), () -> {
            attempts.incrementAndGet();
            client.send("12345", null, null, "안녕하세요", false);
        }).run()).isInstanceOf(TelegramClient.TelegramUnknownOutcome.class);

        assertThat(attempts).hasValue(1);
        server.verify(1, postRequestedFor(urlPathEqualTo("/bottest-token/sendMessage")));
    }

    @Test
    @DisplayName("응답을 기다리다 타임아웃이 나도 한 번만 시도한다 — 요청은 이미 나갔다")
    void neverRetriesAReadTimeout() {
        // 지연(2초)이 타임아웃(300ms)보다 넉넉히 길면 된다 — 시간을 재는 단언은 없다
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}").withFixedDelay(2_000)));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(300));
        TelegramClient client = client(RestClient.builder().requestFactory(factory), server.baseUrl());
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> Retry.decorateRunnable(retries.retry("telegram"), () -> {
            attempts.incrementAndGet();
            client.send("12345", null, null, "안녕하세요", false);
        }).run()).isInstanceOf(TelegramClient.TelegramUnknownOutcome.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("닿았는지 모르는 실패도 브레이커는 센다 — 상대가 아픈 신호인 것은 같다")
    void unknownOutcomeCountsAsABreakerFailure() {
        CircuitBreaker breaker = breakers.circuitBreaker("telegram");
        breaker.reset();

        breaker.onError(0, TimeUnit.MILLISECONDS, new TelegramClient.TelegramUnknownOutcome(
                "텔레그램 sendMessage 결과를 모릅니다", new IOException("closed")));

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("원인에 IOException이 있어도 재시도 판정은 거짓이다 — 본문 읽기 판정(BodyReadFailure)이 이걸 집으면 안 된다")
    void retryPredicateIgnoresUnknownOutcomeEvenWithAnIoCause() {
        var predicate = retries.retry("telegram").getRetryConfig().getExceptionPredicate();

        assertThat(predicate.test(new TelegramClient.TelegramUnknownOutcome("결과를 모릅니다",
                new RestClientException("본문 읽기 실패", new IOException("closed"))))).isFalse();
        assertThat(predicate.test(new TelegramClient.TelegramUnavailable("닿지 못함")))
                .as("닿지 못한 것은 그대로 재시도한다")
                .isTrue();
    }

    private static TelegramClient client(RestClient.Builder builder, String baseUrl) {
        return new TelegramClient(builder, TestProperties.builder()
                .telegramBaseUrl(baseUrl).botToken("test-token")
                .chatId("default-chat").noticeTopicId("3")
                .telegramMinInterval(Duration.ZERO)
                .build());
    }
}
