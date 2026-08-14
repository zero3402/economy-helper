package io.saiden.economyhelper.digest;

import java.util.List;

/**
 * 발송 시도 한 번의 결과.
 *
 * <p>수동 트리거가 "무엇이 나갔고 무엇이 왜 실패했는지"를 알아야 해서 존재한다 —
 * 스케줄러라면 로그로 충분하지만, 배포처에서는 로그를 보기 어렵고 스모크 테스트는
 * 응답만 보고 판단할 수 있어야 한다.
 *
 * <p><b>부분 성공이 정상 상태다.</b> 네 통(환율·주식·코인·뉴스) 중 하나가 실패해도 나머지는
 * 나가므로, 성패를 통 단위로 담는다. 전부 실패했을 때만 {@code sent}가 거짓이다.
 *
 * @param delivered 실제로 나간 통의 이름
 * @param failed    실패했거나 보낼 내용이 없던 통 — <b>이름과 사유를 함께</b> 담는다.
 *                  이름만 남기던 때는 "환율 실패"까지만 알 수 있어 설정이 틀린 것인지
 *                  외부 API가 죽은 것인지 구분하려면 결국 로그를 봐야 했다
 */
public record DigestResult(boolean sent, String slot,
                           List<String> delivered, List<Failure> failed, String reason) {

    /**
     * @param section 통 이름 ({@code 환율}·{@code 증시}·{@code 코인}·{@code 뉴스})
     * @param reason  실패 사유. 텔레그램이 거절했으면 그쪽 {@code description}이 그대로 온다
     */
    public record Failure(String section, String reason) {}

    static DigestResult completed(String slot, List<String> delivered, List<Failure> failed) {
        return new DigestResult(true, slot, List.copyOf(delivered), List.copyOf(failed), "발송 완료");
    }

    static DigestResult skipped(String slot, String reason) {
        return new DigestResult(false, slot, List.of(), List.of(), reason);
    }

    /**
     * 넷 다 실패했다.
     *
     * <p>{@link #skipped}와 달리 <b>실패 목록을 남긴다</b> — 아무것도 못 보낸 때가
     * 무엇이 죽었는지 제일 알고 싶은 때다. 수동 트리거는 응답만 보고 판단해야 한다.
     */
    static DigestResult allFailed(String slot, List<Failure> failed) {
        return new DigestResult(false, slot, List.of(), List.copyOf(failed), "네 종류 모두 실패했습니다");
    }
}
