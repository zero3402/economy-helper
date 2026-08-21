package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherSource;
import io.saiden.economyhelper.news.NewsItem;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import io.saiden.economyhelper.market.weather.PrecipitationSpell;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>화면 전체를 한 파일로 얼려 둔다.</b>
 *
 * <p>{@code MessageFormattingTest}는 주장 하나에 테스트 하나라 <b>규칙</b>을 지킨다. 이 테스트가
 * 지키는 것은 다른 것이다 — <b>리팩터링이 화면을 건드리지 않았다는 증명</b>이다. 구조를 옮기는
 * 커밋마다 이 골든 파일의 diff가 비어 있어야 하고, 버그를 고치는 커밋에서는 <b>바뀐 그 줄이
 * 곧 무엇을 고쳤나의 증거</b>가 된다.
 *
 * <p>예전에는 이 증명을 손으로 했다 — 이전 커밋으로 되돌려 같은 하네스로 찍고 {@code diff}
 * ({@code ARCHITECTURE.md} §6). 그 방식은 커밋 사이에 오라클이 사라지므로 "커밋마다 초록"을
 * 보장하지 못한다. 그래서 저장소 안으로 들여왔다.
 *
 * <p><b>골든이 기계를 타지 않는 이유</b>는 포매터가 완전히 결정적이기 때문이다 —
 * {@code Instant.now()}가 없고, {@code Locale.KOREAN}·{@code Locale.KOREA}·{@code Asia/Seoul}이
 * 전부 명시돼 있고, 출처 정렬이 enum 선언 순이다.
 *
 * <p>⚠️ <b>골든 파일이 없으면 만들어 주고 실패한다.</b> 조용히 만들어 통과시키면 그때의 버그가
 * 요구사항으로 굳는다 — 실제로 온도 {@code 21.0}이 {@code 21}로 나오던 버그가 테스트에
 * {@code '22°C / 30.5°C'}로 굳어 있었다. 사람이 한 번 읽고 커밋해야 한다.
 */
class RenderedOutputTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/messages.txt");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    /** 전일 종가의 기준일 — 화면에 날짜만 찍힌다. */
    private static final Instant BASIS = LocalDate.of(2026, 8, 11).atStartOfDay(SEOUL).toInstant();
    /** 미국 현재가의 조회 시각 — KST 08-13 07:00. */
    private static final Instant US_AT = Instant.parse("2026-08-12T22:00:00Z");
    /** 하루가 어긋난 종가 — 지수와 종목이 각자 날짜를 뒤로 감아 찾을 때 실제로 난다. */
    private static final Instant BASIS_PREV = LocalDate.of(2026, 8, 10).atStartOfDay(SEOUL).toInstant();

    private static final FxRate FX = new FxRate("USD", "KRW", new BigDecimal("1412.17"),
            FxSource.FRANKFURTER, BASIS);
    private static final FxRate FX_INTRADAY = new FxRate("USD", "KRW", new BigDecimal("1389.40"),
            new BigDecimal("-0.32"), FxSource.KIS, US_AT);
    private static final FxRate FX_FLAT = new FxRate("USD", "KRW", new BigDecimal("1412.00"),
            FxSource.FRANKFURTER, BASIS);

    @Test
    @DisplayName("렌더한 화면 전부가 골든과 한 글자도 다르지 않다 — 리팩터링이 출력을 건드리지 않았다는 증명")
    void everyRenderedMessageMatchesTheGoldenFile() throws IOException {
        String rendered = render(cases());

        if (!Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, rendered, StandardCharsets.UTF_8);
            fail("골든 파일이 없어 새로 만들었습니다: " + GOLDEN.toAbsolutePath()
                    + "\n눈으로 한 번 읽고 커밋하세요 — 읽지 않고 굳히면 버그가 요구사항이 됩니다.");
        }

        assertThat(rendered).isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
    }

    private static String render(Map<String, String> cases) {
        StringBuilder out = new StringBuilder();
        cases.forEach((name, body) ->
                out.append("=== ").append(name).append(" ===\n").append(body).append("\n\n"));
        return out.toString();
    }

    /** 이름 → 렌더 결과. <b>순서가 파일 순서다</b> — 새 케이스는 그 도메인 끝에 붙인다. */
    private static Map<String, String> cases() {
        Map<String, String> cases = new LinkedHashMap<>();

        // --- 뉴스 -----------------------------------------------------------
        // 한 건이면 외로운 '1/1'이 어색하므로 제목만 쓴다 — 실제 발송 형태로 찍는다
        cases.put("news/one", String.join("",
                NewsFormatter.formatAll(List.of(item("유가 상승", "인플레이션 우려.", true)))));
        cases.put("news/untranslated",
                NewsFormatter.format(item("Oil holds advance", "Oil kept its gains.", false)));
        cases.put("news/no-body", NewsFormatter.format(item("유가 상승", "", true)));
        cases.put("news/tld-in-source-name", NewsFormatter.format(
                new NewsItem("Investing.com", "금리 인하 기대", "연준이 신호를 보냈다.",
                        "https://example.com/a", NOW, true)));
        cases.put("news/three-split", String.join("\n--- 통 경계 ---\n",
                NewsFormatter.formatAll(List.of(
                        item("첫째", "본문 하나.", true),
                        item("둘째", "본문 둘.", true),
                        item("셋째", "본문 셋.", true)))));
        cases.put("news/empty", String.join("", NewsFormatter.formatAll(List.of())));
        cases.put("news/no-results", NewsFormatter.noResults("금리", Duration.ofHours(24)));

        // --- 환율 -----------------------------------------------------------
        cases.put("fx/notice", FxFormatter.format(FX));
        cases.put("fx/intraday", FxFormatter.format(FX_INTRADAY));
        cases.put("fx/unavailable", FxFormatter.unavailable());

        // --- 증시 -----------------------------------------------------------
        cases.put("stock/both-groups", StockFormatter.format(List.of(
                krIndex("코스피", "3182.44"), krStock("삼성전자", "82000", "-5.40"),
                usIndex("나스닥", "21713.14"), usStock("애플", "232.78")), FX));
        cases.put("stock/domestic-only", StockFormatter.format(List.of(
                krIndex("코스피", "3182.44"), krStock("삼성전자", "82000", "-5.40")), FX));
        cases.put("stock/us-only", StockFormatter.format(List.of(
                usStock("애플", "232.78")), FX));
        cases.put("stock/us-without-fx", StockFormatter.format(List.of(
                usStock("애플", "232.78")), null));
        cases.put("stock/index-only", StockFormatter.format(List.of(
                krIndex("코스피", "3182.44")), FX));
        cases.put("stock/realtime-and-closing-mixed", StockFormatter.format(List.of(
                kis("코스피", "3182.44", StockQuote.Money.NONE, StockQuote.Market.DOMESTIC),
                krStock("삼성전자", "82000", "-5.40")), FX));
        cases.put("stock/two-sources-one-group", StockFormatter.format(List.of(
                kis("삼성전자", "82000", StockQuote.Money.KRW, StockQuote.Market.DOMESTIC),
                krStock("SK하이닉스", "241000", "1.20")), FX));
        cases.put("stock/closing-dates-differ", StockFormatter.format(List.of(
                krIndex("코스피", "3182.44"),
                at(krStock("삼성전자", "82000", "-5.40"), BASIS_PREV)), FX));
        cases.put("stock/us-timestamps-differ", StockFormatter.format(List.of(
                usStock("애플", "232.78"),
                at(usStock("엔비디아", "182.02"), US_AT.minusSeconds(37))), FX));
        cases.put("stock/change-up", StockFormatter.format(List.of(
                krStock("삼성전자", "82000", "4.89")), null));
        cases.put("stock/change-flat", StockFormatter.format(List.of(
                krStock("삼성전자", "82000", "0")), null));
        cases.put("stock/change-unknown", StockFormatter.format(List.of(
                krStock("삼성전자", "82000", null)), null));
        cases.put("stock/change-rounds", StockFormatter.format(List.of(
                krStock("삼성전자", "82000", "-1.451")), null));
        cases.put("stock/escaped-name", StockFormatter.format(List.of(
                usIndex("S&P 500", "6481.40")), null));
        cases.put("stock/empty", StockFormatter.format(List.of(), FX));
        cases.put("stock/not-found", StockFormatter.notFound("없는종목 <b>"));

        // --- 코인 -----------------------------------------------------------
        cases.put("crypto/full", CryptoFormatter.format(List.of(btc(new BigDecimal("62000"))), FX_FLAT));
        cases.put("crypto/without-fx", CryptoFormatter.format(List.of(btc(new BigDecimal("62000"))), null));
        cases.put("crypto/upbit-only", CryptoFormatter.format(List.of(btc(null)), FX_FLAT));
        cases.put("crypto/binance-failed", CryptoFormatter.format(List.of(
                new CryptoQuote("비트코인", "KRW-BTC", NOW,
                        Quote.of(new BigDecimal("89848000"), null), Quote.FAILED)), FX_FLAT));
        // 밴은 '조회 실패'와 다른 말이다 — 다시 치면 오히려 밴이 길어지므로 언제 풀리는지를 적는다
        cases.put("crypto/binance-banned", CryptoFormatter.format(List.of(
                new CryptoQuote("비트코인", "KRW-BTC", NOW,
                        Quote.of(new BigDecimal("89848000"), null),
                        Quote.banned(NOW.plusSeconds(7200)))), FX_FLAT));
        cases.put("crypto/upbit-failed", CryptoFormatter.format(List.of(
                new CryptoQuote("이더리움", "KRW-ETH", NOW, Quote.FAILED, Quote.FAILED)), FX_FLAT));
        cases.put("crypto/no-upbit-market", CryptoFormatter.format(List.of(
                new CryptoQuote("BNB", null, NOW, Quote.NOT_LISTED,
                        Quote.of(new BigDecimal("602.27"), null))), FX_FLAT));
        cases.put("crypto/usdt", CryptoFormatter.format(List.of(usdt()), FX_FLAT));
        cases.put("crypto/cheap-coin", CryptoFormatter.format(List.of(
                new CryptoQuote("도지코인", "KRW-DOGE", NOW,
                        Quote.of(new BigDecimal("0.5"), new BigDecimal("2.10")),
                        Quote.of(new BigDecimal("0.00035"), new BigDecimal("1.90")))), FX_FLAT));
        cases.put("crypto/two-coins", CryptoFormatter.format(List.of(
                btc(new BigDecimal("62000")), usdt()), FX_FLAT));
        cases.put("crypto/empty", CryptoFormatter.format(List.of(), FX_FLAT));
        cases.put("crypto/not-found", CryptoFormatter.notFound("없는코인"));

        // --- 날씨 -----------------------------------------------------------
        cases.put("weather/one-place-one-day", WeatherFormatter.format(List.of(migeum())));
        cases.put("weather/four-places", WeatherFormatter.format(List.of(
                migeum(), seohyeon(),
                oneDay("잠실역", null, SkyCondition.RAIN, "20.1", "27.3", 80),
                oneDay("삼성중앙역", null, SkyCondition.CLOUDY, "20.5", "28.0", 30))));
        cases.put("weather/multi-day", WeatherFormatter.format(List.of(seongnamWeek())));
        // 강수 시각 — 「비옴」이 언제인지. 일 단위 요약이 못 말해 주는 것이 이 줄이다
        cases.put("weather/precipitation-spell", WeatherFormatter.format(List.of(withSpells(
                spell(13, 19, SkyCondition.RAIN, 80)))));
        // ⚠️ **눈은 눈으로 나와야 한다.** ☔ 하나로 적으면 눈 오는 날에 우산 그림이 붙는다 —
        //    이 기능을 만든 이유가 「소나기일 수도 눈일 수도 있다」였다
        cases.put("weather/precipitation-snow", WeatherFormatter.format(List.of(withSpells(
                spell(7, 10, SkyCondition.SNOW, 90)))));
        cases.put("weather/precipitation-thunder", WeatherFormatter.format(List.of(withSpells(
                spell(15, 15, SkyCondition.THUNDERSTORM, 70)))));
        // 하루에 두 번 — 아침 비와 저녁 눈을 하나로 잇지 않는다
        cases.put("weather/precipitation-twice", WeatherFormatter.format(List.of(withSpells(
                spell(6, 8, SkyCondition.SHOWERS, 65), spell(20, 22, SkyCondition.SNOW, 85)))));
        // 자정·정오는 「오후 12시」가 헷갈려 제 이름으로 적는다
        cases.put("weather/precipitation-noon-midnight", WeatherFormatter.format(List.of(withSpells(
                spell(0, 0, SkyCondition.SLEET, 55), spell(12, 12, SkyCondition.DRIZZLE, 60)))));
        // 지나간 날은 확률이 없다 — 실제로 온 양으로 적는다
        cases.put("weather/precipitation-measured", WeatherFormatter.format(List.of(
                archivedWithSpells())));
        // ⚠️ 정오를 넘는 비는 **두 줄**로 나와야 한다. PrecipitationSpells.fold가 거기서 끊으므로
        //    포매터에 오는 것은 이미 토막 둘이고, 각 줄이 제 반나절의 확률을 든다 —
        //    한 토막을 두 줄로 그리면 두 줄에 같은 숫자가 찍혀 한쪽이 거짓이 된다.
        //    이 케이스가 없던 동안에는 골든 어디에도 정오를 넘는 비가 없어, 쪼개는 동작이
        //    화면에서 무엇이 되는지 아무 오라클도 보지 못했다
        cases.put("weather/precipitation-across-noon", WeatherFormatter.format(List.of(withSpells(
                spell(10, 11, SkyCondition.RAIN, 80), spell(12, 15, SkyCondition.RAIN, 90)))));
        cases.put("weather/rain-amount", WeatherFormatter.format(List.of(openMeteoFallback())));
        cases.put("weather/archived", WeatherFormatter.format(List.of(archived())));
        // 0.25는 HALF_EVEN이면 0.2, HALF_UP이면 0.3이다 — oneDecimal만 반올림이 달랐던 자리를
        // 화면으로 못 박는다. change·krw·premium은 전부 HALF_UP이다
        cases.put("weather/rain-rounds-half-up", WeatherFormatter.format(List.of(
                new Weather(place("미금역", null),
                        List.of(Weather.Daily.withAmount(LocalDate.of(2026, 8, 17),
                                SkyCondition.DRIZZLE, new BigDecimal("18.25"),
                                new BigDecimal("29.65"), new BigDecimal("0.25"))),
                        WeatherSource.OPEN_METEO))));
        cases.put("weather/unknown-sky", WeatherFormatter.format(List.of(
                new Weather(place("미금역", null),
                        List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17),
                                SkyCondition.UNKNOWN, new BigDecimal("18.2"),
                                new BigDecimal("29.6"), 20)),
                        WeatherSource.ACCU_WEATHER))));
        cases.put("weather/no-precipitation", WeatherFormatter.format(List.of(
                new Weather(place("미금역", null),
                        List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17),
                                SkyCondition.CLEAR, new BigDecimal("18.2"),
                                new BigDecimal("29.6"), null)),
                        WeatherSource.ACCU_WEATHER))));
        cases.put("weather/sources-diverge", WeatherFormatter.format(List.of(
                migeum(), openMeteoFallbackAt("서현역"))));
        // 예보와 실측이 한 통에 섞인 경우 — 꼬리가 성격마다 한 줄씩 쌓여야 한다.
        // 예전에는 첫 지역 하나로 정해 통째로 '(예보)'라고 말했다
        cases.put("weather/forecast-and-archive-mixed", WeatherFormatter.format(List.of(
                migeum(), archived())));
        cases.put("weather/overseas", WeatherFormatter.format(List.of(
                oneDay("파리", "프랑스", SkyCondition.CLEAR, "16.0", "24.2", 5))));
        cases.put("weather/empty", WeatherFormatter.format(List.of()));
        cases.put("weather/not-found", WeatherFormatter.notFound("없는지명"));
        cases.put("weather/needs-place", WeatherFormatter.needsPlace());
        cases.put("weather/unreadable-date", WeatherFormatter.unreadableDate());
        cases.put("weather/too-far-ahead", WeatherFormatter.tooFarAhead());
        cases.put("weather/unavailable", WeatherFormatter.unavailable());

        // --- 안내 -----------------------------------------------------------
        cases.put("help", HelpFormatter.help());
        cases.put("unknown-command", HelpFormatter.unknownCommand());
        for (Command command : Command.values()) {
            cases.put("usage/" + command.name().toLowerCase(java.util.Locale.ROOT),
                    HelpFormatter.usage(command));
        }
        return cases;
    }

    // --- 픽스처 -------------------------------------------------------------

    private static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem("CNBC", title, body, "https://example.com/a", NOW, translated);
    }

    private static StockQuote krIndex(String name, String price) {
        return new StockQuote(name, new BigDecimal(price), null,
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.DATA_GO, BASIS, false);
    }

    private static StockQuote krStock(String name, String price, String change) {
        return new StockQuote(name, new BigDecimal(price),
                change == null ? null : new BigDecimal(change),
                StockQuote.Money.KRW, StockQuote.Market.DOMESTIC, StockSource.DATA_GO, BASIS, false);
    }

    private static StockQuote usIndex(String name, String price) {
        return new StockQuote(name, new BigDecimal(price), null,
                StockQuote.Money.NONE, StockQuote.Market.US, StockSource.FMP, US_AT, true);
    }

    private static StockQuote usStock(String name, String price) {
        return new StockQuote(name, new BigDecimal(price), null,
                StockQuote.Money.USD, StockQuote.Market.US, StockSource.FMP, US_AT, true);
    }

    /** 한국투자증권이 답한 시세 — 국내도 미국도 실시간이고 시각은 '읽은 시각'이다. */
    private static StockQuote kis(String name, String price, StockQuote.Money currency,
                                  StockQuote.Market market) {
        return new StockQuote(name, new BigDecimal(price), null, currency, market,
                StockSource.KIS, US_AT, true);
    }

    /** 같은 종목의 시각만 바꾼다. */
    private static StockQuote at(StockQuote quote, Instant at) {
        return new StockQuote(quote.name(), quote.price(), quote.changePercent(),
                quote.currency(), quote.market(), quote.source(), at, quote.realtime());
    }

    private static CryptoQuote btc(BigDecimal binanceUsdt) {
        return new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("89848000"), null),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(binanceUsdt, null));
    }

    /** 테더는 바이낸스 호가가 USD다({@code USDTUSD}). */
    private static CryptoQuote usdt() {
        return new CryptoQuote("테더", "KRW-USDT", NOW,
                Quote.of(new BigDecimal("1425"), null),
                Quote.of(new BigDecimal("0.99906"), null));
    }

    private static Weather migeum() {
        return oneDay("미금역", null, SkyCondition.CLOUDY, "18.2", "29.6", 20);
    }

    private static Weather seohyeon() {
        return oneDay("서현역", null, SkyCondition.CLEAR, "19.0", "30.1", 10);
    }

    /** 강수 토막이 붙은 하루 — 미금역 하루치에 토막만 얹는다. */
    private static Weather withSpells(PrecipitationSpell... spells) {
        return new Weather(place("미금역", null),
                List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17), SkyCondition.CLOUDY,
                                new BigDecimal("18.2"), new BigDecimal("29.6"), 20)
                        .withPrecipitation(List.of(spells))),
                WeatherSource.ACCU_WEATHER);
    }

    /** 지나간 날 + 토막. 확률이 아니라 실제로 온 양이 적힌다. */
    private static Weather archivedWithSpells() {
        return new Weather(place("성남시", "대한민국"),
                List.of(Weather.Daily.withAmount(LocalDate.of(2026, 8, 10), SkyCondition.RAIN,
                                new BigDecimal("21.0"), new BigDecimal("26.4"), new BigDecimal("3.7"))
                        .withPrecipitation(List.of(PrecipitationSpell.withAmount(
                                LocalTime.of(2, 0), LocalTime.of(4, 0), SkyCondition.RAIN,
                                new BigDecimal("3.7"))))),
                WeatherSource.OPEN_METEO_ARCHIVE);
    }

    private static PrecipitationSpell spell(int from, int to, SkyCondition kind, int chance) {
        return PrecipitationSpell.withChance(
                LocalTime.of(from, 0), LocalTime.of(to, 0), kind, chance);
    }

    private static Weather oneDay(String name, String country, SkyCondition sky,
                                  String low, String high, Integer chance) {
        return new Weather(place(name, country),
                List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17), sky,
                        new BigDecimal(low), new BigDecimal(high), chance)),
                WeatherSource.ACCU_WEATHER);
    }

    /** 일주일치 — AccuWeather 무료가 5일까지라 이 기간은 언제나 Open-Meteo가 맡는다. */
    private static Weather seongnamWeek() {
        return new Weather(place("성남시", "대한민국"),
                List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 18), SkyCondition.CLOUDY,
                                new BigDecimal("22.0"), new BigDecimal("30.5"), 49),
                        Weather.Daily.withChance(LocalDate.of(2026, 8, 19), SkyCondition.CLEAR,
                                new BigDecimal("21.4"), new BigDecimal("29.9"), 55)),
                WeatherSource.OPEN_METEO);
    }

    /** 폴백 — 2순위가 답하면 확률이 아니라 강수량으로 내려앉는다. */
    private static Weather openMeteoFallback() {
        return openMeteoFallbackAt("미금역");
    }

    private static Weather openMeteoFallbackAt(String name) {
        return new Weather(place(name, null),
                List.of(Weather.Daily.withAmount(LocalDate.of(2026, 8, 17), SkyCondition.RAIN,
                        new BigDecimal("18.2"), new BigDecimal("29.6"), new BigDecimal("2.4"))),
                WeatherSource.OPEN_METEO);
    }

    private static Weather archived() {
        return new Weather(place("성남시", "대한민국"),
                List.of(Weather.Daily.withAmount(LocalDate.of(2025, 8, 19), SkyCondition.DRIZZLE,
                        new BigDecimal("25.3"), new BigDecimal("31.0"), new BigDecimal("0.8"))),
                WeatherSource.OPEN_METEO_ARCHIVE);
    }

    private static GeoLocation place(String name, String country) {
        return new GeoLocation(name, country, 37.35, 127.10889, SEOUL);
    }
}
