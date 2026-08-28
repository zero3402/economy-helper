package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>WireMock 서버는 클래스당 하나다.</b> 테스트마다 띄우고 내리면 포트 재활용 창이 열린다.
 *
 * <p><b>실측이 근거다</b>(2026-08-27 10:58 KST). 전체 실행에서
 * {@code FmpUsOutlookClientTest.ignoresAZeroTarget}가 떨어졌는데, 제 스텁이 200이라고 한
 * 경로에서 <b>정상적인 HTTP 500</b>을 받았다. 저장소에서 그 경로를 500으로 스텁하는 곳은
 * <b>같은 클래스의 다른 메서드 하나뿐</b>이다. 미매칭이면 WireMock은 404를 주므로, 요청이
 * <b>앞 테스트의 서버 인스턴스</b>에 닿은 것이다 — {@code stop()}이 포트를 놓기 전에 다음
 * 서버가 같은 포트를 받는 창이 그 모양을 만든다.
 *
 * <p>그 창은 <b>테스트마다 서버를 새로 띄우는 모양에서만</b> 열린다. 그래서 규칙이 하나다 —
 * 서버는 {@code @BeforeAll}에서 한 번 띄우고, 테스트 사이에는 {@code resetAll()}로 상태만
 * 되돌린다. {@code ARCHITECTURE.md} §6이 그 규칙이고 §7이 그 실패 기록이다.
 *
 * <p><b>왜 사람이 아니라 테스트가 보는가.</b> 이 결함은 <b>간헐적</b>이라 리뷰에서 안 보이고,
 * 새 클라이언트 테스트를 쓰는 사람은 옆 파일을 베낀다 — 한 파일만 옛 모양이면 그 클래스가
 * 다시 창을 연다. 지금은 그 열한 줄이 {@code support.WireMockTest} 한 곳에 있고 스물일곱 클래스가
 * 그것을 상속한다 — 그래서 규칙이 「상속하라」로 바뀌었다. 되돌아오는 길은 「상속 없이 새로 쓴 파일」뿐이다.
 *
 * <p>⚠️ <b>상속으로 옮기면서 이 그물의 모양도 함께 바꿨다.</b> 예전 단언은 「{@code new WireMockServer(}가
 * 스무 파일 넘게 있고 그중 {@code @BeforeEach} 것이 없다」였는데, 서버 생성이 베이스 한 곳으로 가면 그 수가
 * 1이 되어 <b>문턱만 낮추면 그물이 빈다.</b> 그래서 세는 것을 「상속하는 파일」로 바꿨다.
 *
 * <p><b>허용 목록을 두지 않는다.</b> {@code @Test} 본문에서 서버를 만드는 것은 그대로 통과한다
 * ({@code BinanceApiTest}의 미러가 그렇고, 거기서는 「이 테스트만의 서버」가 곧 단언의 일부다). 손으로 적은
 * 예외 목록은 낡는다: {@code CacheConfigTest}가 클래스 목록을 손으로 들고 「여기 안 적으면 새 캐시를
 * 감시가 아예 안 본다」고 제 주석에 자백해 둔 자리가 그것이다.
 *
 * <p>⚠️ <b>이 그물이 정말 무는지 확인할 때는 {@code --rerun-tasks}를 붙일 것.</b> 아무 클래스를
 * 옛 모양으로 되돌려도 그레이들이 {@code :test}를 UP-TO-DATE로 넘기면 <b>초록이 그대로 나온다</b> —
 * 실제로 그렇게 한 번 「그물이 안 문다」고 잘못 읽었다.
 *
 * <p>⚠️ <b>훑은 파일이 0개면 통과와 「아무것도 안 읽음」이 같아 보인다.</b>
 * {@code JavadocLinksTest}가 경로를 잘못 잡아 「0건」으로 초록이던 그 함정이라,
 * 여기서도 <b>파일 수를 먼저 단언한다.</b>
 */
class WireMockLifecycleTest {

    private static final Path TESTS = Path.of("src/test/java");

    /** 서버를 만드는 자리. {@code (}까지 물어 주석·javadoc의 낱말과 갈린다. */
    private static final Pattern SERVER = Pattern.compile("new WireMockServer\\(");

    /** WireMock을 쓰는 흔적 — 스텁·검증·주소. 이 중 하나라도 있으면 서버가 어디선가 떠야 한다. */
    private static final Pattern USES_WIREMOCK =
            Pattern.compile("\\b(stubFor|server\\.verify|server\\.baseUrl|new WireMockServer)\\(");

    /** 그 자리를 감싸는 것 — 중괄호를 파싱하지 않고 <b>바로 앞의 애너테이션</b>으로 본다. */
    private static final Pattern LIFECYCLE =
            Pattern.compile("@(BeforeAll|BeforeEach|AfterAll|AfterEach|Test)\\b");

    private static final Pattern EXTENDS_BASE = Pattern.compile("\\bextends WireMockTest\\b");

    /** 규칙을 스스로 담는 두 파일 — 검사 대상이 될 이유가 없다. */
    private static final Set<String> SELF = Set.of("WireMockLifecycleTest.java", "WireMockTest.java");

    @Test
    @DisplayName("WireMock을 쓰는 테스트는 WireMockTest를 상속하거나 @Test 안에서만 제 서버를 만든다")
    void everyWireMockUserInheritsThePerClassServer() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(TESTS)) {
            files = walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !SELF.contains(path.getFileName().toString()))
                    .toList();
        }
        assertThat(files)
                .as("테스트 소스를 하나도 안 읽었다면 이 검사는 통과가 아니라 공허한 것이다")
                .hasSizeGreaterThan(80);

        List<String> inheriting = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path path : files) {
            String source = Files.readString(path);
            if (!USES_WIREMOCK.matcher(source).find()) {
                continue;
            }
            boolean inherits = EXTENDS_BASE.matcher(source).find();
            if (inherits) {
                inheriting.add(path.getFileName().toString());
            }
            // 상속하지 않는 파일은 서버를 전부 @Test 안에서 만들어야 한다 — BinanceApiTest의 미러가 그 모양이고,
            // 거기서는 「이 테스트만의 서버」가 곧 단언의 일부다. 상속하는 파일도 수명주기 메서드 안에서는 안 만든다
            Matcher server = SERVER.matcher(source);
            while (server.find()) {
                String owner = ownerOf(source, server.start());
                if (!"@Test".equals(owner)) {
                    offenders.add(path.getFileName().toString() + " (" + owner + ")");
                }
            }
            if (!inherits && !SERVER.matcher(source).find()) {
                offenders.add(path.getFileName().toString() + " (서버가 어디에도 없다)");
            }
        }

        assertThat(inheriting)
                .as("WireMockTest를 상속하는 파일을 스물 넘게는 찾아야 한다 — 아니면 패턴이 낡은 것이다")
                .hasSizeGreaterThan(20);
        assertThat(offenders)
                .as("서버는 WireMockTest가 @BeforeAll에서 한 번 띄운다. 새 클라이언트 테스트는 그것을 상속할 것 "
                        + "(선례: RetryLiveTest · HackerNewsApiTest · FmpUsOutlookClientTest). "
                        + "@Test 본문 안의 서버(BinanceApiTest 미러)만 예외다")
                .isEmpty();
    }

    /**
     * 이 자리를 감싸는 수명주기·테스트 애너테이션. 앞쪽에서 가장 마지막에 나온 것을 고른다 —
     * 애너테이션 없는 도우미 메서드 안이면 {@code null}이고, 그건 규칙이 묻는 것이 아니다.
     */
    private static String ownerOf(String source, int at) {
        Matcher lifecycle = LIFECYCLE.matcher(source.substring(0, at));
        String owner = null;
        while (lifecycle.find()) {
            owner = lifecycle.group();
        }
        return owner;
    }
}
