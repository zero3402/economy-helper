package io.saiden.economyhelper.telegram;

import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.chart.ChartImage;
import io.saiden.economyhelper.market.chart.ChartRenderer;
import io.saiden.economyhelper.market.chart.DailyBar;
import io.saiden.economyhelper.market.chart.DailySeries;
import io.saiden.economyhelper.support.FailureReason;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 일봉 하나를 사진 한 장으로 — <b>검색({@code TelegramWebhookController})과 브리핑({@code DailyDigestJob})이
 * 나눠 쓴다.</b>
 *
 * <p>같은 가드·같은 catch·<b>같은 로그 문장</b>이 두 곳에 있었다("KIS가 모르는 심볼이면 0.00만 와서 전부
 * 걸러집니다"까지 글자 그대로). 브리핑 쪽 주석이 「검색 경로와 같은 말을 남긴다」고 스스로 적어 두고
 * 있었는데, 그 말이 곧 한 곳에 있어야 한다는 뜻이었다.
 *
 * <p><b>못 그리면 빈 값이고 답은 그대로 나간다.</b> 차트는 보충이지 답이 아니다 — 일봉 조회가 실패하거나
 * 점이 하나뿐이면 사진만 빠진다({@code WeatherService}가 강수 시각을 다루는 방식과 같은 자리다).
 *
 * <p>⚠️ <b>「현재값과 일일값을 섞지 않는다」는 규칙에 걸리지 않는다.</b> 그 규칙이 막으려는 것은
 * <b>모양이 같은 숫자 둘</b>이 서로 다른 축에 있어 모순처럼 읽히는 것이다(「지금 21°C인데 최고가 29°C」).
 * 차트는 숫자가 아니라 눈에 보이게 다른 표현이고, 그 오른쪽 끝이 곧 현재값이라 둘이 이어진다.
 * 게다가 caption이 창을 이름으로 밝힌다.
 */
public final class Charts {

    private static final Logger log = LoggerFactory.getLogger(Charts.class);

    private Charts() {
    }

    /**
     * @param tag     로그 앞머리 — {@code webhook}·{@code digest}. 어느 경로에서 빠졌는지 로그가 가른다
     * @param subject caption에 적을 이름
     * @param unit    단위. 지수는 {@code null} — 「6,869.83 KRW」라고 적을 근거가 없다
     * @param bars    일봉 조회. <b>여기서 불린다</b> — 글을 먼저 보낸 뒤 부르면 조회 시간이 발송 간격 안에 숨는다
     * @return 그림. 못 그리면 빈 값
     */
    public static Optional<ChartImage> of(String tag, String subject, String unit, Supplier<List<DailyBar>> bars) {
        try {
            List<DailyBar> series = bars.get();
            if (!DailySeries.drawable(series)) {
                // ⚠️ 조용히 빠지지 않는다. 여기까지 왔다는 것은 열쇠는 있었는데 칸이 없다는 뜻이고 원인이
                //    둘이다: KIS가 모르는 심볼이라 0.00만 와서 DailySeries가 전부 걸러냈거나, 상장 직후라
                //    칸이 모자라거나. 예전에는 이 자리가 로그 한 줄 없이 빈손이어서 「차트가 안 나온다」와
                //    「차트를 안 물었다」를 구분할 수 없었다
                log.info("[{}] {} 일봉이 {}칸뿐이라 차트를 뺍니다 — KIS가 모르는 심볼이면 0.00만 와서 전부 걸러집니다",
                        tag, subject, series == null ? 0 : series.size());
                return Optional.empty();
            }
            byte[] png = ChartRenderer.render(series);
            return png.length == 0
                    ? Optional.empty()
                    : Optional.of(new ChartImage(png, ChartCaption.of(subject, unit, series)));
        } catch (RuntimeException e) {
            log.info("[{}] {} 일봉을 못 받아 차트를 뺍니다: {}", tag, subject, FailureReason.of(e));
            return Optional.empty();
        }
    }

    /** 시세가 든 통화가 곧 단위다. 지수는 {@code Money.NONE}이라 {@code null} — caption이 숫자만 적는다. */
    public static String unitOf(StockQuote quote) {
        return quote.currency() == StockQuote.Money.NONE ? null : quote.currency().name();
    }
}
