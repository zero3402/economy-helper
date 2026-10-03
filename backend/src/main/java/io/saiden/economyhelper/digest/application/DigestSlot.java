package io.saiden.economyhelper.digest.application;

import io.saiden.economyhelper.digest.application.port.out.SendHistory;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "오늘 몫은 이미 보냈는가" — 정기 발송 잡들이 공유하는 슬롯 기계.
 *
 * <p>슬롯 = <b>KST 날짜</b>. 요구사항이 "하루 한 번"이므로 키도 하루 단위여야 한다.
 * <b>시각을 넣지 않는다.</b> 넣으면 09시에 못 보내고 09:10에 보낸 것이 다른 슬롯이 되어
 * 같은 브리핑이 두 번 나간다. 날짜 단위라야 발송 창(09~10시) 안에서 몇 번을 재시도해도
 * 한 번만 나가고, "정확히 09시에 깨어 있어야 한다"는 요구가 사라진다.
 *
 * <p>⚠️ <b>접두사를 생성자가 요구한다.</b> {@link SendHistory}는 잡들이 저장소 하나를 함께
 * 쓰므로, 접두사를 빠뜨리면 6시 날씨가 슬롯을 잡아 9시 브리핑이 통째로 안 나간다(실제로 났다).
 * 브리핑만 빈 문자열이다 — 이유는 {@code DailyDigestJob.SLOT_PREFIX}.
 */
public final class DigestSlot {

    private static final Logger log = LoggerFactory.getLogger(DigestSlot.class);

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final SendHistory history;
    private final Clock clock;
    private final ZoneId zone;
    private final String prefix;
    private final String logTag;

    /**
     * @param prefix 슬롯 이름 앞에 붙일 것. <b>{@code null}을 받지 않는다</b> — 빈 문자열은
     *               "접두사가 없어도 된다"는 판단이지 깜빡한 것이 아니다
     * @param logTag 로그 앞머리({@code digest}·{@code weather}). 한 저장소를 나눠 쓰므로
     *               어느 잡이 남긴 줄인지 구분되어야 한다
     */
    DigestSlot(SendHistory history, Clock clock, ZoneId zone, String prefix, String logTag) {
        this.history = history;
        this.clock = clock;
        this.zone = zone;
        this.prefix = Objects.requireNonNull(prefix, "슬롯 접두사를 정하지 않았습니다");
        this.logTag = logTag;
    }

    /**
     * 오늘 몫의 이름 — <b>선점하지 않는다.</b>
     *
     * <p>보낼 것이 아예 없어 그냥 건너뛸 때 쓴다. 그때 선점까지 해 버리면 설정을 고쳐 넣어도
     * 그날은 영영 안 나간다.
     */
    String id() {
        return prefix + clock.instant().atZone(zone).format(FORMAT);
    }

    /**
     * 오늘 몫을 선점한다.
     *
     * <p>Redis가 죽으면 슬롯을 판단할 수 없다. 예외를 그대로 올리면 스케줄러가 삼켜 아무 일도
     * 없었던 것처럼 보이므로, <b>사유를 값에 담아</b> 밖에서 보이게 한다.
     *
     * @param force 이미 보낸 슬롯이어도 진행한다. 수동 점검용이다
     */
    Claim claim(boolean force) {
        String id = id();

        boolean claimed;
        try {
            claimed = history.claim(id);
        } catch (RuntimeException e) {
            log.error("[{}] 발송 이력 조회 실패 — Redis 연결을 확인하세요: {}", logTag, e.toString());
            return Claim.blocked(id, "발송 이력(Redis) 조회 실패: " + e);
        }
        if (!claimed && !force) {
            // 발송 창 안에서 10분마다 도는 구조라 이 분기가 하루에 열 번 넘게 지나간다.
            // info로 두면 정상 동작이 로그를 덮는다
            log.debug("[{}] {} 슬롯은 이미 발송됐습니다 — 건너뜁니다", logTag, id);
            return Claim.blocked(id, "오늘은 이미 발송했습니다");
        }
        return new Claim(id, claimed, null);
    }

    /**
     * 선점을 되돌린다 — <b>아무것도 못 보냈을 때만</b> 부른다.
     *
     * <p>보낸 적 없는 슬롯을 "보냄"으로 남기면 그날 발송은 복구 후에도 영영 비어 있다.
     * {@code force}로 들어와 남의 선점을 지나쳤을 수 있으므로 <b>내가 잡은 것만</b> 되돌린다.
     */
    void release(Claim claim) {
        if (!claim.claimed()) {
            return;
        }
        try {
            history.release(claim.id());
        } catch (RuntimeException e) {
            // ⚠️ claim()처럼 던지지 않는다. 던지면 execute()가 터져 DigestResult가 버려지고
            //    TriggerableJob.lastResult가 어제 것으로 남는다. 되돌리기 실패는 할 수 있는 것이
            //    없으므로 크게 남기고 넘어간다 — 다음 날 슬롯은 TTL이 지워 준다
            log.error("[{}] {} 슬롯 선점을 되돌리지 못했습니다 — 그날 발송이 복구되지 않습니다: {}",
                    logTag, claim.id(), e.toString());
        }
    }

    /**
     * 선점 시도의 결과.
     *
     * @param claimed 이 호출이 슬롯을 차지했는가. {@code force}로 지나친 경우 거짓이다 —
     *                그때 되돌리면 남이 잡은 것을 푸는 셈이 된다
     * @param blockedReason 진행하면 안 되는 이유. 진행해도 되면 {@code null}
     */
    record Claim(String id, boolean claimed, String blockedReason) {

        static Claim blocked(String id, String reason) {
            return new Claim(id, false, reason);
        }

        boolean proceed() {
            return blockedReason == null;
        }
    }
}
