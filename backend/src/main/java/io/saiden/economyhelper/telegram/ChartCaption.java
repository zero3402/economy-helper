package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.SEOUL;
import static io.saiden.economyhelper.telegram.MessageLayout.change;
import static io.saiden.economyhelper.telegram.MessageLayout.money;

import io.saiden.economyhelper.market.chart.DailyBar;
import io.saiden.economyhelper.market.chart.DailySeries;
import java.util.List;

/**
 * 차트 사진의 설명 — <b>그림에 없는 낱말이 전부 여기 있다.</b>
 *
 * <p>{@code ChartRenderer}가 글자를 안 그리는 것은 배포 컨테이너에 폰트가 없으면 두부가 되기
 * 때문이다. 그 대가로 <b>사진 홀로는 무엇의 그림인지 알 수 없으므로</b> caption이 그것을
 * 말해야 한다. 그리고 caption이 텍스트라 <b>골든이 이 낱말들을 계속 덮는다</b> —
 * 그림은 골든이 못 보지만 설명은 본다.
 *
 * <p><b>본문과 겹치는 것을 최소로 적는다.</b> 값과 등락률은 바로 앞 통이 이미 말했으므로,
 * 여기서는 <b>그림을 읽는 데 필요한 것</b>만 적는다 — 무엇인지, 어느 창인지, 그 창에서
 * 얼마에서 얼마로 갔는지. 창의 시작값은 본문에 없는 정보이고 그것이 이 그림의 뜻이다.
 *
 * <p>⚠️ <b>「지금」을 계산하지 않는다.</b> 날짜는 일봉이 든 것을 그대로 쓴다 — 시계를 보면
 * 같은 입력에 다른 글자가 나오고 그 순간 골든이 못 지키는 자리가 된다
 * ({@code CryptoFormatter}가 같은 이유로 「오늘이면 시각만」을 거부한다).
 */
public final class ChartCaption {

    private ChartCaption() {
    }

    /**
     * @param subject 무엇의 그림인가. 이미 이스케이프된 값이 아니라 <b>날 값</b>을 받는다
     * @param unit    값의 단위({@code KRW}). 지수처럼 단위가 없으면 {@code null}
     * @param bars    그린 일봉. {@link DailySeries#drawable}이 참인 것만 온다
     */
    public static String of(String subject, String unit, List<DailyBar> bars) {
        DailyBar first = bars.get(0);
        DailyBar last = bars.get(bars.size() - 1);

        StringBuilder caption = new StringBuilder("<b>").append(Html.escape(subject))
                .append("</b> 최근 ").append(bars.size()).append("거래일");
        caption.append("\n").append(money(first.close()))
                .append(" → ").append(money(last.close()));
        if (unit != null) {
            caption.append(" ").append(unit);
        }
        // 창 전체의 움직임 — 본문의 등락률은 전 고시 대비라 뜻이 다르다
        String moved = change(DailySeries.changePercent(bars));
        if (!moved.isEmpty()) {
            caption.append("\n").append(moved);
        }
        // 그림의 양 끝이 언제인지 — 「최근 14거래일」만으로는 어느 날까지인지 알 수 없다
        caption.append("\n").append(DATE.format(first.date()))
                .append(" ~ ").append(DATE.format(last.date()));
        return caption.toString();
    }
}
