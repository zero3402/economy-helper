package io.saiden.economyhelper.digest.application.port.out;

/**
 * 이미 발송한 슬롯의 기록 — 잡은 선점과 되돌림만 알고 저장소(Redis)는 모른다.
 *
 * <p>슬롯 이름을 만드는 일은 {@code DigestSlot}이 한다. 여기는 받은 이름을 적고 지울 뿐이다.
 */
public interface SendHistory {

    /**
     * 슬롯을 선점한다. 여러 인스턴스가 동시에 불러도 참을 받는 쪽은 하나뿐이어야 한다.
     *
     * @return 이 호출이 슬롯을 차지했으면 {@code true}, 이미 누가 보냈으면 {@code false}
     */
    boolean claim(String slot);

    /** 선점을 되돌린다 — 발송이 실패했을 때만 부른다. */
    void release(String slot);
}
