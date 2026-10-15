package io.saiden.economyhelper.shared.support;

/**
 * <b>출처 장애로 답을 못 만들었다</b> — 「없다」가 아니다.
 *
 * <p>종목·코인·뉴스가 각자 {@code Unavailable}을 던지고 웹훅이 셋을 따로 잡아 똑같이 「잠시 후 다시」로
 * 답했다. 잡는 자리가 하나이고 하는 일이 같으니 공통 조상을 둔다 — 컨텍스트의 타입은 그대로 남아
 * (메시지 모양이 다르다) 부르는 쪽이 바뀌지 않고, 웹훅의 {@code catch}만 하나로 접힌다.
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
