package io.saiden.economyhelper.digest.application.port.out;

import java.util.List;

/**
 * 정기 발송의 창구 — 잡은 값만 넘기고 적는 법·그리는 법·보내는 법(같은 방 간격 포함)은 창구가 맡는다.
 * 구현은 창구 컨텍스트(텔레그램)의 어댑터에 있다 — 다른 컨텍스트가 포트를 <b>구현</b>하는 것은
 * 경계 규칙이 허용하고, <b>호출</b>하는 것은 막는다({@code ArchitectureTest}).
 */
public interface DigestNotifier {

    /**
     * 한 통(글 뒤에 차트들)을 순서대로 보낸다. {@link RuntimeException}은 던지지 않고 값으로 돌려준다.
     *
     * <p>⚠️ <b>글이 나갈 때마다 {@code onDelivered}를 곧바로 부른다.</b> 결과를 돌려줄 때 한꺼번에
     * 알리면, 글이 나간 뒤 차트에서 {@link Error}가 새는 순간 「나갔다」가 사라져 잡이 슬롯을 되돌리고
     * 다음 틱에 같은 글이 또 나간다.
     *
     * @param charts      글 다음에 한 장씩 나갈 차트들. 못 그린 것은 그 한 장만 빠진다
     * @param onDelivered 글이 한 통 나갈 때마다 불린다
     */
    Delivery send(DigestMessage message, List<DigestMessage.Chart> charts, Runnable onDelivered);

    default Delivery send(DigestMessage message) {
        return send(message, List.of(), () -> { });
    }

    /**
     * 발송 한 번의 결과.
     *
     * <p>둘이 함께 참일 수 있다 — 뉴스 셋째 통에서 거절되면 앞의 두 통은 이미 나갔다. 그 통이
     * 「나갔다」로도 「실패했다」로도 남아야 슬롯을 되돌릴지 판단이 선다.
     *
     * @param delivered 글이 한 통이라도 나갔는가
     * @param failure   도중에 멈춘 이유. 끝까지 보냈으면 {@code null}
     */
    record Delivery(boolean delivered, RuntimeException failure) {
    }
}
