package io.saiden.economyhelper.shared.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 일봉 여러 칸 — <b>차트가 그리는 것.</b> I/O를 모르는 순수 클래스다
 * ({@code HalfDays}·{@code InvestOpinions}와 같은 자리라 스프링 없이 테스트한다).
 *
 * <p><b>여기가 값을 걸러내는 유일한 자리다.</b> 출처마다 쓰레기의 모양이 다른데
 * ({@code null}·빈 문자열·{@code 0.00}) 그것을 클라이언트 넷이 각자 처리하면 하나가 빠진다.
 *
 * <p>⚠️ <b>{@code 0}을 그리지 않는다.</b> KIS는 없는 심볼에 에러가 아니라 {@code 0.00}을
 * 주므로(실측: {@code DJI}·{@code DJIA}), 그대로 그리면 <b>차트가 0으로 절벽을 그린다</b> —
 * 「0을 값으로 내보내지 않는다」는 저장소 규칙이 그림에서도 같다. 못 구한 날은
 * <b>빈 칸으로 남긴다</b>(0으로 채우지 않는다) — 선이 이어지지 않는 것이 사실이다.
 */
public final class DailySeries {

    /**
     * 차트에 그리는 칸 수 — <b>거래일 열나흘.</b>
     *
     * <p>지난 이주에가 둥글게 담긴다. 주식은 장이 열린 날만 세므로 달력 2주에 가깝고,
     * 코인은 주말도 있으니 열나흘 그대로다. 더 길게 잡으면 텔레그램 화면에서 세로폭이
     * 줄어 한 칸이 엉긴다.
     */
    public static final int WINDOW = 14;

    private DailySeries() {
    }

    /**
     * 쓸 만한 칸만 골라 <b>날짜 순으로</b> 정렬한다.
     *
     * <p>정렬을 여기서 하는 이유는 출처마다 순서가 다르기 때문이다 — KIS는 최근 것이 먼저
     * 오고 Frankfurter는 날짜를 키로 준다. 그림은 왼쪽이 과거여야 하므로 한 곳에서 맞춘다.
     *
     * @param bars  걸러낼 칸들. {@code null}이 섞여 있어도 된다
     * @param limit 남길 개수. <b>최근 것부터</b> 센다 — 조회일이 오른쪽 끝이다
     */
    public static List<DailyBar> recent(List<DailyBar> bars, int limit) {
        if (bars == null || bars.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<DailyBar> usable = new ArrayList<>();
        for (DailyBar bar : bars) {
            // 0과 음수는 값이 아니다 — 없는 심볼의 0.00이 여기서 걸린다
            if (bar != null && bar.close().signum() > 0) {
                usable.add(bar);
            }
        }
        usable.sort(Comparator.comparing(DailyBar::date));
        // 같은 날이 두 번 오면 뒤엣것만 남긴다 — 되짚기 루프가 겹쳐 부를 수 있다
        List<DailyBar> deduped = new ArrayList<>();
        for (DailyBar bar : usable) {
            if (!deduped.isEmpty() && deduped.getLast().date().equals(bar.date())) {
                deduped.set(deduped.size() - 1, bar);
            } else {
                deduped.add(bar);
            }
        }
        return deduped.size() <= limit
                ? List.copyOf(deduped)
                : List.copyOf(deduped.subList(deduped.size() - limit, deduped.size()));
    }

    /** 그림을 그릴 만한가 — 점이 하나면 선이 없다. */
    public static boolean drawable(List<DailyBar> bars) {
        return bars != null && bars.size() >= 2;
    }

    /**
     * 첫 칸 대비 마지막 칸의 변화율(%). 창 전체의 움직임을 caption이 한마디로 적는 데 쓴다.
     *
     * <p>⚠️ <b>본문이 쓰는 그 계산({@link PercentChange})이어야 한다.</b> 식을 따로 두면 반올림이
     * 경계에서 {@code 0.01%p} 갈려 한 통 안에서 본문과 캡션의 등락률이 다른 숫자가 된다.
     *
     * <p>{@code first}가 음수인 경우만 여기서 막는다 — {@code 0}과 {@code null}은
     * {@link PercentChange#between}이 이미 막는다(분모가 0이면 빈 값).
     * 시세에 음수는 없지만, 있다면 그건 값이 아니라 고장이다.
     */
    public static PercentChange changePercent(List<DailyBar> bars) {
        if (!drawable(bars)) {
            return null;
        }
        BigDecimal first = bars.getFirst().close();
        if (first.signum() < 0) {
            return null;
        }
        return PercentChange.between(bars.getLast().close(), first).orElse(null);
    }
}
