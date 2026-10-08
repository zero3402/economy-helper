package io.saiden.economyhelper.telegram.presentation;

import io.saiden.economyhelper.crypto.domain.CryptoQuote.Quote;
import io.saiden.economyhelper.crypto.domain.CryptoQuote;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.fx.domain.FxSource;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import io.saiden.economyhelper.stock.domain.StockQuote;
import io.saiden.economyhelper.stock.domain.StockSource;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.SkyCondition;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 규칙 테스트({@code MessageFormattingTest})와 골든 테스트({@code RenderedOutputTest})가 함께 쓰는 값.
 *
 * <p>⚠️ <b>값을 바꾸면 골든 파일이 바뀐다.</b> 여기 있는 값은 전부 {@code golden/messages.txt}에
 * 찍혀 있다 — 한 테스트만을 위한 값은 그 테스트 안에 둔다.
 */
final class PresentationFixtures {

    static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    /** 공공데이터포털은 전일 종가를 준다 — 화면에는 이 날짜만 찍힌다. */
    static final Instant BASIS = LocalDate.of(2026, 8, 11)
            .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
    /** 미국 현재가의 조회 시각 — KST 08-13 07:00. */
    static final Instant US_AT = Instant.parse("2026-08-12T22:00:00Z");
    static final FxRate FX = new FxRate("USD", "KRW", new Price(new BigDecimal("1412.17")),
            FxSource.FRANKFURTER, BASIS);
    /** 김프 산수를 눈으로 검산하려고 소수점을 턴 환율. */
    static final FxRate FX_FLAT = new FxRate("USD", "KRW", new Price(new BigDecimal("1412.00")),
            FxSource.FRANKFURTER, BASIS);

    private PresentationFixtures() {
    }

    // --- 뉴스 ---------------------------------------------------------------

    static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem("CNBC", title, body, "https://example.com/a", NOW, translated);
    }

    // --- 증시 ---------------------------------------------------------------

    static StockQuote krIndex(String name, String price) {
        return new StockQuote(name, new Price(new BigDecimal(price)), null,
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.DATA_GO, BASIS, false);
    }

    static StockQuote krStock(String name, String price) {
        return krStock(name, price, null);
    }

    /** @param change 등락률(%) 문자열. {@code null}이면 "못 구했다"는 뜻이다 */
    static StockQuote krStock(String name, String price, String change) {
        return new StockQuote(name, new Price(new BigDecimal(price)),
                change == null ? null : new PercentChange(new BigDecimal(change)),
                StockQuote.Money.KRW, StockQuote.Market.DOMESTIC, StockSource.DATA_GO, BASIS, false);
    }

    static StockQuote usIndex(String name, String price) {
        return new StockQuote(name, new Price(new BigDecimal(price)), null,
                StockQuote.Money.NONE, StockQuote.Market.US, StockSource.FMP, US_AT, true);
    }

    static StockQuote usStock(String name, String price) {
        return new StockQuote(name, new Price(new BigDecimal(price)), null,
                StockQuote.Money.USD, StockQuote.Market.US, StockSource.FMP, US_AT, true);
    }

    /**
     * 한국투자증권이 답한 시세 — <b>국내도 미국도 실시간이고 시각은 '읽은 시각'이다.</b>
     * 이 출처는 시각 필드를 주지 않아 넷이 같은 초를 갖는다(브리핑이 한 번에 부른다).
     */
    static StockQuote kis(String name, String price, StockQuote.Money currency,
                          StockQuote.Market market) {
        return new StockQuote(name, new Price(new BigDecimal(price)), null, currency, market,
                StockSource.KIS, US_AT, true);
    }

    /**
     * 같은 종목의 시각만 바꾼다 — FMP가 심볼마다 제 체결 초를 주거나, 지수와 종목이 각자 날짜를
     * 뒤로 감아 찾아 종가 날짜가 어긋나는 상황을 만든다.
     */
    static StockQuote at(StockQuote quote, Instant at) {
        return new StockQuote(quote.name(), quote.price(), quote.changePercent(),
                quote.currency(), quote.market(), quote.source(), at, quote.realtime());
    }

    // --- 코인 ---------------------------------------------------------------

    /** 업비트 값은 항상 있고, 바이낸스는 인자로 준다({@code null}이면 미상장). */
    static CryptoQuote btc(BigDecimal binanceUsdt) {
        return new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new Price(new BigDecimal("89848000")), null),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(new Price(binanceUsdt), null));
    }

    /** 테더는 바이낸스 호가가 USD다({@code USDTUSD}) — 2026-08-15 실측 0.99906. */
    static CryptoQuote usdt() {
        return new CryptoQuote("테더", "KRW-USDT", NOW,
                Quote.of(new Price(new BigDecimal("1425")), null),
                Quote.of(new Price(new BigDecimal("0.99906")), null));
    }

    // --- 날씨 ---------------------------------------------------------------

    static Weather migeum() {
        return oneDay("미금역", null, SkyCondition.CLOUDY, "18.2", "29.6", 20);
    }

    static Weather seohyeon() {
        return oneDay("서현역", null, SkyCondition.CLEAR, "19.0", "30.1", 10);
    }

    /** 평상시 경로 — 1순위 AccuWeather가 답한 하루. */
    static Weather oneDay(String name, String country, SkyCondition sky,
                          String low, String high, Integer chance) {
        return new Weather(place(name, country),
                List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17), sky,
                        new BigDecimal(low), new BigDecimal(high), chance)),
                WeatherSource.ACCU_WEATHER);
    }

    /** 일주일치 — AccuWeather 무료가 5일까지라 이 기간은 언제나 Open-Meteo가 맡는다. */
    static Weather seongnamWeek() {
        return new Weather(place("성남시", "대한민국"),
                List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 18), SkyCondition.CLOUDY,
                                new BigDecimal("22.0"), new BigDecimal("30.5"), 49),
                        Weather.Daily.withChance(LocalDate.of(2026, 8, 19), SkyCondition.CLEAR,
                                new BigDecimal("21.4"), new BigDecimal("29.9"), 55)),
                WeatherSource.OPEN_METEO);
    }

    /**
     * 폴백 — 2순위 Open-Meteo가 답한 하루.
     *
     * <p>확률이 아니라 강수량인 이유는 Open-Meteo가 {@code precipitation_probability_max}를
     * 안 줄 때 {@code precipitation_sum}으로 떨어지기 때문이다({@code DailyBlock.toDays}).
     * 값을 다른 것인 척하지 않는다는 규칙이 여기서 화면에 드러난다.
     */
    static Weather openMeteoFallback() {
        return openMeteoFallbackAt("미금역");
    }

    static Weather openMeteoFallbackAt(String name) {
        return new Weather(place(name, null),
                List.of(Weather.Daily.withAmount(LocalDate.of(2026, 8, 17), SkyCondition.RAIN,
                        new BigDecimal("18.2"), new BigDecimal("29.6"), new BigDecimal("2.4"))),
                WeatherSource.OPEN_METEO);
    }

    static Weather archived() {
        return new Weather(place("성남시", "대한민국"),
                List.of(Weather.Daily.withAmount(LocalDate.of(2025, 8, 19), SkyCondition.DRIZZLE,
                        new BigDecimal("25.3"), new BigDecimal("31.0"), new BigDecimal("0.8"))),
                WeatherSource.OPEN_METEO_ARCHIVE);
    }

    static GeoLocation place(String name, String country) {
        return new GeoLocation(name, country, 37.35, 127.10889, ZoneId.of("Asia/Seoul"));
    }
}
