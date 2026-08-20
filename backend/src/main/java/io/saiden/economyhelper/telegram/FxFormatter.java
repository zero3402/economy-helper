package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.DATE_TIME;
import static io.saiden.economyhelper.telegram.MessageLayout.SEOUL;
import static io.saiden.economyhelper.telegram.MessageLayout.change;
import static io.saiden.economyhelper.telegram.MessageLayout.head;
import static io.saiden.economyhelper.telegram.MessageLayout.money;

import io.saiden.economyhelper.market.FxRate;

/**
 * 환율 통.
 *
 * <p><b>기준이 값의 성격을 밝힌다</b> — 하루 중에도 움직이는 출처(한국투자증권)는 시각까지,
 * 하루 한 번 고시하는 출처는 날짜와 {@code (고시)}로 끝맺는다. 폴백이 일어나면 값의 성격이
 * 내려앉으므로 숨기면 고장이 아니라 거짓말이 된다({@code ARCHITECTURE.md} 4-6).
 */
public final class FxFormatter {

    private FxFormatter() {
    }

    /**
     * 원/달러 환율.
     *
     * <p><b>{@code 1 USD = 1,412.17 KRW}로 쓴다.</b> 숫자만 두면 어느 쪽이 기준인지 드러나지 않는다.
     *
     * <p><b>출처와 기준일을 반드시 밝힌다.</b> 1순위가 죽어 수출입은행으로 폴백하면
     * 주말엔 며칠 전 값이 나가는데, 그걸 숨기면 고장이 아니라 거짓말이 된다.
     */
    public static String format(FxRate rate) {
        String change = change(rate.changePercent());
        return head(Command.FX)
                + "1 USD = " + money(rate.rate()) + " KRW"
                + (change.isEmpty() ? "" : "\n" + change) + "\n\n"
                + Html.escape(rate.source().displayName()) + "\n\n"
                + basisOf(rate);
    }

    public static String unavailable() {
        return head(Command.FX) + "환율을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    private static String basisOf(FxRate rate) {
        return rate.source().intraday()
                ? DATE_TIME.format(rate.asOf().atZone(SEOUL))
                : DATE.format(rate.asOf().atZone(SEOUL)) + " (고시)";
    }
}
