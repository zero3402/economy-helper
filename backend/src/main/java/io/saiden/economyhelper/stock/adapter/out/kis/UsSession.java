package io.saiden.economyhelper.stock.adapter.out.kis;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 뉴욕 정규장 달력 — 월~금 09:30 ≤ t < 16:00(미국 동부, 서머타임은 시간대가 안다).
 *
 * <p><b>왜 필요한가.</b> 미국 종목 시세({@code price-detail})에는 날짜 필드가 없다(실측 2026-08-18 —
 * {@code last}·{@code base}뿐). 국내·지수는 일봉 날짜로 「오늘 값인가」를 가르는데({@code KisHeaders.closedBefore})
 * 종목은 그 단서가 없어, 주말·개장 전에 현재가 자리에 와 있는 전 거래일 종가에 읽은 시각을 찍고 실시간이라 했다.
 * 그래서 종목은 <b>장중인가만 이 달력으로</b> 가르고, 종가일은 일봉이 확인한 날짜를 쓴다({@code KisStockApi.usStockBasis}).
 *
 * <p>⚠️ <b>알려진 한계 — 미국 휴장일을 모른다.</b> 평일 휴장일(독립기념일 등)의 장중 시각에는 전 거래일 종가가
 * 실시간으로, 그날 16시 뒤에는 그 휴장일 날짜의 (종가)로 나간다. 휴장일 표를 두지 않는 것은 「모른다고 낡았다고
 * 단정하지 않는다」({@code KisStockApi.basisOf}가 날짜를 모를 때 실시간으로 두는 것)와 같은 쪽으로 기운 선택이다 —
 * 표가 틀리거나 묵으면 열린 장을 종가로 꾸민다. 장전·장후(04:00~09:30·16:00~20:00)는 정규장 밖이라 종가로 본다 —
 * 그 사이 {@code last}가 시간외 체결로 움직이는지는 확인하지 않았다(실측 없음).
 */
final class UsSession {

    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(16, 0);

    private UsSession() {
    }

    /** 지금이 정규장 안인가. */
    static boolean isOpen(Instant now) {
        ZonedDateTime local = now.atZone(NEW_YORK);
        LocalTime time = local.toLocalTime();
        return isWeekday(local.getDayOfWeek()) && !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }

    private static boolean isWeekday(DayOfWeek day) {
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
