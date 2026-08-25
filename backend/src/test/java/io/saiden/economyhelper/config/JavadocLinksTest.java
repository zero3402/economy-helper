package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>주석이 설계 문서인 저장소에서 깨진 참조는 결함이다.</b>
 *
 * <p>{@code {@link Cls#member}}가 없는 멤버를 가리키면 읽는 사람이 <b>그런 것이 있다고 믿고</b>
 * 찾아 헤맨다. 실제로 하나 있었다 — {@code HelpFormatter}가 {@code Command#head()}를 가리켰는데
 * {@code Command}에는 그런 메서드가 없다(그 사실을 든 것은 {@code section()}이다).
 * 그래서 사람이 훑는 대신 테스트가 본다.
 *
 * <p><b>고아 javadoc도 여기서 본다.</b> {@code /** … *}{@code /} 바로 뒤에 또 {@code /**}가
 * 오면 <b>앞엣것은 아무것에도 안 달린다</b> — 컴파일도 통과하고 화면에도 안 보이는데, 읽는
 * 사람은 <b>아래 메서드의 계약을 틀리게</b> 읽는다. 이 감사에서 <b>다섯 번</b> 나왔다
 * (telegram의 {@code sendChartQuietly}·{@code cryptoChart}, {@code KisStockApi.Bar},
 * {@code CryptoService.quotesOf}, {@code DailyDigestJob.Section}) — 손으로 찾을 유형이 아니다.
 *
 * <p>⚠️ <b>검사 대상이 0개면 실패한다.</b> 이 검사를 손으로 돌렸을 때 경로가 어긋나 파일을
 * 하나도 안 읽고 「0건」을 냈다 — <b>통과와 아무것도 안 한 것이 같아 보이는</b> 그 함정이다.
 * 그래서 파일 수를 먼저 단언한다.
 */
class JavadocLinksTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** {@code {@link Cls#member}} — 같은 프로젝트 클래스만 본다(JDK·스프링은 건너뛴다). */
    private static final Pattern LINK = Pattern.compile("\\{@link\\s+(\\w+)#(\\w+)");

    @Test
    @DisplayName("{@link Cls#member}가 전부 실재한다 — 없는 멤버를 가리키면 읽는 사람이 헤맨다")
    void everyMemberLinkResolves() throws IOException {
        Map<String, Set<String>> members = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(files)
                .as("소스를 하나도 안 읽었다면 이 검사는 통과가 아니라 공허한 것이다")
                .hasSizeGreaterThan(100);

        for (Path path : files) {
            members.put(nameOf(path), membersOf(stripComments(Files.readString(path))));
        }

        List<String> broken = new ArrayList<>();
        for (Path path : files) {
            String source = Files.readString(path);
            Matcher link = LINK.matcher(source);
            while (link.find()) {
                Set<String> known = members.get(link.group(1));
                if (known != null && !known.contains(link.group(2))) {
                    broken.add(nameOf(path) + " → {@link " + link.group(1) + "#" + link.group(2) + "}");
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    private static String nameOf(Path path) {
        String file = path.getFileName().toString();
        return file.substring(0, file.length() - ".java".length());
    }

    /** 주석을 걷어낸다 — 주석까지 긁으면 {@code {@link}} 자신이 근거가 되어 오탐을 스스로 지운다. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /** 메서드·상수·필드, 그리고 <b>레코드 컴포넌트</b>. 마지막 것을 빠뜨리면 오탐이 쏟아진다. */
    private static Set<String> membersOf(String body) {
        Set<String> names = new HashSet<>();
        collect(names, body, "\\b(\\w+)\\s*\\(");
        collect(names, body, "\\b([A-Z][A-Z0-9_]{2,})\\b");
        collect(names, body, "\\b(\\w+)\\s*[;=]");
        Matcher record = Pattern.compile("\\brecord\\s+\\w+\\s*\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(body);
        while (record.find()) {
            for (String part : record.group(1).split(",")) {
                String[] token = part.trim().split("\\s+");
                if (token.length >= 2) {
                    names.add(token[token.length - 1]);
                }
            }
        }
        return names;
    }

    private static void collect(Set<String> into, String body, String pattern) {
        Matcher found = Pattern.compile(pattern).matcher(body);
        while (found.find()) {
            into.add(found.group(1));
        }
    }

    /** javadoc 블록이 끝난 뒤 공백만 두고 또 {@code /**}가 오는 자리. 앞엣것은 고아다. */
    private static final Pattern ORPHAN = Pattern.compile("\\*/\\s*\\n\\s*/\\*\\*");

    @Test
    @DisplayName("아무것에도 안 달린 javadoc이 없다 — 다섯 번 나온 유형이라 사람이 찾을 것이 아니다")
    void noOrphanedJavadoc() throws IOException {
        // 테스트 소스까지 본다 — 스텁의 계약을 틀리게 읽으면 테스트가 무엇을 보는지도 어긋난다.
        // 실제로 이번 감사에서 테스트 쪽에서도 둘 나왔다(HackerNewsBuzzClientTest·
        // TelegramWebhookControllerTest), 그중 하나는 이 감사가 직접 만든 것이었다
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(SOURCES, Path.of("src/test/java"))) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertThat(files)
                .as("소스를 하나도 안 읽었다면 이 검사는 통과가 아니라 공허한 것이다")
                .hasSizeGreaterThan(150);

        List<String> orphans = new ArrayList<>();
        for (Path path : files) {
            Matcher orphan = ORPHAN.matcher(Files.readString(path));
            while (orphan.find()) {
                int line = (int) Files.readString(path).chars()
                        .limit(orphan.start()).filter(ch -> ch == '\n').count() + 1;
                orphans.add(path.getFileName() + ":" + line);
            }
        }

        assertThat(orphans)
                .as("javadoc 블록 바로 위의 또 다른 javadoc은 아무것도 설명하지 않는다 — "
                        + "읽는 사람이 아래 메서드의 계약을 틀리게 읽는다")
                .isEmpty();
    }
}
