package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageFormattingTest {

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
        assertThat(NewsFormatter.formatAll(List.of(item("유가 상승", "인플레이션 우려.", true))))
                .singleElement().asString().isEqualTo("""
                        <b>뉴스</b>

                        <a href="https://example.com/a"><b>유가 상승</b></a>

                        <blockquote>인플레이션 우려.</blockquote>

                        CNBC

                        2026년 8월 11일(화) 09:00:00""");
    }

    @Test
    @DisplayName("번역 실패 시 왜 영문인지 알린다 — 안 그러면 고장으로 보인다")
    void explainsUntranslatedOutput() {
        String message = NewsFormatter.format(item("Oil holds advance", "Oil kept its gains.", false));

        assertThat(message).contains("번역이 일시적으로 불가");
    }

    @Test
    @DisplayName("출처에 든 TLD를 끊는다 — 안 그러면 텔레그램이 매체 홈페이지 링크를 스스로 만든다")
    void keepsTelegramFromLinkifyingTheSourceName() {
        String message = NewsFormatter.format(new NewsItem(
                "Investing.com", "버거 시장", "", "https://www.investing.com/news/a", NOW, true));

        assertThat(message)
                .as("눈에 보이는 글자는 그대로다 — 폭 없는 문자만 점 앞에 끼어든다")
                .contains("Investing⁠.com")
                .as("링크로 알아볼 수 있는 맨 이름이 남으면 안 된다")
                .doesNotContain("\n\nInvesting.com")
                .as("기사 링크는 그대로 하나뿐이다")
                .contains("<a href=\"https://www.investing.com/news/a\">");
    }

    @Test
    @DisplayName("TLD가 없는 매체 이름은 손대지 않는다")
    void leavesPlainSourceNamesAlone() {
        assertThat(NewsFormatter.format(item("Fed signals cut", "", true)))
                .contains("\n\nCNBC\n\n").doesNotContain("⁠");
    }

    @Test
    @DisplayName("요약문이 없는 기사(AP)는 본문 자리를 비워 둔다")
    void handlesEmptyBody() {
        String message = NewsFormatter.format(item("Fed signals cut", "", true));

        assertThat(message).contains("Fed signals cut").doesNotContain("\n\n\n");
    }

    // --- 뉴스: 기사마다 한 통 ------------------------------------------------

    @Test
    @DisplayName("여러 건은 기사마다 통을 쪼갠다 — 묶으면 첫 기사 카드가 마지막 기사 것처럼 보인다")
    void splitsEachArticleIntoItsOwnMessage() {
        List<String> messages = NewsFormatter.formatAll(List.of(
                item("첫 번째", "본문1", true),
                item("두 번째", "본문2", true),
                item("세 번째", "본문3", true)));

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0))
                .startsWith("<b>뉴스 1/3</b>\n\n").contains("첫 번째")
                .as("한 통에 링크가 하나뿐이어야 카드가 어느 기사 것인지 확정된다")
                .doesNotContain("두 번째").doesNotContain("세 번째")
                .as("통마다 자기 매체와 발행 시각으로 끝맺는다")
                .endsWith("CNBC\n\n2026년 8월 11일(화) 09:00:00");
        assertThat(messages.get(1)).startsWith("<b>뉴스 2/3</b>\n\n").contains("두 번째");
        assertThat(messages.get(2)).startsWith("<b>뉴스 3/3</b>\n\n").contains("세 번째");
    }

    @Test
    @DisplayName("한 건뿐이면 외로운 1/1을 붙이지 않는다")
    void doesNotNumberASingleArticle() {
        List<String> messages = NewsFormatter.formatAll(List.of(item("유일한 기사", "본문", true)));

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).startsWith("<b>뉴스</b>\n\n").doesNotContain("1/1");
    }

    @Test
    @DisplayName("수집 결과가 하나도 없으면 그 사실을 알린다")
    void tellsUserWhenDigestIsEmpty() {
        assertThat(NewsFormatter.formatAll(List.of()))
                .singleElement().asString().contains("가져올 수 있는 값이 없습니다");
    }

    @Test
    @DisplayName("검색 결과가 없을 때와 검색어가 빠졌을 때를 구분해 안내한다")
    void distinguishesNoResultsFromMissingQuery() {
        assertThat(NewsFormatter.noResults("금리", java.time.Duration.ofHours(24)))
                .contains("금리").contains("찾지 못했습니다")
                .as("왜 없는지 밝히지 않으면 봇 고장으로 읽힌다")
                .contains("최근 24시간");
        assertThat(HelpFormatter.usage(Command.NEWS)).contains("/news 금리");
        // 명령마다 예시가 달라야 한다 — 하나로 고정하면 /stock에 /news 예시가 뜬다
        assertThat(HelpFormatter.usage(Command.STOCK)).contains("/stock 삼성전자");
        // 실패·안내 답도 성공 답과 같은 제목을 인다. 그룹 채팅에서 맨몸 문장 하나만
        // 튀어나오면 무엇에 대한 답인지 알 수 없다
        assertThat(HelpFormatter.usage(Command.STOCK)).startsWith("<b>증시</b>\n\n");
        assertThat(HelpFormatter.usage(Command.NEWS)).startsWith("<b>뉴스</b>\n\n");
    }

    @Test
    @DisplayName("도움말은 모든 명령을 빠짐없이 싣는다")
    void helpListsEveryCommand() {
        String help = HelpFormatter.help();
        for (Command command : Command.values()) {
            assertThat(help).contains(command.example());
        }
    }

    @Test
    @DisplayName("도움말의 설명은 그 명령의 답 제목과 같은 말이다 — /stock을 '주식'이라 부르던 때가 있었다")
    void helpDescribesEachCommandWithItsOwnSectionName() {
        String help = HelpFormatter.help();
        for (Command command : Command.values()) {
            if (command == Command.HELP) {
                continue;   // 그 제목은 이 목록 자체의 제목이라 목록 안에서 같은 말을 두 번 하게 된다
            }
            String line = command.example()
                    + (command.shortToken() == null ? "" : " (또는 " + command.shortToken() + ")");
            assertThat(help)
                    .as("'%s'의 설명", line)
                    .contains(line + "\n" + command.section());
        }
        assertThat(help).as("답은 「증시」인데 도움말만 「주식」이면 같은 것을 두 이름으로 부르는 것이다")
                .doesNotContain("주식");
    }

    @Test
    @DisplayName("모르는 명령에는 도움말을 함께 준다 — 무엇을 칠 수 있는지 알려주지 않으면 고장으로 보인다")
    void unknownCommandIncludesHelp() {
        assertThat(HelpFormatter.unknownCommand())
                .startsWith("<b>모르는 명령</b>\n\n")
                .contains("/news")
                .as("도움말 제목까지 딸려 오면 굵은 제목이 둘 연달아 찍힌다")
                .doesNotContain("<b>사용할 수 있는 명령</b>");
    }

    @Test
    @DisplayName("안내·오류 답도 예외 없이 굵은 제목으로 시작한다")
    void everyFailureReplyCarriesItsSectionTitle() {
        // 못 찾음 답도 제목에 검색어를 싣는다 — 답글 인용이 접히면 통 제목만 남는데,
        // 어느 검색이 실패했는지 알아야 할 때 정확히 그 단서가 없었다
        assertThat(NewsFormatter.noResults("금리", java.time.Duration.ofHours(24)))
                .startsWith("<b>뉴스</b>\n\n");
        assertThat(FxFormatter.unavailable()).startsWith("<b>환율</b>\n\n");
        // 제목은 맨몸이다 — 답글 인용이 원 명령을 이미 보여 준다. 검색어는 본문에 남는다
        assertThat(StockFormatter.notFound("없는종목"))
                .startsWith("<b>증시</b>\n\n").contains("'없는종목'");
        assertThat(CryptoFormatter.notFound("없는코인"))
                .startsWith("<b>코인</b>\n\n").contains("'없는코인'");
        assertThat(WeatherFormatter.notFound("없는지역"))
                .startsWith("<b>날씨</b>\n\n").contains("'없는지역'");
        assertThat(HelpFormatter.help()).startsWith("<b>사용할 수 있는 명령</b>");
    }

    // --- 등락률 -------------------------------------------------------------

    @Test
    @DisplayName("상승은 빨강, 하락은 파랑 — 색이 유일한 수단인데, 부호도 함께 적어야 이모지가 "
            + "안 뜨는 기기에서 방향이 남는다")
    void marksDirectionWithColourAndSign() {
        assertThat(stock(krStock("삼성전자", "268000", "4.89"))).contains("🔴 +4.89%");
        assertThat(stock(krStock("삼성전자", "268000", "-1.45"))).contains("🔵 -1.45%");
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

        assertThat(FxFormatter.format(rate)).isEqualTo("""
                <b>환율</b>

                1 USD = 1,414.90 KRW
                🔵 -0.01%

                수출입은행 매매기준율

                2026년 8월 11일(화) (고시)""");
    }

    @Test
    @DisplayName("등락률이 없으면 줄 자체를 빼고 빈 줄을 남기지 않는다")
    void leavesNoBlankLineWhereTheChangeWouldBe() {
        FxRate rate = new FxRate("USD", "KRW", new BigDecimal("1414.90"), FxSource.KEXIM, BASIS);

        assertThat(FxFormatter.format(rate))
                .as("덩어리 사이는 빈 줄 하나다 — 두 줄이면 자리가 비어 보인다")
                .doesNotContain("\n\n\n");
    }

    // --- 증시 ---------------------------------------------------------------

    @Test
    @DisplayName("국내와 미국을 무리로 갈라 각각 조회처와 기준을 밝힌다")
    void stockSeparatesByFreshness() {
        String message = StockFormatter.format(List.of(
                krIndex("코스피", "6345.53"),
                krStock("삼성전자", "239500"),
                usIndex("나스닥", "26588.49"),
                usStock("애플", "302.25")), FX);

        assertThat(message).isEqualTo("""
                <b>증시</b>

                <b>국내</b>

                코스피
                6,345.53

                삼성전자
                239,500 KRW

                금융위원회

                2026년 8월 11일(화) (종가)

                <b>미국</b>

                나스닥
                26,588.49

                애플
                302.25 USD
                426,828 KRW

                Financial Modeling Prep

                2026년 8월 13일(목) 07:00:00""");
        assertThat(message)
                .as("복사 버튼이 붙는 코드 블록을 쓰지 않는다").doesNotContain("<pre>")
                .as("환율은 바로 앞 환율 통에 이미 있다 — 여기 또 넣으면 중복이다")
                .doesNotContain("1 USD =");
    }

    @Test
    @DisplayName("한국투자증권이 답하면 국내도 시각까지 찍힌다 — 이게 평상시 아침 브리핑이다")
    void domesticRealtimeStampsTheTimeNotAClosingDate() {
        String message = StockFormatter.format(List.of(
                kis("코스피", "6869.83", StockQuote.Money.NONE, StockQuote.Market.DOMESTIC),
                kis("삼성전자", "268500", StockQuote.Money.KRW, StockQuote.Market.DOMESTIC),
                kis("나스닥", "26644.91", StockQuote.Money.NONE, StockQuote.Market.US),
                kis("애플", "306.192", StockQuote.Money.USD, StockQuote.Market.US)), FX);

        assertThat(message)
                .as("2순위로 내려앉았을 때만 '(종가)'가 나와야 한다")
                .isEqualTo("""
                <b>증시</b>

                <b>국내</b>

                코스피
                6,869.83

                삼성전자
                268,500 KRW

                한국투자증권

                2026년 8월 13일(목) 07:00:00

                <b>미국</b>

                나스닥
                26,644.91

                애플
                306.192 USD
                432,395 KRW

                한국투자증권

                2026년 8월 13일(목) 07:00:00""");
    }

    @Test
    @DisplayName("검색 답도 브리핑과 같은 함수를 쓴다 — 종목이 하나뿐인 통일 뿐이다")
    void singleStockLooksExactlyLikeItsDigestBlock() {
        String single = StockFormatter.format(List.of(usStock("애플", "302.25")), FX);

        assertThat(single).isEqualTo("""
                <b>증시</b>

                <b>미국</b>

                애플
                302.25 USD
                426,828 KRW

                Financial Modeling Prep

                2026년 8월 13일(목) 07:00:00""");
        assertThat(single).as("굵게는 제목에만 — 값까지 굵으면 무엇이 계층인지 안 드러난다")
                .doesNotContain("<b>302.25");
    }

    @Test
    @DisplayName("한쪽만 있으면 그 무리만 나온다 — 없는 무리 자리에 제목을 남기지 않는다")
    void stockOmitsEmptyGroup() {
        assertThat(StockFormatter.format(List.of(krStock("삼성전자", "239500")), FX))
                .isEqualTo("""
                        <b>증시</b>

                        <b>국내</b>

                        삼성전자
                        239,500 KRW

                        금융위원회

                        2026년 8월 11일(화) (종가)""");
    }

    @Test
    @DisplayName("지수에는 원화 환산을 붙이지 않는다 — 지수는 통화가 아니다")
    void stockNeverConvertsIndices() {
        assertThat(StockFormatter.format(List.of(usIndex("나스닥", "26588.49")), FX))
                .contains("26,588.49").doesNotContain("KRW");
    }

    @Test
    @DisplayName("환율이 없으면 달러만 보낸다 — 환산을 못 한다고 시세를 빼면 안 된다")
    void stockFallsBackToUsdWithoutFx() {
        assertThat(StockFormatter.format(List.of(usStock("애플", "302.25")), null))
                .contains("302.25 USD").doesNotContain("KRW").doesNotContain("1 USD =");
    }

    @Test
    @DisplayName("무리 안에서 기준일이 어긋나면 묵은 줄에 날짜를 붙인다 — 조용히 섞으면 거짓말이 된다")
    void stockMarksStaleLines() {
        StockQuote stale = new StockQuote("코스닥", new BigDecimal("857.84"), null,
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.DATA_GO,
                BASIS.minus(java.time.Duration.ofDays(1)), false);

        assertThat(StockFormatter.format(List.of(krIndex("코스피", "6345.53"), stale), FX))
                .as("무리 기준은 그 무리 끝에 단독으로 — 모든 통이 같은 순서다")
                .endsWith("금융위원회\n\n2026년 8월 11일(화) (종가)")
                .as("무리 기준이 대표하지 못하는 줄에만 날짜를 붙인다")
                .contains("코스닥\n857.84 · 2026년 8월 10일(월)")
                .as("종목끼리는 빈 줄로 갈린다 — 코인 통과 같은 규칙이다")
                .contains("코스피\n6,345.53\n\n코스닥");
    }

    @Test
    @DisplayName("같은 날이면 초가 달라도 날짜를 붙이지 않는다 — 알람과 검색이 같은 모양이어야 한다")
    void stockDoesNotMarkLinesFromTheSameDay() {
        // 미국 무리는 심볼마다 제 FMP 체결 초를 들고 온다 — 넷이 같은 초일 리가 없다.
        // 예전에는 Instant를 그대로 비교해 가장 최근 것 하나만 빼고 전부 날짜가 붙었고,
        // 그 날짜는 맨 밑 기준 줄과 같은 날짜라 알려 주는 것이 없었다.
        String message = StockFormatter.format(List.of(
                usAt(usIndex("나스닥", "26588.49"), US_AT),
                usAt(usIndex("S&P 500", "6721.10"), US_AT.minusSeconds(37)),
                usAt(usStock("엔비디아", "184.10"), US_AT.minusSeconds(12)),
                usAt(usStock("애플", "302.25"), US_AT.minusSeconds(3))), FX);

        assertThat(message)
                .as("무리 기준은 한 번, 맨 아래에만 찍힌다")
                .endsWith("Financial Modeling Prep\n\n2026년 8월 13일(목) 07:00:00")
                .as("값 줄에는 날짜가 한 줄도 붙지 않는다 — 같은 날이기 때문이다")
                .doesNotContain(" · 2026년");
    }

    @Test
    @DisplayName("한 무리에 조회처가 둘이면 한 줄에 하나씩 내려 적는다 — 통마다 규칙이 달라지지 않는다")
    void stacksTwoSourcesInOneStockGroup() {
        // 국내 무리에 조회처가 둘인 상황이다 — KIS를 1순위로 붙이면 지수만 폴백해서 실제로 난다.
        // 출처만 다르고 시장·성격은 같게 둔다(둘 다 DOMESTIC·종가) — 여기서 보려는 것은
        // 출처 줄이 쌓이는지 하나뿐이고, 성격이 섞이는 경우는 아래 테스트가 따로 본다
        StockQuote second = new StockQuote("삼성전자", new BigDecimal("239500"),
                null, StockQuote.Money.KRW, StockQuote.Market.DOMESTIC,
                StockSource.FMP, BASIS, false);

        assertThat(StockFormatter.format(List.of(krStock("코스피", "6345.53"), second), FX))
                .as("출처는 여럿이어도 블록 하나다 — 사이를 빈 줄로 벌리지 않는다")
                .contains("금융위원회\nFinancial Modeling Prep\n\n2026년")
                .as("한 줄에 잇지 않는다")
                .doesNotContain(" · ")
                .as("빈 줄이 겹치지 않는다")
                .doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("무리에 현재가와 종가가 섞이면 어긋난 줄에 그 성격을 밝힌다 — 꼬리가 대표하면 거짓말이 된다")
    void marksLinesWhoseFreshnessDiffersFromTheGroup() {
        // KIS(실시간)로 지수는 받았는데 종목은 폴백해 전일 종가가 온 상황이다.
        // 브리핑이 지수와 종목을 따로 조회하므로 실제로 난다
        StockQuote live = new StockQuote("코스피", new BigDecimal("6345.53"), null,
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.KIS,
                BASIS.plus(java.time.Duration.ofHours(9)), true);

        String message = StockFormatter.format(List.of(live, krStock("삼성전자", "239500")), FX);

        assertThat(message)
                .as("성격마다 한 줄 — 실시간이 위다. 출처를 쌓는 것과 같은 규칙이다")
                .endsWith("2026년 8월 11일(화) 09:00:00\n2026년 8월 11일(화) (종가)")
                .as("값 줄은 깨끗하게 둔다 — 넷 중 하나가 폴백했다고 값마다 꼬리표를 달지 않는다")
                .doesNotContain("KRW · ")
                .as("출처도 둘이 쌓인다")
                .contains("한국투자증권\n금융위원회");
    }

    // --- 날씨: 알람과 검색이 한 함수를 쓴다 ------------------------------------

    @Test
    @DisplayName("한 지역짜리 답이 알람 통의 그 지역 블록과 글자 그대로 같다 — 포매터가 갈리면 안 된다")
    void singleWeatherLooksExactlyLikeItsDigestBlock() {
        assertThat(WeatherFormatter.format(List.of(migeum()))).isEqualTo("""
                <b>날씨</b>

                <b>미금역</b>

                흐림
                18.2°C / 29.6°C
                강수확률 20%

                AccuWeather

                2026년 8월 17일(월) (예보)""");
    }

    @Test
    @DisplayName("알람은 지역마다 블록 하나 — 출처와 기준은 통 하나처럼 맨 아래에서 끝맺는다")
    void weatherAlarmStacksOneBlockPerPlace() {
        String message = WeatherFormatter.format(List.of(migeum(), seohyeon()));

        assertThat(message)
                .startsWith("<b>날씨</b>\n\n<b>미금역</b>")
                .as("지역끼리는 굵은 제목이 경계를 진다 — 증시의 국내·미국과 같은 규칙")
                .contains("강수확률 20%\n\n<b>서현역</b>")
                .as("다른 통과 같은 순서로 끝맺는다 — 값 다음 빈 줄, 출처, 빈 줄, 기준")
                .endsWith("AccuWeather\n\n2026년 8월 17일(월) (예보)");
    }

    @Test
    @DisplayName("여러 날은 하루가 블록 하나다 — 요일을 붙여야 '이번 주말'을 찾을 수 있다")
    void weatherSearchStacksOneBlockPerDay() {
        String message = WeatherFormatter.format(List.of(seongnamWeek()));

        assertThat(message)
                .startsWith("<b>날씨</b>")
                .as("온도는 소수 한 자리로 맞춘다 — 22°C와 30.5°C가 한 줄에 서면 정밀도가 갈린다")
                .contains("<b>성남시, 대한민국</b>\n\n8월 18일(화)\n흐림\n22.0°C / 30.5°C\n강수확률 49%")
                .contains("8월 19일(수)")
                .as("범위는 시작과 끝을 함께 적는다 — 연도는 한 번이면 된다")
                .endsWith("Open-Meteo\n\n2026년 8월 18일(화) ~ 8월 19일(수) (예보)");
    }

    @Test
    @DisplayName("출처가 갈리면 하단에 한 줄씩 내려 적는다 — 출처는 여럿이어도 블록 하나다")
    void stacksEveryDivergedSourceAtTheBottom() {
        String mixed = WeatherFormatter.format(List.of(migeum(), openMeteoFallback()));

        assertThat(mixed)
                .as("지역 블록에는 출처가 붙지 않는다 — 넷 중 하나가 폴백했다고 이름을 다섯 번 적지 않는다")
                .doesNotContain("2.4mm\nOpen-Meteo")
                .as("한 줄에 잇지도, 빈 줄로 벌리지도 않는다 — 출처는 여럿이어도 블록 하나다")
                .endsWith("AccuWeather\nOpen-Meteo\n\n2026년 8월 17일(월) (예보)")
                .as("빈 줄이 겹치지 않는다")
                .doesNotContain("\n\n\n");

        assertThat(WeatherFormatter.format(List.of(openMeteoFallback(), seohyeon())))
                .as("첫 지역이 폴백해도 1순위가 위다 — 등장 순이 아니라 이중화 순서로 적는다")
                .endsWith("AccuWeather\nOpen-Meteo\n\n2026년 8월 17일(월) (예보)");

        assertThat(WeatherFormatter.format(List.of(migeum(), seohyeon())))
                .as("갈리지 않으면 평상시 화면 그대로다 — 지역 블록에 출처가 붙지 않는다")
                .contains("강수확률 20%\n\n<b>서현역</b>")
                .endsWith("AccuWeather\n\n2026년 8월 17일(월) (예보)");
    }

    @Test
    @DisplayName("폴백이면 강수량으로 적는다 — 강수량을 확률이라 부르지 않는다")
    void weatherNamesWhateverTheSourceActuallyGave() {
        String message = WeatherFormatter.format(List.of(openMeteoFallback()));

        assertThat(message)
                .contains("강수량 2.4mm").doesNotContain("강수확률")
                .as("폴백이 일어난 사실을 출처 줄이 밝힌다")
                .contains("Open-Meteo");
    }

    @Test
    @DisplayName("지나간 날은 (실측)이다 — 예보가 아니었던 값을 예보라 적으면 거짓말이 된다")
    void pastWeatherIsMarkedAsMeasured() {
        assertThat(WeatherFormatter.format(List.of(archived())))
                .endsWith("Open-Meteo Archive\n\n2025년 8월 19일(화) (실측)");
    }

    @Test
    @DisplayName("하늘 상태를 모르면 그 줄만 빠진다 — 아무 날씨나 찍지 않는다")
    void weatherOmitsAnUnknownSky() {
        Weather unknown = new Weather(place("미금역", null),
                List.of(Weather.Daily.withChance(LocalDate.of(2026, 8, 17), SkyCondition.UNKNOWN,
                        new BigDecimal("18.2"), new BigDecimal("29.6"), 20)),
                WeatherSource.ACCU_WEATHER);

        assertThat(WeatherFormatter.format(List.of(unknown)))
                .contains("<b>미금역</b>\n\n18.2°C / 29.6°C");
    }

    private static Weather migeum() {
        return oneDay("미금역", null, SkyCondition.CLOUDY, "18.2", "29.6", 20);
    }

    private static Weather seohyeon() {
        return oneDay("서현역", null, SkyCondition.CLEAR, "19.0", "30.1", 10);
    }

    /** 평상시 경로 — 1순위 AccuWeather가 답한 하루. */
    private static Weather oneDay(String name, String country, SkyCondition sky,
                                  String low, String high, int chance) {
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

    /**
     * 폴백 — 2순위 Open-Meteo가 답한 하루.
     *
     * <p>확률이 아니라 강수량인 이유는 Open-Meteo가 {@code precipitation_probability_max}를
     * 안 줄 때 {@code precipitation_sum}으로 떨어지기 때문이다({@code DailyBlock.toDays}).
     * 값을 다른 것인 척하지 않는다는 규칙이 여기서 화면에 드러난다.
     */
    private static Weather openMeteoFallback() {
        return new Weather(place("미금역", null),
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
        return new GeoLocation(name, country, 37.35, 127.10889, java.time.ZoneId.of("Asia/Seoul"));
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
    @DisplayName("업비트에 없는 코인은 이름 자리가 티커이고 심볼을 따로 적지 않는다")
    void showsBinanceOnlyCoin() {
        String message = crypto(new CryptoQuote("BNB", null, NOW,
                Quote.NOT_LISTED, Quote.of(new BigDecimal("612.40"), null)), null);

        assertThat(message)
                .contains("<b>BNB</b>")
                .as("한글 이름을 확인할 곳이 없어 티커가 그대로 담긴다 — 그대로 두면 'BNB BNB'가 된다")
                .doesNotContain("BNB</b> BNB")
                .contains("업비트 미상장")
                .contains("바이낸스\n612.40 USDT")
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

                2026년 8월 11일(화) 09:00:00""");
        assertThat(message).as("들여쓰기를 쓰지 않는다 — 통마다 제각각이던 것을 하나로 맞췄다")
                .doesNotContain("  업비트");
    }

    @Test
    @DisplayName("블록 사이는 전부 빈 줄이고 코인 경계는 굵은 티커가 진다")
    void separatesEveryBlockWithABlankLine() {
        String message = CryptoFormatter.format(
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
        String message = CryptoFormatter.format(
                List.of(btc(new BigDecimal("62000")),
                        new CryptoQuote("이더리움", "KRW-ETH", NOW, Quote.FAILED, Quote.FAILED)),
                FX_FLAT);

        assertThat(message).contains("<b>BTC</b>")
                .contains("<b>ETH</b>")
                .contains("업비트 조회 실패");
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private static String stock(StockQuote quote) {
        return StockFormatter.format(List.of(quote), null);
    }

    private static String crypto(CryptoQuote quote, FxRate fx) {
        return CryptoFormatter.format(List.of(quote), fx);
    }

    private static StockQuote krIndex(String name, String price) {
        return new StockQuote(name, new BigDecimal(price), null,
                StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.DATA_GO, BASIS, false);
    }

    private static StockQuote krStock(String name, String price) {
        return krStock(name, price, null);
    }

    /** @param change 등락률(%) 문자열. {@code null}이면 "못 구했다"는 뜻이다 */
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

    /**
     * 한국투자증권이 답한 시세 — <b>국내도 미국도 실시간이고 시각은 '읽은 시각'이다.</b>
     * 이 출처는 시각 필드를 주지 않아 넷이 같은 초를 갖는다(브리핑이 한 번에 부른다).
     */
    private static StockQuote kis(String name, String price, StockQuote.Money currency,
                                  StockQuote.Market market) {
        return new StockQuote(name, new BigDecimal(price), null, currency, market,
                StockSource.KIS, US_AT, true);
    }

    /** 같은 종목의 시각만 바꾼다 — FMP가 심볼마다 제 체결 초를 주는 상황을 만든다. */
    private static StockQuote usAt(StockQuote quote, Instant at) {
        return new StockQuote(quote.name(), quote.price(), quote.changePercent(),
                quote.currency(), quote.market(), quote.source(), at, quote.realtime());
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
        return new NewsItem("CNBC", title, body, "https://example.com/a", NOW, translated);
    }
}
