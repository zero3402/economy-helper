package io.saiden.economyhelper.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이중화의 기계 부분 — <b>환율·시세·날씨 셋이 전부 이 위에 서 있는데 테스트가 없었다.</b>
 *
 * <p>여기서 지키는 것이 셋이다: <b>적힌 순서</b>대로 세운다(주입 순서를 믿지 않는다),
 * <b>적힌 것만</b> 쓴다(그게 load-bearing이다 — FMP가 국내 순서에서 그렇게 빠진다),
 * 그리고 <b>처음 성공에서 멈춘다</b>(전부 부르는 것은 이중화가 아니라 선택이고 남의 한도를 태운다).
 */
class FailoverTest {

    private enum Source { FIRST, SECOND, THIRD, UNLISTED }

    private record Client(Source source) {}

    private static final List<Source> ORDER = List.of(Source.FIRST, Source.SECOND, Source.THIRD);

    @Test
    @DisplayName("주입 순서를 믿지 않는다 — 적힌 순서로 세운다")
    void ordersByTheDeclaredList() {
        List<Client> injected = List.of(
                new Client(Source.THIRD), new Client(Source.FIRST), new Client(Source.SECOND));

        assertThat(Failover.order(injected, ORDER, Client::source))
                .extracting(Client::source)
                .containsExactly(Source.FIRST, Source.SECOND, Source.THIRD);
    }

    @Test
    @DisplayName("적힌 것만 쓴다 — 목록에 없는 클라이언트는 떨어진다")
    void keepsOnlyWhatTheListNames() {
        List<Client> injected = List.of(new Client(Source.UNLISTED), new Client(Source.FIRST));

        assertThat(Failover.order(injected, ORDER, Client::source))
                .extracting(Client::source)
                .containsExactly(Source.FIRST);
    }

    @Test
    @DisplayName("떨어진 것을 이름으로 알려 준다 — 조용히 사라지면 이중화가 없는 걸 아무도 모른다")
    void namesTheClientsNoListTookAlong() {
        List<Client> injected = List.of(
                new Client(Source.FIRST), new Client(Source.UNLISTED));

        assertThat(Failover.unordered(injected, Client::source, ORDER))
                .extracting(Client::source)
                .containsExactly(Source.UNLISTED);
    }

    @Test
    @DisplayName("목록이 여럿이면 함께 본다 — 하나만 보면 일부러 뺀 것이 거짓 경보가 된다")
    void looksAtEveryListBeforeCallingSomethingUnordered() {
        // StockService가 이 모양이다 — FMP는 국내 순서에서 일부러 빠지고 미국 순서에만 있다
        List<Client> injected = List.of(new Client(Source.FIRST), new Client(Source.THIRD));

        assertThat(Failover.unordered(injected, Client::source,
                List.of(Source.FIRST), List.of(Source.THIRD)))
                .as("어느 목록에도 없는 것만 구성 실수다")
                .isEmpty();
    }

    @Test
    @DisplayName("처음 성공에서 멈춘다 — 1순위가 살아 있으면 2순위는 호출조차 안 된다")
    void stopsAtTheFirstSuccess() {
        List<String> called = new ArrayList<>();

        assertThat(Failover.first(List.of("일", "이"), client -> {
            called.add(client);
            return client + "값";
        }, (client, e) -> called.add("실패:" + client))).contains("일값");

        assertThat(called).containsExactly("일");
    }

    @Test
    @DisplayName("실패는 다음으로 넘기고 사유를 콜백에 흘린다 — 로그는 도메인이 남긴다")
    void movesOnAndHandsTheFailureToTheCaller() {
        List<String> failures = new ArrayList<>();

        assertThat(Failover.first(List.of("일", "이"), client -> {
            if ("일".equals(client)) {
                throw new IllegalStateException("죽었다");
            }
            return client + "값";
        }, (client, e) -> failures.add(client + ":" + e.getMessage()))).contains("이값");

        assertThat(failures).containsExactly("일:죽었다");
    }

    @Test
    @DisplayName("전부 실패하거나 시도할 것이 없으면 빈 값이다")
    void givesNothingWhenEveryoneFails() {
        List<String> failures = new ArrayList<>();

        assertThat(Failover.first(List.of("일", "이"), client -> {
            throw new IllegalStateException("죽었다");
        }, (client, e) -> failures.add(client))).isEmpty();

        assertThat(failures).as("다음이 없어도 콜백은 불린다").containsExactly("일", "이");
        assertThat(Failover.first(List.of(), client -> "값", (c, e) -> { })).isEmpty();
    }

    @Test
    @DisplayName("Error는 삼키지 않는다 — 이중화로 감쌀 문제가 아니다")
    void neverSwallowsAnError() {
        assertThatThrownBy(() -> Failover.first(List.of("일"), client -> {
            throw new StackOverflowError("진짜 원인");
        }, (client, e) -> { })).isInstanceOf(StackOverflowError.class);
    }
}
