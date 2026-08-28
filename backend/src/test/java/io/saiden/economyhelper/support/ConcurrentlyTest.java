package io.saiden.economyhelper.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConcurrentlyTest {

    @Test
    @DisplayName("결과 순서를 입력 순서로 되돌린다 — 발송 순서가 흔들리면 매일 다른 모양이 된다")
    void keepsInputOrder() {
        List<Integer> result = Concurrently.map(List.of(1, 2, 3, 4, 5), n -> n * 10);

        assertThat(result).containsExactly(10, 20, 30, 40, 50);
    }

    @Test
    @DisplayName("정말 동시에 돈다 — 서로를 기다리게 해 두고 전부 풀리는지로 확인한다")
    void actuallyRunsConcurrently() {
        // 다섯이 순차로 돌면 첫 번째가 나머지 넷을 기다리다 영영 끝나지 않는다.
        // 시간을 재지 않고 이 구조로 보는 이유는 시간 단언이 CI에서 흔들리기 때문이다
        CountDownLatch everyoneStarted = new CountDownLatch(5);

        List<Boolean> result = Concurrently.map(List.of(1, 2, 3, 4, 5), n -> {
            everyoneStarted.countDown();
            try {
                return everyoneStarted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });

        assertThat(result).as("다섯이 모두 동시에 시작해야 참이 된다").containsOnly(true);
    }

    @Test
    @DisplayName("종류가 다른 둘도 겹친다 — 서로를 기다리게 해 두고 둘 다 풀리는지로 본다")
    void runsTwoDifferentlyTypedTasksConcurrently() {
        CountDownLatch bothStarted = new CountDownLatch(2);

        Concurrently.Pair<Boolean, String> pair = Concurrently.both(
                () -> {
                    bothStarted.countDown();
                    return await(bothStarted);
                },
                () -> {
                    bothStarted.countDown();
                    return await(bothStarted) ? "둘째" : "혼자";
                });

        assertThat(pair.first()).isTrue();
        assertThat(pair.second()).isEqualTo("둘째");
    }

    @Test
    @DisplayName("둘 중 하나가 던지면 그대로 올린다 — 살릴 것은 각자 안에서 값으로 삼켜야 한다")
    void bothPropagatesFailure() {
        assertThatThrownBy(() -> Concurrently.both(() -> 1, () -> {
            throw new IllegalStateException("둘째 죽음");
        })).isInstanceOf(IllegalStateException.class).hasMessage("둘째 죽음");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    @DisplayName("실패를 감추지 않는다 — '하나 죽어도 나머지는' 판단은 호출자마다 다르다")
    void propagatesFailure() {
        assertThatThrownBy(() -> Concurrently.map(List.of(1, 2, 3), n -> {
            if (n == 2) {
                throw new IllegalStateException("피드 죽음");
            }
            return n;
        })).isInstanceOf(IllegalStateException.class).hasMessage("피드 죽음");
    }

    @Test
    @DisplayName("하나뿐이면 스레드를 만들지 않는다 — 겹칠 것이 없는데 띄우면 지연만 붙는다")
    void runsSingleItemInline() {
        Thread caller = Thread.currentThread();

        List<Thread> ran = Concurrently.map(List.of(1), n -> Thread.currentThread());

        assertThat(ran).containsExactly(caller);
        assertThat(Concurrently.map(List.of(), n -> n)).isEmpty();
    }
}
