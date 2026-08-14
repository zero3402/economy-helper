package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.StockQuote;
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
    @DisplayName("제목 / 본문 / 출처 / 시각 — 다른 통과 같은 순서, 시각은 맨 밑 단독")
    void followsTheSameSkeletonAsEveryOtherSection() {
        String message = MessageFormatter.format(item("유가 상승", "인플레이션 우려.", true));

        assertThat(message).isEqualTo("""
                <a href="https://example.com/a"><b>유가 상승</b></a>

                <blockquote>인플레이션 우려.</blockquote>

                CNBC

                2026년 8월 11일 09:00:00""");
    }

    @Test
    @DisplayName("번역 실패 시 왜 영문인지 알린다 — 안 그러면 고장으로 보인다")
    void explainsUntranslatedOutput() {
        String message = MessageFormatter.format(item("Oil holds advance", "Oil kept its gains.", false));

        assertThat(message).contains("번역이 일시적으로 불가");
    }

    @Test
    @DisplayName("요약문이 없는 기사(AP)는 본문 자리를 비워 둔다")
    void handlesEmptyBody() {
        String message = MessageFormatter.format(item("Fed signals cut", "", true));

        assertThat(message).contains("Fed signals cut").doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("정기 발송은 매체별 1건을 제목 아래로 묶는다")
    void joinsDigestWithSeparator() {
        String message = MessageFormatter.formatNews(List.of(
                item("첫 번째", "본문1", true),
                item("두 번째", "본문2", true)));

        assertThat(message).contains("<b>뉴스</b>").contains("첫 번째").contains("두 번째");
    }

    @Test
    @DisplayName("수집 결과가 하나도 없으면 그 사실을 알린다")
    void tellsUserWhenDigestIsEmpty() {
        assertThat(MessageFormatter.formatNews(List.of())).contains("가져올 수 있는 뉴스가 없습니다");
    }

    @Test
    @DisplayName("검색 결과가 없을 때와 검색어가 빠졌을 때를 구분해 안내한다")
    void distinguishesNoResultsFromMissingQuery() {
        assertThat(MessageFormatter.noResults("금리")).contains("금리").contains("찾지 못했습니다");
        assertThat(MessageFormatter.usage(Command.NEWS)).contains("/news 금리");
        // 명령마다 예시가 달라야 한다 — 하나로 고정하면 /stock에 /news 예시가 뜬다
        assertThat(MessageFormatter.usage(Command.STOCK)).contains("/stock 삼성전자");
        // 실패·안내 답도 성공 답과 같은 제목을 인다. 그룹 채팅에서 맨몸 문장 하나만
        // 튀어나오면 무엇에 대한 답인지 알 수 없다
        assertThat(MessageFormatter.usage(Command.STOCK)).startsWith("<b>증시</b>\n\n");
        assertThat(MessageFormatter.usage(Command.NEWS)).startsWith("<b>뉴스</b>\n\n");
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
                .startsWith("<b>모르는 명령</b>\n\n")
                .contains("/news")
                .as("도움말 제목까지 딸려 오면 굵은 제목이 둘 연달아 찍힌다")
                .doesNotContain("<b>사용할 수 있는 명령</b>");
    }

    @Test
    @DisplayName("안내·오류 답도 예외 없이 굵은 제목으로 시작한다")
    void everyFailureReplyCarriesItsSectionTitle() {
        assertThat(MessageFormatter.noResults("금리")).startsWith("<b>뉴스</b>\n\n");
        assertThat(MessageFormatter.fxUnavailable()).startsWith("<b>환율</b>\n\n");
        assertThat(MessageFormatter.stockNotFound("없는종목")).startsWith("<b>증시</b>\n\n");
        assertThat(MessageFormatter.cryptoNotFound("없는코인")).startsWith("<b>코인</b>\n\n");
        assertThat(MessageFormatter.help()).startsWith("<b>사용할 수 있는 명령</b>");
    }

    // --- 등락률 -------------------------------------------------------------

    @Test
    @DisplayName("상승은 빨강, 하락은 파랑 — 텔레그램이 글자에 색을 못 입혀 이모지가 유일한 수단이다")
    void marksDirectionWithColour() {
        assertThat(MessageFormatter.formatStock(
                krStock("삼성전자", "268000", "4.89"), null)).contains("🔴 4.89%");
        assertThat(MessageFormatter.formatStock(
                krStock("삼성전자", "268000", "-1.45"), null)).contains("🔵 1.45%");
    }

    @Test
    @DisplayName("부호를 원과 겹쳐 쓰지 않는다 — 원이 이미 방향이다")
    void doesNotRepeatDirectionWithASign() {
        assertThat(MessageFormatter.formatStock(krStock("삼성전자", "268000", "-1.45"), null))
                .doesNotContain("-1.45").doesNotContain("+1.45");
    }

    @Test
    @DisplayName("보합은 원을 붙이지 않는다 — 방향이 없는 값에 방향 표시를 붙일 이유가 없다")
    void showsFlatWithoutAColour() {
        assertThat(MessageFormatter.formatStock(krStock("삼성전자", "268000", "0"), null))
                .contains("0.00%").doesNotContain("🔴").doesNotContain("🔵");
    }

    @Test
    @DisplayName("등락률을 못 구하면 표시만 빠지고 시세는 그대로 나간다")
    void omitsUnknownChangeButKeepsThePrice() {
        String message = MessageFormatter.formatStock(krStock("삼성전자", "268000", null), null);

        assertThat(message).contains("268,000 KRW")
                .doesNotContain("%").doesNotContain("🔴").doesNotContain("🔵");
    }

    @Test
    @DisplayName("출처마다 자릿수가 달라도 소수 둘째 자리로 맞춘다")
    void roundsToTwoDecimalsRegardlessOfSource() {
        // FMP는 0.99586, 바이낸스는 -1.451, 공공데이터포털은 4.89로 준다
        assertThat(MessageFormatter.formatStock(krStock("A", "100", "0.99586"), null))
                .contains("🔴 1.00%");
        assertThat(MessageFormatter.formatStock(krStock("B", "100", "-1.451"), null))
                .contains("🔵 1.45%");
    }

    @Test
    @DisplayName("환율도 등락률을 단다 — 세 통이 같은 밀도여야 한다")
    void fxCarriesItsChangeToo() {
        FxRate rate = new FxRate("USD", "KRW", new BigDecimal("1414.90"),
                new BigDecimal("-0.01"), FxSource.KEXIM, BASIS);

        assertThat(MessageFormatter.formatFx(rate)).contains("1,414.9 KRW").contains("🔵 0.01%");
    }

    @Test
    @DisplayName("등락률이 없으면 줄 자체를 빼고 빈 줄을 남기지 않는다")
    void leavesNoBlankLineWhereTheChangeWouldBe() {
        FxRate rate = new FxRate("USD", "KRW", new BigDecimal("1414.90"), FxSource.KEXIM, BASIS);

        assertThat(MessageFormatter.formatFx(rate))
                .as("덩어리 사이는 빈 줄 하나다 — 두 줄이면 자리가 비어 보인다")
                .doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("코인은 거래소마다 등락률이 따로다 — 업비트와 바이낸스는 다른 시장이다")
    void cryptoCarriesPerExchangeChange() {
        CryptoQuote quote = new CryptoQuote("비트코인", "KRW-BTC", NOW,
                CryptoQuote.Quote.of(new BigDecimal("88922000"), new BigDecimal("-0.71")),
                CryptoQuote.Quote.of(new BigDecimal("62910"), new BigDecimal("-1.451")));

        assertThat(MessageFormatter.formatCrypto(quote, null))
                .contains("업비트 88,922,000 KRW · 🔵 0.71%")
                .contains("바이낸스 62,910 USDT · 🔵 1.45%");
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
                <b>증시</b>

                <b>국내</b>
                코스피 6,345.53
                삼성전자 239,500 KRW

                <b>미국</b>
                나스닥 26,588.49
                애플 302.25 USD · 426,828 KRW

                국내 2026년 8월 11일 (종가)
                미국 2026년 8월 13일 07:00:00""");
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
                        <b>증시</b>

                        <b>국내</b>
                        삼성전자 239,500 KRW

                        국내 2026년 8월 11일 (종가)""");
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
        StockQuote stale = new StockQuote(null, "코스닥", "KOSDAQ시리즈", new BigDecimal("857.84"),null, 
                StockQuote.Money.NONE, BASIS.minus(java.time.Duration.ofDays(1)),
                false, true, BigDecimal.ZERO);

        assertThat(MessageFormatter.formatStockDigest(List.of(krIndex("코스피", "6345.53"), stale), FX))
                .as("무리 기준은 맨 밑에 단독으로 — 모든 통이 같은 자리에 둔다")
                .endsWith("국내 2026년 8월 11일 (종가)")
                .as("맨 밑 기준이 대표하지 못하는 줄에만 날짜를 붙인다")
                .contains("코스닥 857.84 · 2026년 8월 10일")
                .as("기준과 같은 줄에는 안 붙인다").contains("코스피 6,345.53\n");
    }

    @Test
    @DisplayName("검색 결과도 달러와 원화를 함께 보여준다")
    void formatsUsStockWithKrw() {
        String message = MessageFormatter.formatStock(
                usStock("애플", "AAPL", "302.25"), FX);

        assertThat(message).isEqualTo("""
                <b>애플</b>

                302.25 USD
                약 426,828 KRW

                2026년 8월 13일 07:00:00""");
        assertThat(message).as("굵게는 제목에만 — 값까지 굵으면 무엇이 계층인지 안 드러난다")
                .doesNotContain("<b>302.25");
    }

    private static StockQuote krIndex(String name, String price) {
        return new StockQuote(null, name, "KOSPI시리즈", new BigDecimal(price),null, 
                StockQuote.Money.NONE, BASIS, false, true, BigDecimal.ZERO);
    }

    private static StockQuote krStock(String name, String price) {
        return krStock(name, price, null);
    }

    /** @param change 등락률(%) 문자열. {@code null}이면 "못 구했다"는 뜻이다 */
    private static StockQuote krStock(String name, String price, String change) {
        return new StockQuote("005930", name, "KOSPI", new BigDecimal(price),
                change == null ? null : new BigDecimal(change),
                StockQuote.Money.KRW, BASIS, false, false, new BigDecimal("1400183726616000"));
    }

    private static StockQuote usIndex(String name, String price) {
        return new StockQuote("^IXIC", name, "", new BigDecimal(price),null, 
                StockQuote.Money.NONE, US_AT, true, true, BigDecimal.ZERO);
    }

    private static StockQuote usStock(String name, String symbol, String price) {
        return new StockQuote(symbol, name, "NASDAQ", new BigDecimal(price),null, 
                StockQuote.Money.USD, US_AT, true, false, new BigDecimal("4439253351000"));
    }

    // --- 뉴스 날짜 ---------------------------------------------------------

    @Test
    @DisplayName("브리핑의 뉴스에도 같은 일시가 붙는다 — 두 채널이 다른 모양이 되면 안 된다")
    void showsPublishedTimeInDigestToo() {
        assertThat(MessageFormatter.formatNews(List.of(item("유가 상승", "본문", true))))
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
                new CryptoQuote("비트코인", "KRW-BTC", NOW,
                        Quote.of(new BigDecimal("89848000"), null), Quote.FAILED), null))
                .as("잠시 뒤 다시 치면 되는 것").contains("바이낸스 조회 실패");
    }

    @Test
    @DisplayName("업비트에 없는 코인은 제목이 티커이고 꼬리표에 마켓 코드가 없다")
    void showsBinanceOnlyCoin() {
        String message = MessageFormatter.formatCrypto(
                new CryptoQuote("BNB", null, NOW,
                        Quote.NOT_LISTED, Quote.of(new BigDecimal("612.40"), null)),
                new BigDecimal("1384"));

        assertThat(message)
                .contains("<b>BNB</b>")
                .contains("업비트 미상장")
                .contains("바이낸스 612.4 USDT")
                .as("BNBUSDT는 티커에 USDT를 붙인 것뿐이라 제목과 같은 말을 두 번 적는 셈이다")
                .doesNotContain("BNBUSDT");
    }

    @Test
    @DisplayName("이름과 값과 시각뿐이다 — 마켓 코드도 이모지도 적지 않는다")
    void showsOnlyNameValueAndTime() {
        String message = MessageFormatter.formatCrypto(btc(new BigDecimal("63703.69")), null);

        assertThat(message).isEqualTo("""
                <b>비트코인</b>

                업비트 89,848,000 KRW
                바이낸스 63,703.69 USDT

                2026년 8월 11일 09:00:00""");
        assertThat(message).as("들여쓰기를 쓰지 않는다 — 통마다 제각각이던 것을 하나로 맞췄다")
                .doesNotContain("  업비트");
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
                        new CryptoQuote("이더리움", "KRW-ETH", NOW,
                                Quote.FAILED, Quote.FAILED)),
                new BigDecimal("1384"));

        assertThat(message).contains("비트코인").doesNotContain("이더리움");
    }

    private static CryptoQuote btc(BigDecimal binanceUsdt) {
        return new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("89848000"), null),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(binanceUsdt, null));
    }

    /** 바이낸스에 {@code USDTUSDT}가 없다 — 브리핑 기본 설정에 들어 있어 실제로 밟는 길이다. */
    private static CryptoQuote usdt() {
        return new CryptoQuote("테더", "KRW-USDT", NOW,
                Quote.of(new BigDecimal("1384"), null), Quote.NOT_LISTED);
    }

    private static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem(NewsSource.CNBC, "CNBC", title, body,
                "https://example.com/a", NOW, translated, 0.9);
    }
}
