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

    /**
     * 종류가 다른 둘을 동시에 — {@code FmpUsOutlookClient}가 목표가와 실적발표일을 겹칠 때 쓴다.
     *
     * <p>{@link #map}은 한 타입의 목록이라 모양이 다른 둘을 담으려면 {@code Object}로 뭉쳐야 했다.
     * 둘째가 던지면 첫째 결과도 함께 버려진다 — 「살아 있는 것은 살린다」가 필요한 자리는
     * 각자 안에서 실패를 삼키고 값으로 돌려준다({@code Fetched}가 그 모양이다).
     */
    public static <A, B> Pair<A, B> both(Supplier<A> first, Supplier<B> second) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<A> a = executor.submit(first::get);
            Future<B> b = executor.submit(second::get);
            return new Pair<>(join(a), join(b));
        }
    }

    public record Pair<A, B>(A first, B second) {}

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
            // ⚠️ Error는 감싸지 않고 그대로 올린다. RuntimeException으로 바꿔 던지면
            //    폴백 루프들(FxService·StockService·WeatherService)이 RuntimeException을
            //    삼키도록 돼 있어 OutOfMemoryError가 "이 출처 실패, 다음으로"가 된다 —
            //    이중화로 감쌀 문제가 아니고, 감싸면 진짜 원인이 로그에서 사라진다
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
