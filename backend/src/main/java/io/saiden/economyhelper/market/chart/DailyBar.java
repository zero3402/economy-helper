package io.saiden.economyhelper.market.chart;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일봉 한 칸 — <b>날짜와 종가뿐이다.</b>
 *
 * <p>고가·저가·시가는 <b>받지 않는다.</b> 화면이 그리는 것은 선 하나이고, 안 쓸 값을 받아
 * 오면 응답만 무거워지고 언젠가 화면에 새어 나온다({@code OpenMeteoForecastClient}가 현재
 * 기온을 안 받는 것과 같은 판단이다). 촛대 그림이 필요해지면 그때 늘린다.
 *
 * @param date  그 거래일. 출처가 준 영업일 그대로다 — 빈 날을 우리가 채우지 않는다
 * @param close 종가. <b>{@code 0}이나 음수는 값이 아니다</b>({@link DailySeries}가 걸러낸다)
 */
public record DailyBar(LocalDate date, BigDecimal close) {

    public DailyBar {
        if (date == null) {
            throw new IllegalArgumentException("일봉에 날짜가 없습니다");
        }
        if (close == null) {
            throw new IllegalArgumentException("일봉에 종가가 없습니다: " + date);
        }
    }
}
