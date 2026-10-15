package io.saiden.economyhelper.digest.domain;

import java.util.List;

/**
 * 발송 시도 한 번의 결과.
 *
 * <p>왜 값으로 돌려주는지는 {@code TriggerableJob.execute}. <b>부분 성공이 정상 상태다</b> —
 * 성패를 통 단위로 담고, 전부 실패했을 때만 {@code sent}가 거짓이다.
 *
 * @param delivered 실제로 나간 통의 이름
 * @param failed    실패했거나 보낼 내용이 없던 통 — <b>이름과 사유를 함께</b> 담는다
 */
public record DigestResult(boolean sent, String slot,
                           List<String> delivered, List<Failure> failed, String reason) {

    /**
     * @param section 통 이름 ({@code 환율}·{@code 증시}·{@code 코인}·{@code 뉴스})
     * @param reason  실패 사유. 텔레그램이 거절했으면 그쪽 {@code description}이 그대로 온다
     */
    public record Failure(String section, String reason) {

        /** 예외의 {@code getMessage()}가 비는 경우가 있다 — 그때는 타입이라도 남긴다. */
        public static Failure of(String section, RuntimeException e) {
            return new Failure(section, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public static DigestResult completed(String slot, List<String> delivered, List<Failure> failed) {
        return new DigestResult(true, slot, List.copyOf(delivered), List.copyOf(failed), "발송 완료");
    }

    public static DigestResult skipped(String slot, String reason) {
        return new DigestResult(false, slot, List.of(), List.of(), reason);
    }

    /** {@link #deferred}의 사유 — 술어가 이 글자로 가르므로 로그에 덧붙일 것은 로그 쪽에 적는다. */
    private static final String DEFERRED = "일부가 빠져 발송을 미뤘습니다 — 다음 틱이 다시 모읍니다";

    /**
     * 일부가 빠져 <b>일부러 안 보냈다</b> — 슬롯은 되돌렸고 다음 틱이 다시 모은다(날씨 알람).
     *
     * <p>{@link #allFailed}처럼 실패 목록을 남긴다. 성분을 늘리지 않아 액추에이터 응답 모양은 그대로다.
     */
    public static DigestResult deferred(String slot, List<Failure> failed) {
        return new DigestResult(false, slot, List.of(), List.copyOf(failed), DEFERRED);
    }

    /** 계획된 미룸인가 — 스케줄 경로가 ERROR로 남기지 않을 유일한 「안 보냄」이다. */
    public boolean deferred() {
        return DEFERRED.equals(reason);
    }

    /**
     * 나간 것이 확인된 통은 없는데 <b>닿았는지 모르는</b> 통이 있다 — 슬롯은 잡은 채 둔다.
     * 되돌리면 다음 틱이 같은 통을 다시 보내 구독자 전원에게 두 번 갈 수 있다. 사람이 결과를 보고 판단한다.
     */
    public static DigestResult uncertain(String slot, List<Failure> failed) {
        return new DigestResult(false, slot, List.of(), List.copyOf(failed),
                "전달됐는지 모르는 통이 있어 슬롯을 잡은 채 둡니다 — 되돌리면 두 번 갑니다");
    }

    /**
     * 하나도 못 보냈다.
     *
     * <p>{@link #skipped}와 달리 <b>실패 목록을 남긴다</b> — 아무것도 못 보낸 때가
     * 무엇이 죽었는지 제일 알고 싶은 때다.
     */
    public static DigestResult allFailed(String slot, List<Failure> failed) {
        return new DigestResult(false, slot, List.of(), List.copyOf(failed),
                "보낼 수 있는 것이 하나도 없습니다");
    }
}
