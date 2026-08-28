package io.saiden.economyhelper.market.kis;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KIS 호출 사이의 <b>간격</b>을 지킨다 — 환율·국내·미국이 이 문 하나를 함께 쓴다.
 *
 * <p><b>왜 resilience4j 리미터가 아닌가.</b> {@code RateLimiter}는 <b>고정 윈도</b>다. 1초에
 * 1건으로 잡아도 "호출 사이 1초"를 보장하지 않는다 — 첫 호출이 윈도 끝에서 퍼밋을 쓰면
 * 두 번째 퍼밋은 <b>다음 윈도가 시작되는 순간</b>, 즉 수십 ms 뒤에 나온다. 실측으로 미국 종목
 * 조회(NAS → NYS)의 두 호출이 <b>120ms 간격</b>으로 나갔다.
 *
 * <p><b>그리고 KIS는 그 간격을 본다.</b> 실측(2026-08-19, 모의 계정):
 *
 * <table border="1">
 *   <caption>같은 요청, 커넥션과 간격만 바꿔 잰 것</caption>
 *   <tr><th>커넥션</th><th>간격</th><th>결과</th></tr>
 *   <tr><td>매번 새로</td><td>0.05초</td><td>둘 다 200</td></tr>
 *   <tr><td><b>재사용</b></td><td>0.2초</td><td>두 번째가 <b>초당 거래건수 초과</b>(HTTP 500)</td></tr>
 *   <tr><td>재사용</td><td>1초</td><td>셋 다 200</td></tr>
 * </table>
 *
 * <p>앱은 커넥션 풀을 쓰므로 언제나 아래쪽 경우다. 그래서 <b>1초를 띄우는 것</b>이 고칠 점이고,
 * 그건 윈도가 아니라 간격의 문제다. 이 계정이 못 하는 것을 커넥션을 매번 새로 열어 피할 수도
 * 있지만, KIS가 문서로 약속한 규칙은 "초당 1건"이므로 그쪽을 지킨다 — 커넥션마다 센다는 것은
 * 우리가 관찰한 구현 사정일 뿐이다.
 *
 * <p><b>기다림에는 한도가 있다</b>({@code max-wait}). 없애면 큐가 길어질 때 요청이 무한히
 * 늘어진다 — 예전 리미터의 {@code timeoutDuration}이 하던 몫이다. 넘치면 던지고, 그러면
 * 상위 서비스가 다음 출처로 넘어간다.
 *
 * <p>브리핑은 KIS를 9번 연달아 부르므로 마지막 호출이 ~8초를 기다린다. 이건 늦어진 것이
 * 아니라 <b>원래 지불해야 했던 값</b>이다.
 */
@Component
public class KisThrottle {

    private final long intervalNanos;
    private final long capNanos;

    /**
     * <b>공평한 락</b>이다 — 먼저 온 호출이 먼저 나간다. 공평하지 않으면 브리핑의 연속 호출이
     * 사용자의 {@code /stock} 하나를 계속 밀어낼 수 있다.
     *
     * <p>{@code synchronized}가 아닌 이유는 가상 스레드다 — 그 안에서 자면 캐리어 스레드가
     * 핀 된다. {@code ReentrantLock}은 풀린다.
     */
    private final ReentrantLock gate = new ReentrantLock(true);

    /** 다음 호출이 허용되는 시점({@code System.nanoTime()} 기준). 벽시계는 뒤로 갈 수 있다. */
    private long nextAllowed = System.nanoTime();

    /**
     * @param interval 호출 사이 최소 간격. 모의 계정이 초당 1건이라 기본 1초다 —
     *                 실전 계정은 초당 20건이므로 낮춰 잡을 수 있다({@code base-url}과 함께 바꾼다)
     * @param maxWait  이 시간을 넘겨 기다려야 하면 기다리지 않고 던진다
     */
    public KisThrottle(@Value("${economy-helper.market.kis.min-interval:1s}") Duration interval,
                       @Value("${economy-helper.market.kis.max-wait:20s}") Duration maxWait) {
        this.intervalNanos = Math.max(0, interval.toNanos());
        this.capNanos = Math.max(0, maxWait.toNanos());
    }

    /**
     * 앞 호출과 최소 간격이 벌어질 때까지 기다린다. <b>실제 HTTP 호출 직전에</b> 부른다 —
     * 메서드 단위로 부르면 그 안에서 두 번 나가는 경로가 다시 새어 나간다.
     *
     * @throws Congested 대기 한도를 넘겼을 때. 던져야 이중화가 다음 출처로 넘어간다
     */
    void pace() {
        try {
            // 줄 서는 것까지 한도에 넣는다 — 락을 잡은 뒤에 재면 앞에 몇이 서 있는지 못 본다
            if (!gate.tryLock(capNanos, TimeUnit.NANOSECONDS)) {
                throw new Congested(
                        "KIS 호출이 밀려 대기 한도를 넘겼습니다: " + Duration.ofNanos(capNanos));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Congested("KIS 호출을 기다리다 중단됐습니다");
        }
        try {
            long waitNanos = nextAllowed - System.nanoTime();
            if (waitNanos > 0) {
                Thread.sleep(Duration.ofNanos(waitNanos));
            }
            // 잔 뒤의 시각으로 다시 잰다 — 요청 처리 시간이 간격을 이미 벌어 놨을 수 있다
            nextAllowed = System.nanoTime() + intervalNanos;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Congested("KIS 호출 간격을 기다리다 중단됐습니다");
        } finally {
            gate.unlock();
        }
    }

    /**
     * <b>이 문이 거절한 것</b> — 상대 장애가 아니라 <b>우리가 스스로 건 스로틀</b>이다.
     *
     * <p>⚠️ <b>타입을 따로 두는 이유는 브레이커다.</b> {@code configs.default}가
     * {@code RequestNotPermitted}를 무시 목록에 넣어 둔 이유가 「리미터가 우리 호출을 거절한
     * 것은 상대 장애가 아니다」인데, KIS는 그 리미터를 <b>걷어내고 이 문으로 바꿨다</b> —
     * 그 순간 거절이 맨 {@code IllegalStateException}이 되어 <b>그 보호에서 조용히 빠졌다.</b>
     * 브리핑이 KIS를 9번 연달아 부르는 동안 사용자의 {@code /stock} 하나가 줄에서 밀려
     * {@code max-wait}를 넘기면, 그것이 {@code kisStock}·{@code kisFx} 브레이커에
     * <b>상대 장애로 기록된다</b> — 설정 주석이 경고한 「우리 트래픽이 몰릴 때 브레이커가 열려
     * 멀쩡한 상대를 끊는다」가 그대로 일어난다.
     *
     * <p>중단({@code InterruptedException})도 여기 넣는다. 종료 신호이지 KIS의 문제가 아니다.
     */
    public static final class Congested extends IllegalStateException {

        public Congested(String message) {
            super(message);
        }
    }
}
