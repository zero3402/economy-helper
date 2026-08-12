package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.news.NewsSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageFormatterTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

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

    private static NewsItem item(String title, String body, boolean translated) {
        return new NewsItem(NewsSource.BLOOMBERG, "Bloomberg", title, body,
                "https://example.com/a", NOW, translated, 0.9);
    }
}
