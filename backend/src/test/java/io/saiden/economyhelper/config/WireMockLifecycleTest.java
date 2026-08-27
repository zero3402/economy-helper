package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * 다시 창을 연다. 22개를 한 번에 고쳤으므로, 다음에 되돌아오는 길은 「새로 쓴 파일」뿐이다.
 *
 * <p><b>허용 목록을 두지 않는다.</b> 규칙을 <b>실측한 결함에만</b> 맞췄으므로 예외가 필요 없다 —
 * {@code @Test} 본문에서 서버를 만드는 것은 그대로 통과한다({@code BinanceApiTest}의 미러가
 * 그렇고, 거기서는 「이 테스트만의 서버」가 곧 단언의 일부다). 손으로 적은 예외 목록은
 * 낡는다: {@code CacheConfigTest}가 클래스 목록을 손으로 들고 「여기 안 적으면 새 캐시를
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

    /** 그 자리를 감싸는 것 — 중괄호를 파싱하지 않고 <b>바로 앞의 애너테이션</b>으로 본다. */
    private static final Pattern LIFECYCLE =
            Pattern.compile("@(BeforeAll|BeforeEach|AfterAll|AfterEach|Test)\\b");

    @Test
    @DisplayName("@BeforeEach가 WireMock 서버를 띄우지 않는다 — 그 모양에서 앞 테스트의 서버에 요청이 닿았다")
    void neverStartsAWireMockServerPerTest() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(TESTS)) {
            files = walk.filter(path -> path.toString().endsWith(".java"))
                    // 자기 자신은 뺀다 — 이 파일의 주석과 패턴이 검사 대상이 될 이유가 없다
                    .filter(path -> !path.getFileName().toString().equals("WireMockLifecycleTest.java"))
                    .toList();
        }
        assertThat(files)
                .as("테스트 소스를 하나도 안 읽었다면 이 검사는 통과가 아니라 공허한 것이다")
                .hasSizeGreaterThan(80);

        List<Path> using = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path path : files) {
            String source = Files.readString(path);
            Matcher server = SERVER.matcher(source);
            boolean found = false;
            while (server.find()) {
                found = true;
                if ("@BeforeEach".equals(ownerOf(source, server.start()))) {
                    offenders.add(path.getFileName().toString());
                }
            }
            if (found) {
                using.add(path);
            }
        }

        assertThat(using)
                .as("WireMock을 쓰는 파일을 하나도 못 찾았다면 패턴이 낡은 것이다")
                .hasSizeGreaterThan(20);
        assertThat(offenders)
                .as("서버를 @BeforeAll에서 한 번만 띄우고, 테스트 사이에는 @BeforeEach에서 "
                        + "server.resetAll()로 상태만 되돌릴 것 (선례: RetryLiveTest · "
                        + "HackerNewsApiTest · FmpUsOutlookClientTest)")
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
