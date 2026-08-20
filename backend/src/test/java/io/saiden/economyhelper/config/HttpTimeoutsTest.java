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

    /** 어떤 {@code base-url}에도 없지만 반드시 목록에 있어야 하는 호스트. */
    private static final String KIS_LIVE = "openapi.koreainvestment.com";

    @Autowired EconomyHelperProperties properties;
    @Autowired ConfigurableEnvironment environment;

    @Test
    @DisplayName("타임아웃 목록의 호스트가 전부 실재하는 base-url이다 — 오타는 조용히 기본값이 된다")
    void everyConfiguredHostIsRealHost() {
        Set<String> known = baseUrlHosts();

        List<String> unknown = properties.httpTimeouts().stream()
                .map(HttpTimeout::host)
                // 실전 도메인은 아직 어느 base-url에도 없다 — 다음 테스트가 대신 지킨다
                .filter(host -> !KIS_LIVE.equals(host))
                .filter(host -> !known.contains(host))
                .toList();

        assertThat(unknown)
                .as("이 호스트로는 아무 요청도 안 나간다 — 오타이거나 지운 출처의 잔재다. "
                        + "설정에 남아 있으면 '타임아웃을 줬다'고 믿게 된다. 아는 호스트: %s", known)
                .isEmpty();
    }

    @Test
    @DisplayName("KIS 실전 도메인도 목록에 있다 — 계정을 옮기는 날 조용히 15초로 떨어진다")
    void carriesTheLiveKisDomainToo() {
        // 호스트로 키를 잡았으므로 도메인이 바뀌면 매칭이 사라진다. 실전은 앱키와 도메인을
        // 함께 바꾸는 일이라(application.yml의 market.kis 주석) 그날 조용히 전역 기본값으로
        // 떨어지는데, KIS는 거절 하나에 2.2~3.5초를 쓰는 출처다
        assertThat(properties.httpTimeouts()).anySatisfy(timeout ->
                assertThat(timeout.host()).isEqualTo(KIS_LIVE));

        HttpTimeout paper = timeoutFor("openapivts.koreainvestment.com");
        HttpTimeout live = timeoutFor(KIS_LIVE);
        assertThat(live.connect()).as("모의와 실전이 같은 값이어야 한다").isEqualTo(paper.connect());
        assertThat(live.read()).isEqualTo(paper.read());
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
