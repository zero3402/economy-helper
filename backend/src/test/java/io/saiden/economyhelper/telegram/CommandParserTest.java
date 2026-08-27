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
        ParsedCommand stock = CommandParser.parse("/stock").orElseThrow();
        assertThat(stock.hasArgument()).isFalse();
        assertThat(stock.missingRequiredArgument()).isTrue();

        assertThat(CommandParser.parse("/stock   ").orElseThrow().missingRequiredArgument()).isTrue();
        assertThat(CommandParser.parse("/crypto").orElseThrow().missingRequiredArgument()).isTrue();
        assertThat(CommandParser.parse("/weather").orElseThrow().missingRequiredArgument()).isTrue();

        // 인자가 필요 없는 명령은 인자가 없어도 정상이다
        assertThat(CommandParser.parse("/fx").orElseThrow().missingRequiredArgument()).isFalse();
        assertThat(CommandParser.parse("/help").orElseThrow().missingRequiredArgument()).isFalse();
    }

    @Test
    @DisplayName("검색어 없는 /news는 사용법 대상이 아니다 — 그 명령에는 검색어 없는 기본 답이 있다")
    void bareNewsIsNotAUsagePrompt() {
        // 여섯 중 뉴스만 인자가 선택이다. 사용법에서 막히던 동안 '검색어 없이 /n'이
        // NewsFacade에 닿지도 못했다 — 이 단언이 그 문을 지킨다
        for (String text : new String[] {"/news", "/news   ", "/n", "/n  "}) {
            ParsedCommand parsed = CommandParser.parse(text).orElseThrow();
            assertThat(parsed.command()).isEqualTo(Command.NEWS);
            assertThat(parsed.hasArgument()).as("%s에는 검색어가 없다", text).isFalse();
            assertThat(parsed.missingRequiredArgument())
                    .as("%s가 사용법으로 막히면 안 된다", text)
                    .isFalse();
        }

        // 검색어가 있으면 그대로 검색이다
        assertThat(CommandParser.parse("/news 금리").orElseThrow())
                .isEqualTo(new ParsedCommand(Command.NEWS, "금리"));
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

    @Test
    @DisplayName("줄임말도 같은 명령으로 갈린다 — 인자 자르기는 정식 이름과 한 규칙이다")
    void acceptsShortTokens() {
        assertThat(CommandParser.parse("/c 비트코인"))
                .contains(new ParsedCommand(Command.CRYPTO, "비트코인"));
        assertThat(CommandParser.parse("/s 삼성전자"))
                .contains(new ParsedCommand(Command.STOCK, "삼성전자"));
        assertThat(CommandParser.parse("/w 내일 성남"))
                .contains(new ParsedCommand(Command.WEATHER, "내일 성남"));
        assertThat(CommandParser.parse("/f")).contains(new ParsedCommand(Command.FX, ""));
        assertThat(CommandParser.parse("/h")).contains(new ParsedCommand(Command.HELP, ""));
    }

    @Test
    @DisplayName("줄임말도 대문자·봇이름·인자 없음을 똑같이 견딘다 — 파서가 하나라서다")
    void treatsShortTokensLikeTheirFullNames() {
        assertThat(CommandParser.parse("/C 비트코인"))
                .contains(new ParsedCommand(Command.CRYPTO, "비트코인"));
        assertThat(CommandParser.parse("/s@economy_helper_bot 삼성전자"))
                .contains(new ParsedCommand(Command.STOCK, "삼성전자"));
        assertThat(CommandParser.parse("/s").orElseThrow().missingRequiredArgument()).isTrue();
    }

    @Test
    @DisplayName("선언한 줄임말은 전부 제 명령으로 되돌아온다 — 오타나 충돌이면 여기서 걸린다")
    void everyDeclaredShortTokenResolvesBack() {
        for (Command command : Command.values()) {
            if (command.shortToken() != null) {
                assertThat(CommandParser.parse(command.shortToken()))
                        .as("%s의 줄임말 %s", command, command.shortToken())
                        .map(ParsedCommand::command)
                        .contains(command);
            }
        }
    }

    @Test
    @DisplayName("선언하지 않은 한 글자는 여전히 모르는 명령이다 — 있는 것만 받는다")
    void stillRejectsUndeclaredShortTokens() {
        // ⚠️ 예전에는 여기가 /n이었다. 그때는 /news에 줄임말이 없었고 Command javadoc이
        //    "지어내지 않는다"고 적어 뒀다. 이제 /n이 선언됐으므로 이 단언은 아직 아무도
        //    쓰지 않는 글자로 옮긴다 — 「선언한 것만 받는다」는 주장 자체는 그대로다
        assertThat(CommandParser.parse("/z 금리")).isEmpty();
        assertThat(CommandParser.isUnknownCommand("/z 금리")).isTrue();
        assertThat(CommandParser.isUnknownCommand("/x")).isTrue();
    }

    @Test
    @DisplayName("줄임말이 서로 겹치지 않는다 — 겹치면 하나가 먼저 걸려 나머지가 죽는다")
    void shortTokensNeverCollide() {
        // Command javadoc이 이 그물을 가리키므로 실제로 있어야 한다. 여섯이 전부 첫 글자 하나를
        // 쓰는데, 같은 글자를 두 명령이 들면 파서가 먼저 만난 쪽만 살고 나머지는 조용히 죽는다 —
        // 그 실패는 "그 명령만 동작하지 않는다"라서 발견이 늦다
        java.util.List<String> shorts = java.util.Arrays.stream(Command.values())
                .map(Command::shortToken)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(shorts).as("줄임말이 하나도 없으면 이 단언이 공허하게 통과한다").isNotEmpty();
        assertThat(shorts).doesNotHaveDuplicates();

        // 줄임말이 다른 명령의 정식 토큰과 겹치지 않는지도 본다. token()은 공개 접근자가
        // 없으므로(Command 안에서만 읽는다) 파서를 통해 확인한다 — 그게 실제로 걸리는 경로다
        for (String shortToken : shorts) {
            assertThat(CommandParser.parse(shortToken + " 아무거나"))
                    .as("%s가 아무 명령에도 안 걸린다", shortToken)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("모든 명령이 줄임말로도 불린다 — /n을 더한 뒤 여섯이 전부 갖췄다")
    void everyCommandAnswersToItsShortToken() {
        for (Command command : Command.values()) {
            assertThat(command.shortToken())
                    .as("%s에 줄임말이 없다", command)
                    .isNotNull();
            assertThat(CommandParser.parse(command.shortToken()
                            + (command.argument() == Command.Argument.REQUIRED
                                    ? " 아무거나" : "")))
                    .as("%s가 %s로 안 걸린다", command, command.shortToken())
                    .map(ParsedCommand::command)
                    .contains(command);
        }
    }
}
