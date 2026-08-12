package io.saiden.economyhelper.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandParserTest {

    @Test
    @DisplayName("명령과 인자를 갈라낸다")
    void extractsCommandAndArgument() {
        assertThat(CommandParser.parse("/news 금리"))
                .contains(new ParsedCommand(Command.NEWS, "금리"));
        assertThat(CommandParser.parse("  /stock   삼성 전자  "))
                .contains(new ParsedCommand(Command.STOCK, "삼성 전자"));
        assertThat(CommandParser.parse("/crypto 비트코인"))
                .contains(new ParsedCommand(Command.CRYPTO, "비트코인"));
    }

    @Test
    @DisplayName("인자가 없는 명령은 빈 인자로 파싱된다")
    void parsesArgumentlessCommands() {
        assertThat(CommandParser.parse("/fx"))
                .contains(new ParsedCommand(Command.FX, ""));
        assertThat(CommandParser.parse("/help"))
                .contains(new ParsedCommand(Command.HELP, ""));
    }

    @Test
    @DisplayName("그룹 채팅의 /명령@봇이름 형태에서 봇 이름을 뗀다")
    void stripsBotMention() {
        assertThat(CommandParser.parse("/news@economy_helper_bot 금리"))
                .contains(new ParsedCommand(Command.NEWS, "금리"));
        assertThat(CommandParser.parse("/fx@economy_helper_bot"))
                .contains(new ParsedCommand(Command.FX, ""));
    }

    @Test
    @DisplayName("인자가 필요한 명령을 인자 없이 부르면 명령은 알아보되 사용법 대상으로 표시한다")
    void flagsMissingRequiredArgument() {
        ParsedCommand news = CommandParser.parse("/news").orElseThrow();
        assertThat(news.hasArgument()).isFalse();
        assertThat(news.missingRequiredArgument()).isTrue();

        assertThat(CommandParser.parse("/news   ").orElseThrow().missingRequiredArgument()).isTrue();
        assertThat(CommandParser.parse("/stock").orElseThrow().missingRequiredArgument()).isTrue();

        // 인자가 필요 없는 명령은 인자가 없어도 정상이다
        assertThat(CommandParser.parse("/fx").orElseThrow().missingRequiredArgument()).isFalse();
    }

    @Test
    @DisplayName("일반 대화는 명령이 아니고, 모르는 명령으로도 치지 않는다")
    void ignoresPlainConversation() {
        for (String text : new String[] {"안녕하세요", "환율 알려줘", "", "   ", null}) {
            assertThat(CommandParser.parse(text)).isEmpty();
            assertThat(CommandParser.isUnknownCommand(text)).isFalse();
        }
    }

    @Test
    @DisplayName("'/'로 시작하는 모르는 명령만 안내 대상이다")
    void detectsUnknownCommand() {
        assertThat(CommandParser.isUnknownCommand("/start")).isTrue();
        assertThat(CommandParser.isUnknownCommand("/exchange")).isTrue();
        // 접두가 겹치는 다른 명령이 우리 명령으로 오인되면 안 된다
        assertThat(CommandParser.isUnknownCommand("/newsletter 금리")).isTrue();
        assertThat(CommandParser.parse("/newsletter 금리")).isEmpty();

        assertThat(CommandParser.isUnknownCommand("/news 금리")).isFalse();
        assertThat(CommandParser.isUnknownCommand("/fx")).isFalse();
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다")
    void isCaseInsensitive() {
        assertThat(CommandParser.parse("/NEWS 금리"))
                .contains(new ParsedCommand(Command.NEWS, "금리"));
        assertThat(CommandParser.parse("/Fx"))
                .contains(new ParsedCommand(Command.FX, ""));
    }

    @Test
    @DisplayName("개행으로 인자를 넘기는 클라이언트도 처리한다")
    void handlesNewlineSeparator() {
        assertThat(CommandParser.parse("/news\n금리"))
                .contains(new ParsedCommand(Command.NEWS, "금리"));
    }
}
