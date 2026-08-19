package io.saiden.economyhelper.digest;

import java.util.List;

/**
 * 스케줄을 기다리지 않고 한 번 돌릴 수 있는 정기 발송 잡.
 *
 * <p>담는 것은 <b>마지막 결과를 들고 있는 일</b> 하나다. 두 잡이 이걸 각자 갖고 있었는데,
 * 같은 자리에 같은 필드를 두 벌 두면 한쪽만 고쳐지는 날이 온다.
 *
 * <p>결과를 들고 있는 이유는 <b>"오늘 아침에 왜 안 왔나"를 확인하려고 실제 방송을 한 번 더
 * 쏘지 않기 위해서다.</b> 그건 구독자에게 중복을 보내는 일이다 — 액추에이터 엔드포인트가
 * 읽기({@code GET})와 쓰기({@code POST})를 갈라 둔 이유가 그것이다.
 *
 * <p>{@link #execute}만 잡마다 다르다. <b>합치지 않는다</b> — 브리핑은 통 넷을 겹쳐 모아
 * 순서대로 보내고, 날씨는 지역들을 겹쳐 모아 한 통으로 보낸다. 슬롯을 잡는 시점도 다르다.
 */
public abstract class TriggerableJob {

    private volatile DigestResult lastResult =
            new DigestResult(false, null, List.of(), List.of(), "아직 실행된 적이 없습니다");

    public DigestResult lastResult() {
        return lastResult;
    }

    /**
     * @param force 이미 보낸 슬롯이어도 다시 보낸다. 수동 점검용이다
     */
    public DigestResult run(boolean force) {
        DigestResult result = execute(force);
        lastResult = result;
        return result;
    }

    /**
     * <b>{@code boolean}이 아니라 {@link DigestResult}를 돌려준다.</b> 둘 다 부분 실패를
     * 허용하는 잡이라 수동 점검이 <b>무엇이 나갔고 무엇이 왜 실패했는지</b>를 알아야 한다 —
     * 배포처에서는 로그를 보기 어렵고 스모크 테스트는 응답만 보고 판단할 수 있어야 한다.
     */
    protected abstract DigestResult execute(boolean force);
}
