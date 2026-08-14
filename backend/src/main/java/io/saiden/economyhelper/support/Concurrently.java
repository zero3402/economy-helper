package io.saiden.economyhelper.support;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 외부 호출 여럿을 <b>동시에</b> 돌리고 결과를 순서대로 모은다.
 *
 * <p><b>왜 필요한가.</b> 아침 브리핑 한 번이 피드 5 + HN 5 + Gemini 10회를 <b>줄줄이</b> 불렀고,
 * Gemini 한 번이 2~5초라 LLM만으로 20~50초였다. 이 호출들은 계산이 아니라 <b>기다림</b>이라
 * 겹쳐 놓기만 하면 합이 아니라 최댓값이 된다.
 *
 * <p><b>가상 스레드를 쓴다.</b> 풀 크기를 정할 필요가 없다 — 크게 잡으면 상대 API의 한도를
 * 때리고 작게 잡으면 병렬화가 무의미해지는데, 여기서 동시에 도는 것은 기껏해야 매체 수(한 자리)다.
 * 스레드가 싸므로 "필요한 만큼"이 곧 정답이 된다. 대신 상대 API 보호는 스레드 수가 아니라
 * {@code resilience4j} 레이트리미터가 맡는다 — 그게 원래 그 일을 하는 자리다.
 *
 * <p><b>실패를 여기서 감추지 않는다.</b> 한 건이 던지면 그대로 올린다. "하나 죽어도 나머지는
 * 나간다"는 판단은 호출자마다 다르고({@code FeedFetcher}는 빈 목록, 브리핑은 통 단위 실패),
 * 이미 각자 자리에 구현돼 있다.
 */
public final class Concurrently {

    private Concurrently() {
    }

    /**
     * @return {@code items} 순서 그대로의 결과. 입력이 1개 이하면 스레드를 만들지 않는다 —
     *         겹칠 것이 없는데 스레드를 띄우면 지연만 붙는다
     */
    public static <T, R> List<R> map(List<T> items, Function<T, R> work) {
        if (items.size() <= 1) {
            return items.stream().map(work).toList();
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<R>> futures = items.stream()
                    .map(item -> executor.submit(() -> work.apply(item)))
                    .toList();
            return futures.stream().map(Concurrently::join).toList();
        }
    }

    /** 서로 다른 일 여럿을 동시에. 종류가 달라 {@link #map}으로 묶이지 않는 자리에 쓴다. */
    public static <R> List<R> all(List<Supplier<R>> tasks) {
        return map(tasks, Supplier::get);
    }

    /**
     * <p>인터럽트를 삼키지 않는다 — 삼키면 종료 신호가 무시돼 배포 때 컨테이너가
     * 강제 종료될 때까지 남는다.
     */
    private static <R> R join(Future<R> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행이 중단됐습니다", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }
}
