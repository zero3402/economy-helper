package io.saiden.economyhelper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>이 저장소의 {@code DateTimeFormatter}는 전부 {@code uuuu} + STRICT다.</b>
 *
 * <p>{@code ofPattern}은 기본이 <b>SMART</b>다. 그러면 상대가 보낸 {@code 2026/02/31}이 예외가
 * 아니라 <b>조용히 2월 28일</b>이 된다(실측). 남의 값을 우리가 「고쳐서」 화면에 내는 것이고
 * 로그에도 흔적이 없다 — <b>틀린 값이 빈손보다 나쁘다</b>가 깨지는 자리다.
 *
 * <p>⚠️ <b>{@code yyyy}와 STRICT는 한 쌍으로 고쳐야 한다.</b> {@code yyyy}는 <b>연호 기준</b>
 * 연도라 STRICT에서 연호 필드를 요구하고, 없으면 <b>파싱이 통째로 실패한다.</b> 그래서
 * {@code uuuu}로 함께 바꾼다.
 *
 * <p><b>출력 전용 포매터까지 같은 규칙을 받는다 — 일부러 그렇게 넓혔다.</b> 포맷할 때
 * {@code uuuu}와 {@code yyyy}는 서기 연도를 <b>똑같이</b> 찍으므로(실측: 다섯 경우 바이트 동일,
 * 윤년과 세기 경계 포함) 잃는 것이 없다. 얻는 것은 <b>규칙이 하나가 되어 리플렉션으로 셀 수
 * 있다</b>는 것이다 — 「이 포매터가 파싱에 쓰이나」를 소스에서 알아내려 하지 않아도 된다.
 *
 * <p>⚠️ <b>그 판단이 이 테스트를 두 번 다시 쓰게 했다.</b> 처음에는 소스를 정규식으로 훑어
 * 「{@code parse}에 쓰이는 것만」 골랐는데, 적대적 리뷰가 <b>거짓 음성 다섯</b>을 재현해 냈다 —
 * 호출을 두 줄로 쪼개기 · 인라인 {@code ofPattern} · 정적 임포트 · 포매터를 수신자로 쓰기
 * ({@code F.parse(...)}) · {@code ofPattern(} 뒤 공백 하나. <b>정규식으로는 끝이 없었다.</b>
 * 지금은 컴파일된 클래스에서 포매터 <b>객체</b>를 꺼내 {@link DateTimeFormatter#getResolverStyle()}과
 * {@code toString()}을 직접 본다 — 소스가 어떻게 적혀 있든 상관없다.
 *
 * <p>⚠️ <b>리플렉션이 못 보는 자리를 그 다음 테스트가 막는다</b> — 메서드 안에서 만들어지는
 * 포매터는 객체로 남지 않는다. 그래서 <b>포매터는 정적 필드로 선언한다</b>를 함께 못 박는다.
 * 둘을 맞물려 두면 흔한 우회는 다 막히지만 <b>「빈틈이 없다」고까지는 말하지 않는다</b> —
 * 그 경계는 {@link #formattersAreDeclaredAsFields}의 javadoc에 적어 뒀다.
 */
class StrictDateParsingTest {

    private static final Path CLASSES = Path.of("build/classes/java/main");
    private static final Path SOURCES = Path.of("src/main/java");

    /** {@code uuuu}는 {@code Year}로, {@code yyyy}는 {@code YearOfEra}로 찍힌다(실측). */
    private static final String ERA_YEAR = "YearOfEra";

    @Test
    @DisplayName("포매터가 전부 uuuu + STRICT다 — SMART는 2026/02/31을 조용히 2/28로 고치고, yyyy+STRICT는 정상 값도 못 읽는다")
    void everyFormatterIsStrictAndProleptic() throws Exception {
        List<Class<?>> classes = compiledMainClasses();
        Map<String, String> offenders = new LinkedHashMap<>();
        int formatters = 0;

        for (Class<?> type : classes) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != DateTimeFormatter.class) {
                    continue;
                }
                // ⚠️ 비정적 필드는 인스턴스가 없어 값을 읽을 수 없다 — 그러면 규칙을 못 세운다.
                //    이 저장소에는 하나도 없으므로 「정적으로 두라」를 규칙으로 못 박는다
                if (!Modifier.isStatic(field.getModifiers())) {
                    offenders.put(type.getSimpleName() + "." + field.getName(),
                            "정적이 아니다 — 값을 읽을 수 없어 STRICT인지 셀 수 없다");
                    continue;
                }
                field.setAccessible(true);
                DateTimeFormatter formatter = (DateTimeFormatter) field.get(null);
                if (formatter == null) {
                    continue;
                }
                formatters++;
                String where = type.getSimpleName() + "." + field.getName();
                if (formatter.getResolverStyle() != ResolverStyle.STRICT) {
                    offenders.put(where, "SMART다 — " + formatter);
                } else if (formatter.toString().contains(ERA_YEAR)) {
                    offenders.put(where, "STRICT인데 yyyy다 — 연호 필드를 요구해 정상 값도 못 읽는다");
                }
            }
        }

        assertThat(formatters).as("찾은 정적 DateTimeFormatter — 0개면 이 테스트가 아무것도 안 본 것이다")
                .isGreaterThan(8);
        assertThat(offenders).as("uuuu + STRICT가 아닌 포매터").isEmpty();
    }

    /**
     * 포매터를 만드는 자리 — {@code ofPattern}만이 아니다. 이 중 어느 것이든 <b>정적
     * {@code DateTimeFormatter} 필드 선언 밖</b>에 오면 위 리플렉션이 그 포매터를 못 본다.
     *
     * <p>{@code static final Object F = new DateTimeFormatterBuilder()...}처럼
     * <b>다른 타입에 숨겨</b> 리플렉션을 피해도 여기서 걸린다 — 선언이 정적
     * {@code DateTimeFormatter} 필드가 아니기 때문이다. 완전수식({@code new java.time.format.…})도 잡는다.
     *
     * <p>⚠️ <b>보장 범위를 정확히 적어 둔다 — 「빈틈이 없다」가 아니다.</b> 이것은 자바 파서가
     * 아니라 정규식이고, <b>덮지 못하는 모양이 실제로 있다</b>(적대적 리뷰가 열거했다):
     *
     * <ul>
     *   <li><b>이미 STRICT인 포매터를 되돌리는 것</b> —
     *       {@code ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.SMART)}는 위 생성 목록에 없다.
     *   <li>리플렉션으로 포매터를 만드는 코드.
     * </ul>
     *
     * <p>그런 모양이 이 저장소에 들어올 이유가 없어 <b>파서를 들이지 않는다</b> —
     * 「없는 개선을 만들어 붙이지 않는다」와 같은 판단이다. <b>실질 보장은 위의 리플렉션
     * 검사</b>이고(이름 붙은 포매터 전부를 값으로 확인한다) 이쪽은 그것이 못 보는 자리를 막는 걸쇠다.
     */
    private static final Pattern CREATES_FORMATTER = Pattern.compile(
            "ofPattern\\s*\\(|ofLocalizedDate\\s*\\(|ofLocalizedTime\\s*\\(|"
                    + "ofLocalizedDateTime\\s*\\(|new\\s+(?:[\\w.]+\\.)?DateTimeFormatterBuilder\\s*\\(");

    /**
     * <b>정적</b> 필드 선언. {@code static}을 요구하는 것이 핵심이다 — 없이 보면
     * 메서드 안의 지역 변수 {@code DateTimeFormatter f = ...}도 필드로 오인한다
     * (적대적 리뷰가 그 반례를 짚었다).
     */
    private static final Pattern STATIC_FIELD = Pattern.compile(
            "\\bstatic\\b[^;{}]*\\bDateTimeFormatter\\b[^;{}]*=");

    @Test
    @DisplayName("ofPattern은 필드 초기화에만 온다 — 메서드 안에서 만들면 위 리플렉션이 그 포매터를 못 본다")
    void formattersAreDeclaredAsFields() throws IOException {
        List<String> scanned = new ArrayList<>();
        List<String> inline = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                scanned.add(file.getFileName().toString());
                String source = Files.readString(file);
                String code = withoutComments(source);
                Matcher found = CREATES_FORMATTER.matcher(code);
                while (found.find()) {
                    // 그 문장의 시작(앞의 ; { } 다음)부터 보면 정적 필드 선언인지 알 수 있다
                    int begin = Math.max(code.lastIndexOf(';', found.start()),
                            Math.max(code.lastIndexOf('{', found.start()),
                                    code.lastIndexOf('}', found.start())));
                    String statement = code.substring(begin + 1, found.start());
                    if (!STATIC_FIELD.matcher(statement).find()) {
                        inline.add(file.getFileName() + ":" + (code.substring(0, found.start())
                                .split("\n", -1).length));
                    }
                }
            }
        }

        assertThat(scanned).as("훑은 main 소스 파일 — 0개면 이 테스트가 아무것도 안 본 것이다")
                .hasSizeGreaterThan(100);
        assertThat(inline).as("필드 밖에서 만들어진 포매터 (리플렉션 검사가 못 본다)").isEmpty();
    }

    /**
     * 주석을 <b>같은 길이의 공백으로</b> 지운다 — 줄·자리 번호가 안 밀린다.
     *
     * <p><b>문자열 리터럴은 지우지 않고 지나간다</b>(내용을 그대로 남긴다). 지우는 것이
     * 아니라 <b>주석으로 오인하지 않는 것</b>이 목적이어서다 — {@code "https://…"}의 슬래시 둘을
     * 주석 시작으로 읽으면 그 줄의 나머지 코드가 사라진다. 대가는 문자열 안에 적힌
     * {@code ofPattern(}도 검사에 걸린다는 것인데, 그건 <b>과탐 쪽으로 틀리는</b> 방향이라 받는다.
     *
     * <p>⚠️ <b>「그 줄이 별표로 시작하나」로 보다가 고쳤다.</b> 그러면
     * {@code /* date *}{@code / return LocalDate.parse(s, DateTimeFormatter.ofPattern(...));}처럼
     * <b>끝난 블록 주석 뒤의 실행 코드</b>가 통째로 주석으로 처리됐다(적대적 리뷰가 짚었다).
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char at = source.charAt(i);
            // ⚠️ 문자열·문자 리터럴을 먼저 지나간다. 안 그러면 "https://example.com" 안의
            //    슬래시 둘을 주석 시작으로 읽어 **그 줄의 나머지 코드가 지워진다**
            if (at == '"' || at == '\'') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != at) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, source.length());
                out.append(source, i, end);
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                blank(out, source, i, end);
                i = end;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? source.length() : end;
                blank(out, source, i, end);
                i = end;
            } else {
                out.append(source.charAt(i++));
            }
        }
        return out.toString();
    }

    /** 줄바꿈만 남기고 지운다 — 줄 번호를 보존한다. */
    private static void blank(StringBuilder out, String source, int from, int to) {
        for (int at = from; at < to; at++) {
            out.append(source.charAt(at) == '\n' ? '\n' : ' ');
        }
    }

    private static List<Class<?>> compiledMainClasses() throws IOException {
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CLASSES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = CLASSES.relativize(file).toString()
                        .replace(".class", "").replace('/', '.');
                try {
                    // 초기화하지 않고 로드한다 — 스프링 빈의 정적 초기화를 깨우지 않는다
                    types.add(Class.forName(name, false, StrictDateParsingTest.class.getClassLoader()));
                } catch (Throwable ignored) {
                    // 로드할 수 없는 것은 포매터도 들 수 없다
                }
            }
        }
        assertThat(types).as("컴파일된 main 클래스 — 0개면 경로가 틀렸다").hasSizeGreaterThan(100);
        return types;
    }
}
