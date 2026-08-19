package io.saiden.economyhelper.digest;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * <p><b>이 클래스가 따로 있는 이유는 사고가 났기 때문이다.</b> 두 잡이 같은 기계를 각자
 * 들고 있었고, 새로 붙인 날씨 잡이 접두사를 빠뜨려 <b>6시 날씨가 슬롯을 잡는 바람에 9시
 * 브리핑이 통째로 나가지 않았다</b>({@link SendHistory}가 저장소 하나를 함께 쓰고 두 잡의
 * 슬롯이 둘 다 {@code yyyy-MM-dd}였다). 그래서 <b>접두사를 생성자가 요구한다</b> — 새 잡을
 * 붙이는 사람이 "이건 무엇의 슬롯인가"를 반드시 한 번 결정하게 된다.
 *
 * <p>브리핑만 접두사가 <b>빈 문자열</b>이다. 이미 돌고 있는 키를 그대로 두기 위해서다 —
 * 여기서 이름을 바꾸면 배포 직후의 슬롯이 "안 보낸 것"으로 보여 브리핑이 한 번 더 나간다.
 */
final class DigestSlot {

    private static final Logger log = LoggerFactory.getLogger(DigestSlot.class);

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
        if (claim.claimed()) {
            history.release(claim.id());
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
