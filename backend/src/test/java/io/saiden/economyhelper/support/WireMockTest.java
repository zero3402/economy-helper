package io.saiden.economyhelper.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

/**
 * <b>WireMock 서버는 클래스당 하나다</b> — 그 규칙을 상속으로 지킨다.
 *
 * <p>서버는 {@code @BeforeAll}에서 한 번 뜨고, 테스트 사이에는 {@code resetAll()}로 <b>상태만</b> 되돌린다
 * (스텁·요청기록·시나리오가 함께 비므로 {@code verify(n, …)} 같은 횟수 단언도 그대로 성립한다). 테스트마다
 * 띄우고 내리면 포트 재활용 창이 열리고, 그 창에서 요청이 앞 테스트의 서버에 닿는 것을 실측했다
 * ({@code ARCHITECTURE.md} §7). 스물일곱 클래스가 같은 열한 줄을 각자 들고 있었다 — 한 파일만 옛 모양이면
 * 그 클래스가 다시 창을 연다.
 *
 * <p>{@code server}는 상속된 정적 필드다 — 부르는 쪽 코드가 한 글자도 안 바뀐다. 클래스는 순차로 도므로
 * (병렬은 포크 단위다) 앞 클래스의 {@code @AfterAll}이 내린 뒤 다음 클래스의 {@code @BeforeAll}이 띄운다.
 *
 * <p>h2c를 꺼야 하는 클래스(JDK HttpClient가 HTTP/2를 먼저 시도하는데 WireMock의 평문 h2 구현과 POST 본문에서
 * 충돌한다)는 {@link WireMockOptions}를 단다. 실제 Bot API 서버에서는 나지 않는 문제다.
 *
 * <p>{@code WireMockLifecycleTest}가 「스텁하는 파일은 전부 이것을 상속하거나 {@code @Test} 안에서 제 서버를
 * 만든다」를 훑는다.
 */
public abstract class WireMockTest {

    protected static WireMockServer server;

    @BeforeAll
    static void startWireMock(TestInfo info) {
        WireMockConfiguration options = WireMockConfiguration.options().dynamicPort();
        WireMockOptions custom = info.getTestClass()
                .map(type -> type.getAnnotation(WireMockOptions.class)).orElse(null);
        if (custom != null && custom.http2PlainDisabled()) {
            options = options.http2PlainDisabled(true);
        }
        server = new WireMockServer(options);
        server.start();
    }

    @AfterAll
    static void stopWireMock() {
        server.stop();
    }

    /** 스텁·요청기록·시나리오를 함께 비운다 — 서버는 그대로 두고 상태만 되돌린다. */
    @BeforeEach
    void resetWireMock() {
        server.resetAll();
    }

    /** 서버 옵션 — 지금은 h2c 끄기 하나다. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface WireMockOptions {

        boolean http2PlainDisabled() default false;
    }
}
