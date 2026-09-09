package io.saiden.economyhelper.support;

import java.util.function.Supplier;

/**
 * 한 호출의 결과 — <b>세 상태다: 값을 받았다 · 실패했다 · 안 물었다.</b>
 *
 * <p><b>둘로는 못 든다.</b> 「안 물었다」를 실패로 세면 물을 이유가 없는 조회가 늘 던지고,
 * 성공으로 세면 <b>실패가 가려져</b> 브레이커가 그것을 못 본다. 그리고 값이 {@code null}인
 * 성공은 「없다」이고 그건 <b>값</b>이다 — 「모른다」가 아니다.
 *
 * <p>전망 클라이언트 둘이 이 레코드를 <b>글자까지 똑같이</b> 각자 들고 있었다
 * ({@code FmpUsOutlookClient}·{@code KisDomesticOutlookClient}). 컴포넌트 셋도 팩터리도
 * 술어도 같았고, {@link Concurrently#both}의 javadoc은 이미 <b>한 개념인 것처럼</b>
 * 이것을 가리키고 있었다. {@link Permit}을 뽑은 이유와 같은 자리다 — 같은 네 줄이 두 파일에
 * 있으면 한쪽에서 고친 결함이 다른 쪽에 그대로 남는다.
 *
 * <p><b>정책은 여기 없다.</b> 「하나라도 성공했으면 담고 아니면 던진다」(KIS)와 「전부
 * 영구 차단이면 빈 값을 담는다」(FMP)는 <b>진짜로 다른 판단</b>이라 각 클라이언트에 남는다.
 * 이 레코드가 드는 것은 세 상태와 그것을 읽는 법뿐이다.
 *
 * @param value   받은 것. 없으면 {@code null}이고 그건 <b>값</b>이다
 * @param failure 실패했으면 <b>그 예외 객체</b>. 물었고 이것이 {@code null}이면 성공이다.
 *                <p>⚠️ <b>메시지가 아니라 예외를 드는 것이 중요하다.</b> 두 가지가 걸린다 —
 *                {@code e.getMessage()}는 <b>{@code null}일 수 있어</b>(메시지 없이 던진 예외)
 *                실패가 성공으로 세어졌고, 무엇보다 <b>타입이 사라지면 브레이커의
 *                {@code ignoreExceptions}가 안 맞는다</b>: {@code kisStock}이
 *                {@code KisThrottle$Congested}를 그 목록에 두는 이유가 「우리 문이 우리를 거절한
 *                것을 상대 장애로 세지 않는다」인데, 맨 {@code IllegalStateException}으로 바꿔
 *                던지면 <b>그 보호에서 조용히 빠진다.</b> (적대적 리뷰가 잡은 자리다.)
 *                <p>⚠️ <b>그러나 메시지를 밖으로 흘리지는 말 것</b> — FMP 예외에는 apikey가
 *                박힌 URL이 들어 있다. 상태 코드를 읽는 데만 쓰고, 나가는 줄은
 *                {@link FailureReason}이 만든다
 * @param asked   실제로 물었나. <b>있을 수 없는 값</b>(ETF의 목표가)이나 <b>한도가 없어</b>
 *                못 부른 다리는 거짓이다
 */
public record Fetched<T>(T value, RuntimeException failure, boolean asked) {

    /**
     * 물어보고 결과를 값으로 잡는다 — <b>던지지 않는다.</b>
     *
     * <p>「살아 있는 것은 살린다」가 필요한 자리에서 {@link Concurrently#both}에 넘기는 모양이
     * 이것이다. 그쪽은 하나가 던지면 나머지 결과까지 버리므로, 삼키는 일을 각 다리가 해야 한다.
     */
    public static <T> Fetched<T> attempt(Supplier<T> call) {
        try {
            return new Fetched<>(call.get(), null, true);
        } catch (RuntimeException e) {
            return new Fetched<>(null, e, true);
        }
    }

    /** 물을 이유가 없거나 물 수 없었던 것 — <b>성공도 실패도 아니다.</b> */
    public static <T> Fetched<T> skipped() {
        return new Fetched<>(null, null, false);
    }

    /**
     * 물었고 실패하지 않았나.
     *
     * <p><b>「안 물었다」는 성공이 아니다</b> — 그것이 세 상태를 든 이유다.
     */
    public boolean succeeded() {
        return asked && failure == null;
    }
}
