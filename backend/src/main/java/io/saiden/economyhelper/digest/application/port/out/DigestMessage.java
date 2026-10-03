package io.saiden.economyhelper.digest.application.port.out;

import io.saiden.economyhelper.crypto.domain.CryptoQuote;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.shared.domain.DailyBar;
import io.saiden.economyhelper.stock.domain.StockOutlook;
import io.saiden.economyhelper.stock.domain.StockQuote;
import io.saiden.economyhelper.weather.domain.Weather;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 정기 발송 잡이 창구에 넘기는 <b>한 통의 값</b> — 글자·그림·전송은 창구({@link DigestNotifier})의 몫이다.
 *
 * <p>잡은 무엇을 보낼지(어느 값을, 어느 순서로)만 정하고 어떻게 적을지는 모른다. 그래서 여기에는
 * 조회 컨텍스트가 이미 가진 값만 담긴다.
 */
public sealed interface DigestMessage {

    /**
     * @param rate 원/달러 환율
     */
    record Fx(FxRate rate) implements DigestMessage {
    }

    /**
     * 국내·미국 지수와 종목을 한 통에.
     *
     * @param quotes   지수 → 국내 종목 → 미국 종목 순서
     * @param fx       미국 종목의 원화 환산에 쓸 환율. {@code null}이면 달러로만 나간다
     * @param outlooks 종목마다 붙는 전망. 지수에는 없다
     */
    record Stocks(List<StockQuote> quotes, FxRate fx, Map<StockQuote, StockOutlook> outlooks)
            implements DigestMessage {
    }

    /**
     * @param fx 바이낸스 값의 원화 환산과 김프에 쓴다. {@code null}이면 둘 다 빠진다
     */
    record Coins(List<CryptoQuote> quotes, FxRate fx) implements DigestMessage {
    }

    /** 점수순 기사들 — 창구가 기사마다 한 통으로 나눈다(미리보기 카드가 제 기사에 붙도록). */
    record News(List<NewsItem> items) implements DigestMessage {
    }

    /** 알람 지역들의 오늘 하루치 — 한 통이다. */
    record WeatherAlarm(List<Weather> places) implements DigestMessage {
    }

    /**
     * 통에 딸린 차트 한 장의 재료.
     *
     * @param subject caption에 적을 이름
     * @param unit    단위. 지수는 {@code null} — 「6,869.83 KRW」라고 적을 근거가 없다
     * @param bars    일봉. 잡이 수집할 때 이미 받아 둔 결과를 되돌려준다(실패였으면 그 예외를 다시 던진다)
     */
    record Chart(String subject, String unit, Supplier<List<DailyBar>> bars) {
    }
}
