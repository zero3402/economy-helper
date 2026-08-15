package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoQuote.Quote;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxSource;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
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
    /** 김프 산수를 눈으로 검산하려고 소수점을 턴 환율. */
    private static final FxRate FX_FLAT = new FxRate("USD", "KRW", new BigDecimal("1412.00"),
            FxSource.FRANKFURTER, BASIS);

    @Test
    @DisplayName("제목 / 값 / 출처 / 시각 — 다른 통과 같은 순서, 시각은 맨 밑 단독")
    void followsTheSameSkeletonAsEveryOtherSection() {
        assertThat(MessageFormatter.formatNews(List.of(item("유가 상승", "인플레이션 우려.", true))))
                .singleElement().asString().isEqualTo("""
                        <b>뉴스</b>

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

    // --- 뉴스: 기사마다 한 통 ------------------------------------------------

    @Test
    @DisplayName("여러 건은 기사마다 통을 쪼갠다 — 묶으면 첫 기사 카드가 마지막 기사 것처럼 보인다")
    void splitsEachArticleIntoItsOwnMessage() {
        List<String> messages = MessageFormatter.formatNews(List.of(
                item("첫 번째", "본문1", true),
                item("두 번째", "본문2", true),
                item("세 번째", "본문3", true)));

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0))
                .startsWith("<b>뉴스 1/3</b>\n\n").contains("첫 번째")
                .as("한 통에 링크가 하나뿐이어야 카드가 어느 기사 것인지 확정된다")
                .doesNotContain("두 번째").doesNotContain("세 번째")
                .as("통마다 자기 매체와 발행 시각으로 끝맺는다")
                .endsWith("CNBC\n\n2026년 8월 11일 09:00:00");
        assertThat(messages.get(1)).startsWith("<b>뉴스 2/3</b>\n\n").contains("두 번째");
        assertThat(messages.get(2)).startsWith("<b>뉴스 3/3</b>\n\n").contains("세 번째");
    }

    @Test
    @DisplayName("한 건뿐이면 외로운 1/1을 붙이지 않는다")
    void doesNotNumberASingleArticle() {
        List<String> messages = MessageFormatter.formatNews(List.of(item("유일한 기사", "본문", true)));

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).startsWith("<b>뉴스</b>\n\n").doesNotContain("1/1");
    }

    @Test
    @DisplayName("수집 결과가 하나도 없으면 그 사실을 알린다")
    void tellsUserWhenDigestIsEmpty() {
        assertThat(MessageFormatter.formatNews(List.of()))
                .singleElement().asString().contains("가져올 수 있는 뉴스가 없습니다");
    }

    @Test
    @DisplayName("브리핑의 뉴스에도 같은 일시가 붙는다 — 두 채널이 다른 모양이 되면 안 된다")
    void showsPublishedTimeInDigestToo() {
        assertThat(MessageFormatter.formatNews(List.of(item("유가 상승", "본문", true))))
                .singleElement().asString().contains("2026년 8월 11일 09:00:00");
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
        assertThat(stock(krStock("삼성전자", "268000", "4.89"))).contains("🔴 +4.89%");
        assertThat(stock(krStock("삼성전자", "268000", "-1.45"))).contains("🔵 -1.45%");
    }

    @Test
    @DisplayName("부호를 숫자 옆에 붙인다 — 이모지가 안 뜨는 기기에서는 그것만이 방향이다")
    void signsEveryChange() {
        assertThat(stock(krStock("삼성전자", "268000", "4.89"))).contains("+4.89%");
        assertThat(stock(krStock("삼성전자", "268000", "-1.45"))).contains("-1.45%");
    }

    @Test
    @DisplayName("보합은 원도 부호도 붙이지 않는다 — 방향이 없는 값이다")
    void showsFlatWithoutAColour() {
        assertThat(stock(krStock("삼성전자", "268000", "0")))
                .contains("0.00%").doesNotContain("🔴").doesNotContain("🔵")
                .doesNotContain("+0.00").doesNotContain("-0.00");
    }

    @Test
    @DisplayName("등락률을 못 구하면 표시만 빠지고 시세는 그대로 나간다")
    void omitsUnknownChangeButKeepsThePrice() {
        assertThat(stock(krStock("삼성전자", "268000", null)))
                .contains("268,000 KRW")
                .doesNotContain("%").doesNotContain("🔴").doesNotContain("🔵");
    }

    @Test
    @DisplayName("출처마다 자릿수가 달라도 소수 둘째 자리로 맞춘다")
    void roundsToTwoDecimalsRegardlessOfSource() {
        // FMP는 0.99586, 바이낸스는 -1.451, 공공데이터포털은 4.89로 준다
        assertThat(stock(krStock("A", "100", "0.99586"))).contains("🔴 +1.00%");
        assertThat(stock(krStock("B", "100", "-1.451"))).contains("🔵 -1.45%");
    }

    @Test
    @DisplayName("환율도 값 다음에 출처, 한 줄 띄고 기준이다 — 네 통이 같은 순서다")
    void fxCarriesItsChangeToo() {
        FxRate rate = new FxRate("USD", "KRW", new BigDecimal("1414.90"),
                new BigDecimal("-0.01"), FxSource.KEXIM, BASIS);

        assertThat(MessageFormatter.formatFx(rate)).isEqualTo("""
                <b>환율</b>

                1 USD = 1,414.9 KRW
                🔵 -0.01%

                수출입은행 매매기준율

                2026년 8월 11일 (고시)""");
    }

    @Test
    @DisplayName("등락률이 없으면 줄 자체를 빼고 빈 줄을 남기지 않는다")
    void leavesNoBlankLineWhereTheChangeWouldBe() {
        FxRate rate = new FxRate("USD", "KRW", new BigDecimal("1414.90"), FxSource.KEXIM, BASIS);

        assertThat(MessageFormatter.formatFx(rate))
                .as("덩어리 사이는 빈 줄 하나다 — 두 줄이면 자리가 비어 보인다")
                .doesNotContain("\n\n\n");
    }

    // --- 증시 ---------------------------------------------------------------

    @Test
    @DisplayName("국내와 미국을 무리로 갈라 각각 조회처와 기준을 밝힌다")
    void stockSeparatesByFreshness() {
        String message = MessageFormatter.formatStock(List.of(
                krIndex("코스피", "6345.53"),
                krStock("삼성전자", "239500"),
                usIndex("나스닥", "26588.49"),
                usStock("애플", "AAPL", "302.25")), FX);

        assertThat(message).isEqualTo("""
                <b>증시</b>

                <b>국내</b>

                코스피
                6,345.53

                삼성전자
                239,500 KRW

                금융위원회

                2026년 8월 11일 (종가)

                <b>미국</b>

                나스닥
                26,588.49

                애플
                302.25 USD
                426,828 KRW

                Financial Modeling Prep

                2026년 8월 13일 07:00:00""");
        assertThat(message)
                .as("복사 버튼이 붙는 코드 블록을 쓰지 않는다").doesNotContain("<pre>")
                .as("환율은 바로 앞 환율 통에 이미 있다 — 여기 또 넣으면 중복이다")
                .doesNotContain("1 USD =");
    }

    @Test
    @DisplayName("검색 답도 브리핑과 같은 함수를 쓴다 — 종목이 하나뿐인 통일 뿐이다")
    void singleStockLooksExactlyLikeItsDigestBlock() {
        String single = MessageFormatter.formatStock(List.of(usStock("애플", "AAPL", "302.25")), FX);

        assertThat(single).isEqualTo("""
                <b>증시</b>

                <b>미국</b>

                애플
                302.25 USD
                426,828 KRW

                Financial Modeling Prep

                2026년 8월 13일 07:00:00""");
        assertThat(single).as("굵게는 제목에만 — 값까지 굵으면 무엇이 계층인지 안 드러난다")
                .doesNotContain("<b>302.25");
    }

    @Test
    @DisplayName("한쪽만 있으면 그 무리만 나온다 — 없는 무리 자리에 제목을 남기지 않는다")
    void stockOmitsEmptyGroup() {
        assertThat(MessageFormatter.formatStock(List.of(krStock("삼성전자", "239500")), FX))
                .isEqualTo("""
                        <b>증시</b>

                        <b>국내</b>

                        삼성전자
                        239,500 KRW

                        금융위원회

                        2026년 8월 11일 (종가)""");
    }

    @Test
    @DisplayName("지수에는 원화 환산을 붙이지 않는다 — 지수는 통화가 아니다")
    void stockNeverConvertsIndices() {
        assertThat(MessageFormatter.formatStock(List.of(usIndex("나스닥", "26588.49")), FX))
                .contains("26,588.49").doesNotContain("KRW");
    }

    @Test
    @DisplayName("환율이 없으면 달러만 보낸다 — 환산을 못 한다고 시세를 빼면 안 된다")
    void stockFallsBackToUsdWithoutFx() {
        assertThat(MessageFormatter.formatStock(List.of(usStock("애플", "AAPL", "302.25")), null))
                .contains("302.25 USD").doesNotContain("KRW").doesNotContain("1 USD =");
    }

    @Test
    @DisplayName("무리 안에서 기준일이 어긋나면 묵은 줄에 날짜를 붙인다 — 조용히 섞으면 거짓말이 된다")
    void stockMarksStaleLines() {
        StockQuote stale = new StockQuote(null, "코스닥", "KOSDAQ시리즈", new BigDecimal("857.84"), null,
                StockQuote.Money.NONE, StockSource.DATA_GO,
                BASIS.minus(java.time.Duration.ofDays(1)), false, true, BigDecimal.ZERO);

        assertThat(MessageFormatter.formatStock(List.of(krIndex("코스피", "6345.53"), stale), FX))
                .as("무리 기준은 그 무리 끝에 단독으로 — 모든 통이 같은 순서다")
                .endsWith("금융위원회\n\n2026년 8월 11일 (종가)")
                .as("무리 기준이 대표하지 못하는 줄에만 날짜를 붙인다")
                .contains("코스닥\n857.84 · 2026년 8월 10일")
                .as("종목끼리는 빈 줄로 갈린다 — 코인 통과 같은 규칙이다")
                .contains("코스피\n6,345.53\n\n코스닥");
    }

    @Test
    @DisplayName("조회처는 무리마다 그 무리 끝에 단다 — 증시만 출처 자리가 비어 있었다")
    void stockNamesItsVendor() {
        String message = MessageFormatter.formatStock(List.of(
                krStock("삼성전자", "239500"), usStock("애플", "AAPL", "302.25")), FX);

        assertThat(message)
                .contains("239,500 KRW\n\n금융위원회\n\n2026년 8월 11일 (종가)")
                .contains("426,828 KRW\n\nFinancial Modeling Prep\n\n2026년 8월 13일 07:00:00")
                .as("무리 이름을 접두사로 반복하지 않는다 — 꼬리가 이미 그 무리 안에 있다")
                .doesNotContain("국내 금융위원회").doesNotContain("미국 Financial");
    }

    // --- 코인: 업비트 + 바이낸스 + 김프 --------------------------------------

    @Test
    @DisplayName("업비트 다음에 바이낸스, 그 아래 원화 환산 — 환산은 환율로 한다")
    void showsBothExchangesInOrder() {
        String message = crypto(btc(new BigDecimal("62000")), FX_FLAT);

        assertThat(message.indexOf("업비트")).isLessThan(message.indexOf("바이낸스"));
        assertThat(message)
                .contains("89,848,000 KRW")
                .contains("62,000 USDT")
                // 62,000 × 1,412.00 = 87,544,000
                .contains("87,544,000 KRW");
    }

    @Test
    @DisplayName("김프는 업비트 값을 환율 환산값으로 나눈 것이다 — 화면의 두 원화값이 곧 검산이다")
    void showsKimchiPremium() {
        // 89,848,000 ÷ (62,000 × 1,412.00 = 87,544,000) − 1 = +2.63%
        assertThat(crypto(btc(new BigDecimal("62000")), FX_FLAT))
                .as("김프도 거래소 블록과 같은 모양이다 — 라벨 윗줄, 값 아랫줄")
                .contains("\n\n김프\n🔴 +2.63%");
    }

    @Test
    @DisplayName("역프면 파랑에 음수다 — 국내가 더 싸다는 뜻이다")
    void showsNegativePremium() {
        CryptoQuote cheap = new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("85000000"), null),
                Quote.of(new BigDecimal("62000"), null));

        // 85,000,000 ÷ 87,544,000 − 1 = -2.9059…%
        assertThat(crypto(cheap, FX_FLAT)).contains("김프\n🔵 -2.91%");
    }

    @Test
    @DisplayName("환율이 없으면 원화 환산도 김프도 빠지고 시세만 나간다")
    void omitsPremiumWithoutFx() {
        assertThat(crypto(btc(new BigDecimal("62000")), null))
                .contains("62,000 USDT").doesNotContain("김프");
    }

    @Test
    @DisplayName("한쪽 거래소만 있으면 김프를 계산하지 않는다 — 비교 대상이 없다")
    void omitsPremiumWithOneExchange() {
        assertThat(crypto(btc(null), FX_FLAT)).doesNotContain("김프");
    }

    @Test
    @DisplayName("테더는 바이낸스 USDTUSD라 값이 USD고 원화는 환율로 만든다 — 미상장이 아니다")
    void showsTetherInUsd() {
        String message = crypto(usdt(), FX_FLAT);

        assertThat(message)
                .contains("<b>USDT</b>")
                .as("USDTUSDT가 없다고 미상장으로 찍던 자리다")
                .doesNotContain("미상장")
                .contains("바이낸스\n0.99906 USD")
                // 0.99906 × 1,412.00 = 1,410.67272 → 1,411
                .contains("1,411 KRW")
                // 1,425 ÷ 1,410.67272 − 1 = +1.0157…%
                .contains("김프\n🔴 +1.02%");
    }

    @Test
    @DisplayName("코인은 거래소마다 등락률이 따로다 — 업비트와 바이낸스는 다른 시장이다")
    void cryptoCarriesPerExchangeChange() {
        CryptoQuote quote = new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("88922000"), new BigDecimal("-0.71")),
                Quote.of(new BigDecimal("62910"), new BigDecimal("-1.451")));

        assertThat(crypto(quote, null))
                .contains("업비트\n88,922,000 KRW\n🔵 -0.71%")
                .contains("바이낸스\n62,910 USDT\n🔵 -1.45%")
                .as("블록 안은 붙이고 블록 사이만 띄운다")
                .contains("🔵 -0.71%\n\n바이낸스");
    }

    @Test
    @DisplayName("값이 없는 거래소는 이유를 적는다 — 다시 시도해야 하는지가 여기서 갈린다")
    void explainsMissingExchange() {
        assertThat(crypto(btc(null), null))
                .contains("업비트\n89,848,000 KRW")
                .as("영영 안 나오는 것").contains("바이낸스 미상장");

        assertThat(crypto(new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("89848000"), null), Quote.FAILED), null))
                .as("잠시 뒤 다시 치면 되는 것").contains("바이낸스 조회 실패");
    }

    @Test
    @DisplayName("코인 제목은 굵은 티커에 한글 이름을 곁들인다 — 티커만으로는 무엇인지 모른다")
    void namesEachCoinInKorean() {
        assertThat(crypto(btc(new BigDecimal("62000")), null)).contains("<b>BTC</b> 비트코인");
        assertThat(crypto(usdt(), FX_FLAT)).contains("<b>USDT</b> 테더");
    }

    @Test
    @DisplayName("업비트에 없는 코인은 이름 자리가 티커이고 심볼을 따로 적지 않는다")
    void showsBinanceOnlyCoin() {
        String message = crypto(new CryptoQuote("BNB", null, NOW,
                Quote.NOT_LISTED, Quote.of(new BigDecimal("612.40"), null)), null);

        assertThat(message)
                .contains("<b>BNB</b>")
                .as("한글 이름을 확인할 곳이 없어 티커가 그대로 담긴다 — 그대로 두면 'BNB BNB'가 된다")
                .doesNotContain("BNB</b> BNB")
                .contains("업비트 미상장")
                .contains("바이낸스\n612.4 USDT")
                .as("BNBUSDT는 티커에 USDT를 붙인 것뿐이라 같은 말을 두 번 적는 셈이다")
                .doesNotContain("BNBUSDT");
    }

    @Test
    @DisplayName("검색 답도 브리핑과 같은 함수를 쓴다 — 코인이 하나뿐인 통일 뿐이다")
    void singleCryptoLooksExactlyLikeItsDigestBlock() {
        String message = crypto(btc(new BigDecimal("62000")), null);

        assertThat(message).isEqualTo("""
                <b>코인</b>

                <b>BTC</b> 비트코인

                업비트
                89,848,000 KRW

                바이낸스
                62,000 USDT

                2026년 8월 11일 09:00:00""");
        assertThat(message).as("들여쓰기를 쓰지 않는다 — 통마다 제각각이던 것을 하나로 맞췄다")
                .doesNotContain("  업비트");
    }

    @Test
    @DisplayName("블록 사이는 전부 빈 줄이고 코인 경계는 굵은 티커가 진다")
    void separatesEveryBlockWithABlankLine() {
        String message = MessageFormatter.formatCrypto(
                List.of(btc(new BigDecimal("62000")), usdt()), FX_FLAT);

        assertThat(message)
                .as("굵은 제목 다음도 빈 줄이다")
                .contains("<b>BTC</b> 비트코인\n\n업비트")
                .as("거래소 블록끼리도 빈 줄로 갈린다")
                .contains("89,848,000 KRW\n\n바이낸스")
                .as("코인 경계는 굵은 티커가 진다 — 간격을 두 겹으로 쓰지 않는다")
                .contains("\n\n<b>USDT</b> 테더\n\n업비트");
    }

    @Test
    @DisplayName("값이 없는 코인도 빼지 않는다 — 진짜 장애면 알려야 한다")
    void keepsCoinWithNoPrice() {
        String message = MessageFormatter.formatCrypto(
                List.of(btc(new BigDecimal("62000")),
                        new CryptoQuote("이더리움", "KRW-ETH", NOW, Quote.FAILED, Quote.FAILED)),
                FX_FLAT);

        assertThat(message).contains("<b>BTC</b>")
                .contains("<b>ETH</b>")
                .contains("업비트 조회 실패");
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private static String stock(StockQuote quote) {
        return MessageFormatter.formatStock(List.of(quote), null);
    }

    private static String crypto(CryptoQuote quote, FxRate fx) {
        return MessageFormatter.formatCrypto(List.of(quote), fx);
    }

    private static StockQuote krIndex(String name, String price) {
        return new StockQuote(null, name, "KOSPI시리즈", new BigDecimal(price), null,
                StockQuote.Money.NONE, StockSource.DATA_GO, BASIS, false, true, BigDecimal.ZERO);
    }

    private static StockQuote krStock(String name, String price) {
        return krStock(name, price, null);
    }

    /** @param change 등락률(%) 문자열. {@code null}이면 "못 구했다"는 뜻이다 */
    private static StockQuote krStock(String name, String price, String change) {
        return new StockQuote("005930", name, "KOSPI", new BigDecimal(price),
                change == null ? null : new BigDecimal(change),
                StockQuote.Money.KRW, StockSource.DATA_GO, BASIS, false, false,
                new BigDecimal("1400183726616000"));
    }

    private static StockQuote usIndex(String name, String price) {
        return new StockQuote("^IXIC", name, "", new BigDecimal(price), null,
                StockQuote.Money.NONE, StockSource.FMP, US_AT, true, true, BigDecimal.ZERO);
    }

    private static StockQuote usStock(String name, String symbol, String price) {
        return new StockQuote(symbol, name, "NASDAQ", new BigDecimal(price), null,
                StockQuote.Money.USD, StockSource.FMP, US_AT, true, false,
                new BigDecimal("4439253351000"));
    }

    private static CryptoQuote btc(BigDecimal binanceUsdt) {
        return new CryptoQuote("비트코인", "KRW-BTC", NOW,
                Quote.of(new BigDecimal("89848000"), null),
                binanceUsdt == null ? Quote.NOT_LISTED : Quote.of(binanceUsdt, null));
    }

    /** 테더는 바이낸스 호가가 USD다({@code USDTUSD}) — 2026-08-15 실측 0.99906. */
    private static CryptoQuote usdt() {
        return new CryptoQuote("테더", "KRW-USDT", NOW,
                Quote.of(new BigDecimal("1425"), null),
                Quote.of(new BigDecimal("0.99906"), null));
    }

    private static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem(NewsSource.CNBC, "CNBC", title, body,
                "https://example.com/a", NOW, translated, 0.9);
    }
}
