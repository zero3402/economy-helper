package io.saiden.economyhelper.shared.support;

/**
 * <b>출처 장애로 답을 못 만들었다</b> — 「없다」가 아니다.
 *
 * <p>종목·코인·뉴스가 각자 던지는 {@code Unavailable}의 공통 조상이다 — 웹훅이 한 {@code catch}로 잡아
 * 똑같이 「잠시 후 다시」로 답한다. 컨텍스트의 타입은 그대로 남는다(메시지 모양이 다르다).
 *
 * <p>{@code shared}는 컨텍스트를 모른다 — 여기에는 「장애」라는 뜻만 있다.
 */
public abstract class Outage extends RuntimeException {

    protected Outage(String message) {
        super(message);
    }

    protected Outage(String message, Throwable cause) {
        super(message, cause);
    }
}
