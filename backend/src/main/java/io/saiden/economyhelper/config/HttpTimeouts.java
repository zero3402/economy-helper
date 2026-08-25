package io.saiden.economyhelper.config;

import io.saiden.economyhelper.config.EconomyHelperProperties.HttpTimeout;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * 출처마다 다른 타임아웃 — <b>호스트로 가른다.</b>
 *
 * <p>전역 하나로는 스무 곳을 못 맞춘다. 실측 p50이 업비트 37ms에서 FMP 1.7초까지 벌어지고,
 * Gemini는 조회가 아니라 <b>생성</b>이라 성격이 다르다. 15초 하나는 빠른 시세에는 300배
 * 느슨하고 Gemini에는 짧을 수 있다.
 *
 * <p><b>왜 생성자마다 넘기지 않는가.</b> 그러면 클라이언트 스무 개의 생성자와 테스트의
 * {@code RestClient.builder()} 호출 예순 곳(서른 파일)이 함께 바뀐다. 여기 방식은
 * 자동설정이 만든 팩터리를 라우터로 바꿔 끼우는 것뿐이라 <b>생성자도 테스트도 안 건드린다</b> —
 * 단위 테스트는 자기 빌더를 {@code new}로 만들어 이 커스터마이저를 아예 타지 않으므로
 * 지금 동작이 그대로다.
 *
 * <p><b>덤으로 커넥션 풀이 줄어든다.</b> {@code RestClient.Builder} 빈은 prototype이고
 * {@code RestClientBuilderConfigurer}가 주입마다 팩터리를 <b>새로</b> 만든다 — 지금 이 앱에
 * {@code java.net.http.HttpClient}가 스물세 개, 풀이 스물세 개 있다. 라우터는
 * (connect, read) 쌍마다 하나만 기억하므로 <b>설정에 적힌 서로 다른 쌍의 수 + 기본 하나</b>가
 * 된다 — 지금은 호스트 열다섯이 쌍 넷을 나눠 쓰므로 <b>다섯</b>이다.
 *
 * <p>⚠️ 이 숫자를 손으로 적지 말 것. 한동안 「다섯 + 기본 하나 = 여섯」이었는데 그 사이
 * 쌍 하나가 합쳐져 실제로는 넷이었다 — 설정을 고치면 따라 틀리는 종류의 문장이다.
 *
 * <p>KIS 셋이 그래서 풀을 공유한다 — 토큰 발급도 같은 앱키의 호출이므로
 * {@code KisTokenStore.request()}가 간격 문을 함께 탄다({@code KisThrottle} 참고).
 *
 * <p><b>기본값을 잃지 않는다.</b> 자동설정이 만든 {@link HttpClientSettings}를 받아
 * {@code withTimeouts}로 두 값만 갈아 낀다 — 그래야 {@code spring.http.clients.ssl}·
 * {@code redirects}처럼 나중에 붙는 설정이 조용히 무력해지지 않는다.
 */
@Configuration
public class HttpTimeouts {

    private static final Logger log = LoggerFactory.getLogger(HttpTimeouts.class);

    private final ClientHttpRequestFactoryBuilder<?> factories;
    private final HttpClientSettings defaults;
    private final Map<String, HttpTimeout> byHost;

    /** (connect, read) 쌍마다 팩터리 하나. 호스트마다 만들면 풀이 열셋이다. */
    private final Map<String, ClientHttpRequestFactory> pools = new ConcurrentHashMap<>();

    public HttpTimeouts(ClientHttpRequestFactoryBuilder<?> factories, HttpClientSettings defaults,
                        EconomyHelperProperties properties) {
        this.factories = factories;
        this.defaults = defaults;
        List<HttpTimeout> configured = properties.httpTimeouts() == null
                ? List.of() : properties.httpTimeouts();
        this.byHost = configured.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        HttpTimeout::host, timeout -> timeout));
    }

    /**
     * ⚠️ {@code RestClientBuilderConfigurer}가 팩터리를 세운 <b>뒤에</b> 커스터마이저를
     * 적용하므로 우리 것이 이긴다. 순서가 뒤집히면 전역 기본값이 남으니, 조용히 그렇게 되는
     * 것을 막으려고 {@code HttpTimeoutsTest}가 호스트마다 팩터리가 실제로 다른지 본다.
     */
    @Bean
    RestClientCustomizer perHostTimeouts() {
        return builder -> builder.requestFactory(new HostRouting());
    }

    private ClientHttpRequestFactory factoryFor(String host) {
        HttpTimeout timeout = host == null ? null : byHost.get(host);
        if (timeout == null) {
            return pools.computeIfAbsent("(기본)", key -> factories.build(defaults));
        }
        return pools.computeIfAbsent(timeout.connect() + "|" + timeout.read(), key -> {
            log.info("[http] 타임아웃 — connect {} / read {} (첫 호스트 {})",
                    timeout.connect(), timeout.read(), host);
            return factories.build(withTimeouts(timeout.connect(), timeout.read()));
        });
    }

    private HttpClientSettings withTimeouts(Duration connect, Duration read) {
        return defaults.withTimeouts(connect, read);
    }

    /** 요청 시점에 손에 있는 유일한 키가 URI다 — 거기서 호스트를 꺼내 고른다. */
    private final class HostRouting implements ClientHttpRequestFactory {

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
            // getHost()는 포트를 뗀다 — openapivts.koreainvestment.com:29443이 호스트만 남는다.
            // 그래서 설정에도 포트를 적지 않는다
            return factoryFor(uri.getHost()).createRequest(uri, method);
        }
    }
}
