package io.saiden.economyhelper.digest;

/**
 * 발송 시도 한 번의 결과.
 *
 * <p>수동 트리거가 "왜 안 보냈는지"를 알아야 해서 존재한다 — 스케줄러라면 로그로 충분하지만,
 * 8단계 스모크 테스트에서는 응답만 보고 판단할 수 있어야 한다.
 */
public record DigestResult(boolean sent, String slot, int itemCount, String reason) {

    static DigestResult completed(String slot, int itemCount) {
        return new DigestResult(true, slot, itemCount, "발송 완료");
    }

    static DigestResult skipped(String slot, String reason) {
        return new DigestResult(false, slot, 0, reason);
    }
}
