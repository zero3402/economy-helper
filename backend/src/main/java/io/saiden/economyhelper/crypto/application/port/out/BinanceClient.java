package io.saiden.economyhelper.crypto.application.port.out;

import io.saiden.economyhelper.crypto.domain.BinancePrice;
import java.time.Instant;
import java.util.List;

/**
 * 바이낸스 칸 — USDT 호가 현재가.
 *
 * <p>업비트({@link UpbitClient})와 따로 둔다 — 두 칸은 폴백이 아니라 나란히 서는 값이다.
 * 실패의 <b>뜻</b>이 화면에 닿아야 하므로(미상장·밴·조회 실패) 좁은 예외 둘을 이 경계에 둔다.
 * 브레이커 무시 목록도 그 타입을 적는다.
 */
public interface BinanceClient {

    /**
     * 여러 심볼의 현재가를 <b>한 번에</b>.
     *
     * <p>없는 심볼이 하나라도 섞이면 <b>요청 전체가 400</b>이다(실측: {@code USDTUSDT} →
     * {@code {"code":-1121,"msg":"Invalid symbol."}}). 그래서 호출 전에 걸러야 한다 —
     * {@link io.saiden.economyhelper.crypto.domain.BinanceSymbol}이 그 일을 한다.
     *
     * @throws UnknownSymbol 그 심볼이 바이낸스에 없다(400)
     * @throws Banned        밴 중이라 부르지 않았다(418·429 뒤)
     */
    List<BinancePrice> prices(List<String> symbols);

    /**
     * <b>바이낸스에 그 심볼이 없다</b>(400 {@code -1121}).
     *
     * <p>좁은 타입으로 두는 이유는 <b>브레이커</b>다 — 무시 목록에 이 타입만 적는다. 없는 심볼의
     * 400이 브레이커를 열면 멀쩡한 다른 코인까지 막히지만, {@code HttpClientErrorException}을 통째로
     * 무시하면 <b>418·429도 빠져</b> 밴 동안 계속 찔러 밴을 연장한다.
     */
    class UnknownSymbol extends RuntimeException {

        public UnknownSymbol(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * <b>밴 중이라 부르지 않았다</b> — 상대 장애가 아니라 우리가 스스로 닫은 문이다.
     *
     * <p>그래서 브레이커의 무시 목록에 든다({@code RequestNotPermitted}와 같은 자리).
     * 실패로 세면 밴이 풀린 뒤에도 브레이커가 열린 채 남아 <b>밴보다 오래 가는 정지</b>가 된다.
     *
     * <p>{@link UnknownSymbol}과도 뜻이 다르다. 그쪽은 「그 코인이 없다」(영영)이고
     * 이쪽은 「지금은 못 본다」(언제 풀리는지까지 안다) — 화면이 그 둘을 갈라 적는다.
     */
    class Banned extends RuntimeException {

        private final transient Instant until;

        public Banned(Instant until) {
            super("바이낸스가 우리 IP를 밴했습니다. " + until + "까지 부르지 않습니다");
            this.until = until;
        }

        /** 언제 풀리는지 — 화면이 이 값을 적는다. 「잠시 후」보다 시각이 낫다. */
        public Instant until() {
            return until;
        }
    }
}
