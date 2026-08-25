package io.saiden.economyhelper.support;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 이중화의 <b>기계 부분</b> — 순서대로 정렬하고, 순서대로 시도한다.
 *
 * <p>환율·시세·날씨 셋이 같은 모양을 각자 들고 있었다. 같은 판단이 세 곳에 있으면
 * 하나만 고쳐지는 날이 온다 — 이 저장소가 이미 그 계열로 여러 번 물렸다
 * ({@code Price}가 뽑혀 나온 이유도 같다).
 *
 * <p><b>정책은 여기 없다.</b> 순서 목록은 각 서비스가 들고(그게 그 도메인의 계약이다),
 * 로그도 호출부가 남긴다 — {@code [stock]} 태그가 {@code /crypto} 실패에 붙었던 사고가
 * {@code FxService.orNull}의 javadoc에 적혀 있다. 여기서 로그를 통일하면 그 사고를 되풀이한다.
 *
 * <p>{@code support}에 두는 이유는 위치다. {@code WeatherClient}는 {@code market.weather}에,
 * {@code FxRateClient}·{@code StockClient}는 {@code market}에 있어서 {@code market}에 두면
 * {@code public}이 되어야 한다. {@link Concurrently}가 이미 이런 성격의 자리다 —
 * 도메인 지식이 없는 순수 기계.
 */
public final class Failover {

    private Failover() {
    }

    /**
     * <b>선언한 순서로 정렬한다.</b> 주입 순서를 믿지 않는다 — 클래스 이름을 바꾸다 순서가
     * 뒤집히면 1순위와 2순위가 조용히 맞바뀐다.
     *
     * <p><b>{@code wanted}에 없는 클라이언트는 떨어진다.</b> 그게 load-bearing이다 —
     * {@code StockService.DOMESTIC_ORDER}가 FMP를 그렇게 뺀다(FMP 무료 티어는 한국 종목을
     * 못 준다). 필터가 아니라 "적힌 것만 쓴다"로 읽어야 한다.
     *
     * <p>⚠️ <b>비교가 {@code ==}에서 {@code equals}로 바뀐다.</b> 뽑아내기 전에는 세 서비스가
     * 전부 {@code client.source() == source}였다. 제네릭에서는 타입 변수에 {@code ==}를 쓸 수
     * 없어 {@code equals}가 되는데, 지금 쓰이는 것이 전부 enum이라 {@code Enum.equals}는
     * 동일성 비교 그대로다 — 뜻이 바뀌지 않는다. 나중에 enum이 아닌 것을 키로 쓰게 되면
     * 그때는 뜻이 달라지므로 여기 적어 둔다.
     *
     * @param sourceOf 클라이언트에서 출처를 꺼내는 법. SPI마다 메서드 이름이 같지만
     *                 공통 상위 타입이 없어 함수로 받는다
     */
    public static <C, S> List<C> order(List<C> clients, List<S> wanted, Function<C, S> sourceOf) {
        return wanted.stream()
                .flatMap(source -> clients.stream().filter(client -> source.equals(sourceOf.apply(client))))
                .toList();
    }

    /**
     * <b>어느 목록도 데려가지 않은 클라이언트</b> — 등록해 놓고 순서에 안 적은 것들이다.
     *
     * <p>{@link #order}가 목록에 없는 것을 떨어뜨리는 것은 <b>의도한 동작</b>이다(위 참고).
     * 문제는 그 떨어짐이 <b>소리가 없다는 것</b>이다: 새 출처를 {@code @Component}로 등록하고
     * 순서에 적는 것을 잊으면 컴파일도 테스트도 통과한 채 <b>영영 안 불린다.</b> 증상이
     * "이중화가 있는 줄 알았는데 없다"라서 장애가 나기 전에는 아무도 모른다.
     *
     * <p><b>목록을 여러 개 받는 이유가 여기 있다.</b> 한 목록만 보면 거짓 경보가 난다 —
     * {@code StockService}는 국내와 미국 순서를 따로 들고 FMP를 <b>국내에서만</b> 일부러
     * 뺀다(무료 티어가 한국 종목을 못 준다). 그래서 "어느 목록에도 없는 것"만 문제다.
     *
     * <p>여기서 로그를 남기지 않는 것은 {@link #order}와 같은 이유다 — 태그와 문장은
     * 도메인마다 다르고, 그걸 통일했다가 {@code [stock]}이 {@code /crypto} 실패에 붙은
     * 사고가 있었다. <b>사실만 돌려주고 말은 호출부가 한다.</b>
     *
     * @return 어느 {@code wanted}에도 출처가 없는 클라이언트들. 전부 제자리면 빈 목록
     */
    @SafeVarargs
    public static <C, S> List<C> unordered(List<C> clients, Function<C, S> sourceOf,
                                           List<S>... wanted) {
        List<S> covered = java.util.Arrays.stream(wanted).flatMap(List::stream).toList();
        return clients.stream()
                .filter(client -> !covered.contains(sourceOf.apply(client)))
                .toList();
    }

    /**
     * <b>순서대로 시도하고 처음 성공한 것을 쓴다.</b>
     *
     * <p>성공하면 즉시 돌아가므로 <b>1순위가 살아 있는 한 2순위는 호출조차 되지 않는다.</b>
     * 매번 전부 불러 가장 신선한 것을 고르는 방식은 이중화가 아니라 <i>선택</i>이고, 요청마다
     * 2순위의 한도(수출입은행 하루 1,000회, FMP 하루 250회)를 태운다.
     *
     * <p><b>{@code RuntimeException}만 삼킨다.</b> {@code Error}는 그대로 올라간다 — 그건
     * 이중화로 감쌀 문제가 아니고, 감싸면 진짜 원인이 "이 출처 실패, 다음으로"에 묻힌다
     * ({@link Concurrently}가 같은 이유로 {@code Error}를 되던진다).
     *
     * <p><b>로그는 호출부가 남긴다</b>({@code onFailure}). 도메인마다 태그와 문장이 달라야
     * 하기 때문이다 — {@code [stock]} 태그가 {@code /crypto} 실패에 붙었던 사고가
     * {@code FxService.orNull}의 javadoc에 적혀 있다.
     *
     * @param onFailure 한 출처가 실패했을 때. 다음 출처가 있든 없든 불린다
     * @return 처음 성공한 값. 전부 실패했거나 시도할 것이 없으면 {@link Optional#empty()}
     */
    public static <C, R> Optional<R> first(List<C> clients, Function<C, R> call,
                                           BiConsumer<C, RuntimeException> onFailure) {
        for (C client : clients) {
            try {
                return Optional.of(call.apply(client));
            } catch (RuntimeException e) {
                onFailure.accept(client, e);
            }
        }
        return Optional.empty();
    }
}
