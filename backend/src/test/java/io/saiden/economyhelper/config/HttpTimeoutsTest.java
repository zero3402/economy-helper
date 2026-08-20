package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties.HttpTimeout;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * <b>호스트로 키를 잡은 대가에 대한 그물.</b>
 *
 * <p>{@code economy-helper.http-timeouts}는 호스트 문자열로 출처를 가리키는데, 오타가 나면
 * <b>오류가 아니라 조용한 기본값</b>이 된다. 같은 함정을 {@code cache-ttl}은
 * {@code CacheConfigTest}로, 리미터는 {@code ResilienceConfigTest}로 막았다 — 여기도 같다.
 */
@SpringBootTest
class HttpTimeoutsTest {

    @Autowired EconomyHelperProperties properties;
    @Autowired ConfigurableEnvironment environment;

    @Test
    @DisplayName("타임아웃 목록의 호스트가 전부 실재하는 base-url이다 — 오타는 조용히 기본값이 된다")
    void everyConfiguredHostIsRealHost() {
        Set<String> known = baseUrlHosts();

        List<String> unknown = properties.httpTimeouts().stream()
                .map(HttpTimeout::host)
                .filter(host -> !known.contains(host))
                .toList();

        assertThat(unknown)
                .as("이 호스트로는 아무 요청도 안 나간다 — 오타이거나 지운 출처의 잔재다. "
                        + "설정에 남아 있으면 '타임아웃을 줬다'고 믿게 된다. 아는 호스트: %s", known)
                .isEmpty();
    }

    @Test
    @DisplayName("성격이 다른 출처는 실제로 다른 값을 든다 — 같으면 전역 하나와 다를 바 없다")
    void actuallyDiffersBySource() {
        // Gemini는 생성이라 늘리고(30초), 시세는 실측 37~142ms라 줄인다(3초).
        // 이 둘이 같아지면 이 설정이 주석과 다를 바 없어진다
        assertThat(timeoutFor("generativelanguage.googleapis.com").read())
                .isGreaterThan(timeoutFor("api.upbit.com").read());
        assertThat(timeoutFor("api.upbit.com").read())
                .as("시세는 캐시 수명(10초)의 3분의 1을 넘겨 기다리지 않는다")
                .isLessThan(java.time.Duration.ofSeconds(4));
    }

    private HttpTimeout timeoutFor(String host) {
        return properties.httpTimeouts().stream()
                .filter(timeout -> host.equals(timeout.host())).findFirst()
                .orElseThrow(() -> new AssertionError(host + "에 타임아웃이 없습니다"));
    }

    /** 설정에 적힌 모든 {@code *base-url}의 호스트. 출처가 실재하는지 보는 유일한 근거다. */
    private Set<String> baseUrlHosts() {
        return environment.getPropertySources().stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .map(EnumerablePropertySource.class::cast)
                .flatMap(source -> java.util.Arrays.stream(source.getPropertyNames()))
                .filter(name -> name.startsWith("economy-helper.") && name.endsWith("base-url"))
                .map(environment::getProperty)
                .filter(value -> value != null && value.startsWith("http"))
                .map(value -> URI.create(value).getHost())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
