package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService.StockMatch;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.news.NewsSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageFormatterTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    /** 공공데이터포털은 전일 종가를 준다 — 브리핑에 찍히는 날짜가 이것이다. */
    private static final Instant BASIS = LocalDate.of(2026, 8, 11)
            .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    /** 미국 현재가의 조회 시각 — KST 08-13 07:00. */
    private static final Instant US_AT = Instant.parse("2026-08-12T22:00:00Z");
    private static final FxRate FX = new FxRate("USD", "KRW", new BigDecimal("1412.17"),
            FxSource.FRANKFURTER, BASIS);

    @Test
    @DisplayName("매체명·제목·본문·링크를 담는다")
    void includesAllParts() {
        String message = MessageFormatter.format(item("유가 상승", "인플레이션 우려가 되살아났다.", true));

        assertThat(message)
                .contains("<b>Bloomberg</b>")
                .contains(">유가 상승</a>")
                .contains("<blockquote>인플레이션 우려가 되살아났다.</blockquote>")
                .as("긴 URL 줄이 아니라 제목이 링크가 된다")
                .contains("<a href=\"https://example.com/a\">");
    }

    @Test
    @DisplayName("번역 실패 시 왜 영문인지 알린다 — 안 그러면 고장으로 보인다")
    void explainsUntranslatedOutput() {
        String message = MessageFormatter.format(item("Oil holds advance", "Oil kept its gains.", false));

        assertThat(message).contains("번역이 일시적으로 불가");
    }

    @Test
    @DisplayName("번역에 성공하면 경고를 붙이지 않는다")
    void noWarningWhenTranslated() {
        assertThat(MessageFormatter.format(item("유가 상승", "본문", true)))
                .doesNotContain("번역이 일시적으로 불가");
    }

    @Test
    @DisplayName("요약문이 없는 기사(Reuters)는 본문 자리를 비워 둔다")
    void handlesEmptyBody() {
        String message = MessageFormatter.format(item("Fed signals cut", "", true));

        assertThat(message).contains("Fed signals cut").doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("정기 발송은 매체별 1건을 제목 아래로 묶는다")
    void joinsDigestWithSeparator() {
        String message = MessageFormatter.formatDigest(List.of(
                item("첫 번째", "본문1", true),
                item("두 번째", "본문2", true)));

        assertThat(message).contains("📰 <b>뉴스</b>").contains("첫 번째").contains("두 번째");
    }

    @Test
    @DisplayName("수집 결과가 하나도 없으면 그 사실을 알린다")
    void tellsUserWhenDigestIsEmpty() {
        assertThat(MessageFormatter.formatDigest(List.of())).contains("가져올 수 있는 뉴스가 없습니다");
    }

    @Test
    @DisplayName("검색 결과가 없을 때와 검색어가 빠졌을 때를 구분해 안내한다")
    void distinguishesNoResultsFromMissingQuery() {
        assertThat(MessageFormatter.noResults("금리")).contains("금리").contains("찾지 못했습니다");
        assertThat(MessageFormatter.usage(Command.NEWS)).contains("/news 금리");
        // 명령마다 예시가 달라야 한다 — 하나로 고정하면 /stock에 /news 예시가 뜬다
        assertThat(MessageFormatter.usage(Command.STOCK)).contains("/stock 삼성전자");
    }

    @Test
    @DisplayName("도움말은 모든 명령을 빠짐없이 싣는다")
    void helpListsEveryCommand() {
        String help = MessageFormatter.help();
        for (Command command : Command.values()) {
            assertThat(help).contains(command.token());
        }
    }

    @Test
    @DisplayName("모르는 명령에는 도움말을 함께 준다 — 무엇을 칠 수 있는지 알려주지 않으면 고장으로 보인다")
    void unknownCommandIncludesHelp() {
        assertThat(MessageFormatter.unknownCommand())
                .contains("모르는 명령")
                .contains("/news");
    }

    @Test
    @DisplayName("국내와 미국을 무리로 갈라 각각 기준을 밝힌다 — 종가와 현재가를 한 덩어리로 붙이면 안 된다")
    void stockDigestSeparatesByFreshness() {
        String message = MessageFormatter.formatStockDigest(List.of(
                krIndex("코스피", "6345.53"),
                krStock("삼성전자", "239500"),
                usIndex("나스닥", "26588.49"),
                usStock("애플", "AAPL", "302.25")), FX);

        assertThat(message).isEqualTo("""
                📈 <b>증시</b>

                <b>국내</b>  2026년 8월 11일 (종가)

                코스피  6,345.53
                삼성전자  239,500 KRW

                <b>미국</b>  2026년 8월 13일 07:00:00

                나스닥  26,588.49
                애플  302.25 USD · 426,828 KRW""");
        assertThat(message)
                .as("복사 버튼이 붙는 코드 블록을 쓰지 않는다").doesNotContain("<pre>")
                .as("환율은 바로 앞 환율 통에 이미 있다 — 여기 또 넣으면 중복이다")
                .doesNotContain("1 USD =");
    }

    @Test
    @DisplayName("한쪽만 있으면 그 무리만 나온다 — 없는 무리 자리에 제목을 남기지 않는다")
    void stockDigestOmitsEmptyGroup() {
        assertThat(MessageFormatter.formatStockDigest(List.of(krStock("삼성전자", "239500")), FX))
                .isEqualTo("""
                        📈 <b>증시</b>

                        <b>국내</b>  2026년 8월 11일 (종가)

                        삼성전자  239,500 KRW""");
    }

    @Test
    @DisplayName("지수에는 원화 환산을 붙이지 않는다 — 지수는 통화가 아니다")
    void stockDigestNeverConvertsIndices() {
        assertThat(MessageFormatter.formatStockDigest(List.of(usIndex("나스닥", "26588.49")), FX))
                .contains("26,588.49").doesNotContain("약").doesNotContain("KRW)");
    }

    @Test
    @DisplayName("환율이 없으면 달러만 보낸다 — 환산을 못 한다고 시세를 빼면 안 된다")
    void stockDigestFallsBackToUsdWithoutFx() {
        String message = MessageFormatter.formatStockDigest(List.of(usStock("애플", "AAPL", "302.25")), null);

        assertThat(message).contains("302.25 USD").doesNotContain("약").doesNotContain("1 USD =");
    }

    @Test
    @DisplayName("무리 안에서 기준일이 어긋나면 묵은 줄에 날짜를 붙인다 — 조용히 섞으면 거짓말이 된다")
    void stockDigestMarksStaleLines() {
        StockQuote stale = new StockQuote(null, "코스닥", "KOSDAQ시리즈", new BigDecimal("857.84"),
                StockQuote.Money.NONE, BASIS.minus(java.time.Duration.ofDays(1)),
                false, true, BigDecimal.ZERO);

        assertThat(MessageFormatter.formatStockDigest(List.of(krIndex("코스피", "6345.53"), stale), FX))
                .contains("<b>국내</b>  2026년 8월 11일 (종가)")
                .contains("2026년 8월 10일")
                .as("기준과 같은 줄에는 날짜를 안 붙인다").doesNotContain("6,345.53  2026년");
    }

    @Test
    @DisplayName("검색 결과도 달러와 원화를 함께 보여준다")
    void formatsUsStockWithKrw() {
        String message = MessageFormatter.formatStock(
                new StockMatch(usStock("애플", "AAPL", "302.25"), List.of()), FX);

        assertThat(message)
                .contains("<b>애플</b>").contains("AAPL").contains("302.25 USD")
                .contains("약 426,828 KRW")
                .contains("FMP · 2026년 8월 13일 07:00:00")
                .as("현재가이므로 '종가'라고 쓰면 안 된다").doesNotContain("종가");
    }

    private static StockQuote krIndex(String name, String price) {
        return new StockQuote(null, name, "KOSPI시리즈", new BigDecimal(price),
                StockQuote.Money.NONE, BASIS, false, true, BigDecimal.ZERO);
    }

    private static StockQuote krStock(String name, String price) {
        return new StockQuote("005930", name, "KOSPI", new BigDecimal(price),
                StockQuote.Money.KRW, BASIS, false, false, new BigDecimal("1400183726616000"));
    }

    private static StockQuote usIndex(String name, String price) {
        return new StockQuote("^IXIC", name, "", new BigDecimal(price),
                StockQuote.Money.NONE, US_AT, true, true, BigDecimal.ZERO);
    }

    private static StockQuote usStock(String name, String symbol, String price) {
        return new StockQuote(symbol, name, "NASDAQ", new BigDecimal(price),
                StockQuote.Money.USD, US_AT, true, false, new BigDecimal("4439253351000"));
    }

    // --- 뉴스 날짜 ---------------------------------------------------------

    @Test
    @DisplayName("기사에 발행 일시를 붙인다 — 없으면 어제 것인지 방금 것인지 알 수 없다")
    void showsArticlePublishedTime() {
        // NOW = 2026-08-11T00:00:00Z → KST 09:00
        assertThat(MessageFormatter.format(item("유가 상승", "본문", true)))
                .contains("2026년 8월 11일 09:00:00");
    }

    @Test
    @DisplayName("브리핑의 뉴스에도 같은 일시가 붙는다 — 두 채널이 다른 모양이 되면 안 된다")
    void showsPublishedTimeInDigestToo() {
        assertThat(MessageFormatter.formatDigest(List.of(item("유가 상승", "본문", true))))
                .contains("2026년 8월 11일 09:00:00");
    }

    // --- 코인: 업비트 + 바이낸스 -------------------------------------------

    @Test
    @DisplayName("업비트 다음에 바이낸스, 그 옆에 원화 환산")
    void showsBothExchangesInOrder() {
        String message = MessageFormatter.formatCrypto(
                btc(new BigDecimal("63703.69")), new BigDecimal("1384"));

        assertThat(message.indexOf("업비트")).isLessThan(message.indexOf("바이낸스"));
        assertThat(message)
                .contains("89,848,000 KRW")
                .contains("63,703.69 USDT")
                // 63,703.69 × 1,384 = 88,165,906.96 → 88,165,907
                .contains("88,165,907 KRW");
    }

    @Test
    @DisplayName("단건은 값이 없는 이유를 적는다 — 다시 시도해야 하는지가 여기서 갈린다")
    void explainsMissingExchangeInSingleQuote() {
        assertThat(MessageFormatter.formatCrypto(btc(null), new BigDecimal("1384")))
                .contains("업비트 89,848,000 KRW")
                .as("영영 안 나오는 것").contains("바이낸스 미상장");

        assertThat(MessageFormatter.formatCrypto(
                new CryptoQuote("비트코인", "KRW-BTC", "BTCUSDT", NOW,
                        Quote.of(new BigDecimal("89848000")), Quote.FAILED), null))
                .as("잠시 뒤 다시 치면 되는 것").contains("바이낸스 조회 실패");
    }

    @Test
    @DisplayName("업비트에 없는 코인은 바이낸스만 적고 꼬리표도 바이낸스 심볼 하나뿐이다")
    void showsBinanceOnlyCoin() {
        String message = MessageFormatter.formatCrypto(
                new CryptoQuote("비앤비", null, "BNBUSDT", NOW,
                        Quote.NOT_LISTED, Quote.of(new BigDecimal("612.40"))),
                new BigDecimal("1384"));

        assertThat(message)
                .contains("업비트 미상장")
                .contains("바이낸스 612.4 USDT")
                .contains("BNBUSDT")
                .as("업비트 마켓 코드가 없으니 꼬리표에 구분자만 남으면 안 된다")
                .doesNotContain(" · BNBUSDT");
    }

    @Test
    @DisplayName("USDT 원화값을 모르면 USDT만 적는다 — 환산을 못 한다고 시세를 빼지 않는다")
    void showsUsdtOnlyWhenRateUnknown() {
        String message = MessageFormatter.formatCrypto(btc(new BigDecimal("63703.69")), null);

        assertThat(message).contains("63,703.69 USDT").doesNotContain("88,165,907");
    }

    @Test
    @DisplayName("브리핑 코인 통도 코인마다 두 거래소를 보여준다")
    void showsBothExchangesInDigest() {
        String message = MessageFormatter.formatCryptoDigest(
                List.of(btc(new BigDecimal("63703.69")), usdt()), new BigDecimal("1384"));

        assertThat(message).contains("비트코인").contains("바이낸스").contains("63,703.69 USDT");
    }

    @Test
    @DisplayName("브리핑에서는 값이 없는 거래소 줄을 뺀다 — 매일 같은 코인이라 첫날 이후 소음이다")
    void omitsMissingExchangeInDigest() {
        String message = MessageFormatter.formatCryptoDigest(
                List.of(btc(new BigDecimal("63703.69")), usdt()), new BigDecimal("1384"));

        assertThat(message.substring(message.indexOf("테더")))
                .as("테더는 바이낸스에 USDTUSDT가 없다 — 매일 아침 그 사실을 알릴 이유가 없다")
                .doesNotContain("바이낸스")
                .contains("업비트 1,384 KRW");
    }

    @Test
    @DisplayName("양쪽 다 값이 없는 코인은 브리핑에서 통째로 뺀다 — 이름만 찍히고 아래가 비면 고장으로 보인다")
    void dropsCoinWithNoPriceFromDigest() {
        String message = MessageFormatter.formatCryptoDigest(
                List.of(btc(new BigDecimal("63703.69")),
                        new CryptoQuote("이더리움", "KRW-ETH", "ETHUSDT", NOW,
                                Quote.FAILED, Quote.FAILED)),
                new BigDecimal("1384"));

        assertThat(message).contains("비트코인").doesNotContain("이더리움");
    }

    private static CryptoQuote btc(BigDecimal binanceUsdt) {
        return new CryptoQuote("비트코인", "KRW-BTC", binanceUsdt == null ? null : "BTCUSDT", NOW,
                Quote.of(new BigDecimal("89848000")),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(binanceUsdt));
    }

    /** 바이낸스에 {@code USDTUSDT}가 없다 — 브리핑 기본 설정에 들어 있어 실제로 밟는 길이다. */
    private static CryptoQuote usdt() {
        return new CryptoQuote("테더", "KRW-USDT", null, NOW,
                Quote.of(new BigDecimal("1384")), Quote.NOT_LISTED);
    }

    private static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem(NewsSource.BLOOMBERG, "Bloomberg", title, body,
                "https://example.com/a", NOW, translated, 0.9);
    }
}
