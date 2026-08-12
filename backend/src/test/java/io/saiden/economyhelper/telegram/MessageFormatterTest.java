package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

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
    private static final LocalDate BASIS = LocalDate.of(2026, 8, 11);

    @Test
    @DisplayName("매체명·제목·본문·링크를 담는다")
    void includesAllParts() {
        String message = MessageFormatter.format(item("유가 상승", "인플레이션 우려가 되살아났다.", true));

        assertThat(message)
                .contains("[Bloomberg]")
                .contains("유가 상승")
                .contains("인플레이션 우려가 되살아났다.")
                .contains("https://example.com/a");
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
    @DisplayName("정기 발송은 매체별 1건을 구분선으로 묶는다")
    void joinsDigestWithSeparator() {
        String message = MessageFormatter.formatDigest(List.of(
                item("첫 번째", "본문1", true),
                item("두 번째", "본문2", true)));

        assertThat(message).contains("첫 번째").contains("두 번째").contains("———");
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
    @DisplayName("증시 통은 지수를 먼저 놓고 빈 줄로 종목과 나눈다 — 지수에는 '원'을 붙이지 않는다")
    void stockDigestSeparatesIndicesFromStocks() {
        String message = MessageFormatter.formatStockDigest(List.of(
                index("코스피", "6345.53", BASIS),
                index("코스닥", "857.84", BASIS),
                stock("삼성전자", "239500", BASIS)));

        assertThat(message).isEqualTo("""
                📈 증시 (08-11 종가)

                코스피  6,345.53
                코스닥  857.84

                삼성전자  239,500원""");
    }

    @Test
    @DisplayName("한쪽만 있으면 빈 줄도 하나뿐이다 — 없는 무리 자리에 구멍을 남기지 않는다")
    void stockDigestOmitsEmptyGroup() {
        assertThat(MessageFormatter.formatStockDigest(List.of(stock("삼성전자", "239500", BASIS))))
                .isEqualTo("""
                        📈 증시 (08-11 종가)

                        삼성전자  239,500원""");

        assertThat(MessageFormatter.formatStockDigest(List.of(index("코스피", "6345.53", BASIS))))
                .isEqualTo("""
                        📈 증시 (08-11 종가)

                        코스피  6,345.53""");
    }

    @Test
    @DisplayName("지수와 종목의 기준일이 어긋나면 묵은 줄에 날짜를 붙인다 — 조용히 섞으면 거짓말이 된다")
    void stockDigestMarksStaleLines() {
        String message = MessageFormatter.formatStockDigest(List.of(
                index("코스피", "6345.53", BASIS.minusDays(1)),
                stock("삼성전자", "239500", BASIS)));

        assertThat(message)
                .contains("📈 증시 (08-11 종가)")
                .contains("코스피  6,345.53 (08-10)")
                .contains("삼성전자  239,500원")
                .doesNotContain("삼성전자  239,500원 (");
    }

    private static StockQuote index(String name, String price, LocalDate basis) {
        return new StockQuote(null, name, "KOSPI시리즈", new BigDecimal(price), basis, BigDecimal.ZERO);
    }

    private static StockQuote stock(String name, String price, LocalDate basis) {
        return new StockQuote("005930", name, "KOSPI", new BigDecimal(price), basis,
                new BigDecimal("1400183726616000"));
    }

    private static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem(NewsSource.BLOOMBERG, "Bloomberg", title, body,
                "https://example.com/a", NOW, translated, 0.9);
    }
}
