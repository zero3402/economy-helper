package io.saiden.economyhelper.telegram.presentation;

import static io.saiden.economyhelper.telegram.presentation.MessageLayout.appendChangeLine;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.date;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.dateTime;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.head;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.krwAmount;
import static io.saiden.economyhelper.telegram.presentation.MessageLayout.retryLater;

import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.telegram.adapter.in.web.Command;

/**
 * 환율 통.
 *
 * <p><b>기준이 값의 성격을 밝힌다</b> — 하루 중에도 움직이는 출처(한국투자증권)는 시각까지,
 * 하루 한 번 고시하는 출처는 날짜와 {@code (고시)}로 끝맺는다. 폴백이 일어나면 값의 성격이
 * 내려앉으므로 숨기면 고장이 아니라 거짓말이 된다({@code docs/design.md} 4.7).
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
        StringBuilder lines = new StringBuilder(head(Command.FX))
                .append("1 USD = ").append(krwAmount(rate.rate().value()));
        appendChangeLine(lines, rate.changePercent());
        return lines.append("\n\n")
                .append(Html.escape(rate.source().displayName())).append("\n\n")
                .append(basisOf(rate))
                .toString();
    }

    public static String unavailable() {
        return retryLater(Command.FX, "환율을 가져오지 못했습니다.");
    }

    /**
     * 움직이는 값은 시각까지, 아니면 날짜와 성격 — 고시 출처는 {@code (고시)}, 실시간 출처가 쉬는 날 준
     * 마지막 영업일 값은 {@code (종가)}다(증시 통과 같은 꼬리표).
     */
    private static String basisOf(FxRate rate) {
        if (rate.live()) {
            return dateTime(rate.asOf());
        }
        return date(rate.asOf()) + (rate.source().intraday() ? " (종가)" : " (고시)");
    }
}
