package io.saiden.economyhelper.config;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 헥사고날 경계를 컴파일된 클래스로 센다 — {@link StrictDateParsingTest}와 같은 결이다. 문서의 규칙은
 * 소스가 어떻게 적혀 있든 바이트코드 의존으로 확인해야 우회가 안 된다.
 *
 * <p>컨텍스트는 {@code fx·stock·crypto·weather·news·translate·digest·telegram}이고 층은
 * {@code domain → application(port.out) ← adapter}다. {@code shared}는 누구나 쓰는 바닥,
 * {@code infrastructure}는 어댑터끼리 나눠 쓰는 기술 인프라(KIS 호출·토큰·스로틀, Gemini),
 * {@code config}는 조립이다.
 */
class ArchitectureTest {

    private static final String ROOT = "io.saiden.economyhelper";
    private static final List<String> CONTEXTS =
            List.of("fx", "stock", "crypto", "weather", "news", "translate", "digest", "telegram");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    @DisplayName("domain은 프레임워크·직렬화·바깥 층을 모른다")
    void domainIsPure() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..adapter..", "..presentation..",
                        ROOT + ".infrastructure..", ROOT + ".config..",
                        "org.springframework..", "tools.jackson..", "com.fasterxml..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("application은 포트만 안다 — 어댑터·인프라·표현·스프링 캐시를 모른다")
    void applicationKnowsOnlyPorts() {
        // 캐시는 어댑터의 @Cacheable이거나, 미스를 가려야 하면 포트(TranslationCache)다
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter..", "..presentation..", ROOT + ".infrastructure..",
                        "org.springframework.cache..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("어댑터 모듈끼리는 서로 모른다 — 나눠 쓸 것은 infrastructure로 올린다")
    void adaptersDoNotKnowEachOther() {
        Set<String> modules = adapterModules();
        for (String module : modules) {
            String[] others = modules.stream().filter(m -> !m.equals(module))
                    .map(m -> m + "..").toArray(String[]::new);
            noClasses().that().resideInAPackage(module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .allowEmptyShould(true)
                    .check(CLASSES);
        }
    }

    @Test
    @DisplayName("다른 컨텍스트는 domain과 application의 서비스로만 부른다 — 어댑터·표현은 그 컨텍스트 것이다")
    void contextsMeetThroughDomainAndServices() {
        for (String context : CONTEXTS) {
            String[] foreign = CONTEXTS.stream().filter(c -> !c.equals(context))
                    .flatMap(c -> java.util.stream.Stream.of(
                            ROOT + "." + c + ".adapter..",
                            ROOT + "." + c + ".presentation.."))
                    .toArray(String[]::new);
            noClasses().that().resideInAPackage(ROOT + "." + context + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(foreign)
                    .check(CLASSES);
        }
    }

    /**
     * 다른 컨텍스트의 포트는 <b>구현만</b> 한다. 창구(텔레그램)가 {@code DigestNotifier}를 구현하는 것은
     * 헥사고날의 어댑터 자리 그대로지만, 남의 포트를 <b>부르는</b> 것은 그 컨텍스트의 서비스를 건너뛰는 것이다.
     *
     * <p>그래서 남의 {@code port..}에 기댈 수 있는 것은 <b>그 포트를 구현하는 어댑터</b>뿐이고, 기댈 수 있는
     * 타입도 <b>구현하는 포트의 계약</b>(그 인터페이스와 중첩 타입, 메서드 시그니처에 나오는 타입)뿐이다 —
     * 포트 하나를 구현했다고 같은 컨텍스트의 다른 포트({@code SendHistory} 등)를 부르면 안 된다.
     */
    @Test
    @DisplayName("남의 포트는 어댑터가 구현할 때 그 계약만 안다 — 부르는 것은 서비스를 건너뛰는 것이다")
    void foreignPortsAreOnlyImplemented() {
        List<String> broken = new java.util.ArrayList<>();
        for (JavaClass origin : CLASSES) {
            String home = contextOf(origin.getPackageName());
            boolean adapter = origin.getPackageName().contains(".adapter.")
                    || origin.getPackageName().startsWith(ROOT + ".telegram.");
            Set<String> contract = adapter ? contractOf(origin) : Set.of();
            for (com.tngtech.archunit.core.domain.Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String owner = contextOf(target.getPackageName());
                if (owner == null || owner.equals(home) || !isPort(target, owner)) {
                    continue;
                }
                if (!contract.contains(target.getName())) {
                    broken.add(dependency.getDescription());
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(broken).isEmpty();
    }

    @Test
    @DisplayName("application 아래 인터페이스는 port 아래에만 둔다 — 다른 하위 패키지로 옮겨 규칙을 비껴가지 않게")
    void portsLiveUnderPort() {
        noClasses().that().resideInAPackage("..application..")
                .and().resideOutsideOfPackage("..application.port..")
                .should().beInterfaces()
                .check(CLASSES);
    }

    @Test
    @DisplayName("shared와 infrastructure는 업무 컨텍스트를 모른다")
    void foundationsKnowNoContext() {
        String[] contexts = CONTEXTS.stream().map(c -> ROOT + "." + c + "..").toArray(String[]::new);
        noClasses().that().resideInAnyPackage(ROOT + ".shared..", ROOT + ".infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(contexts)
                .check(CLASSES);
        noClasses().that().resideInAPackage(ROOT + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".infrastructure..", ROOT + ".config..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("컨텍스트 사이에 순환이 없다")
    void contextsAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("업무 컨텍스트",
                        slice -> CONTEXTS.contains(slice.getNamePart(1))))
                .should().beFreeOfCycles()
                .check(CLASSES);
    }

    private static boolean isPort(JavaClass type, String context) {
        return type.getPackageName().startsWith(ROOT + "." + context + ".application.port");
    }

    /** {@code origin}이 구현하는 남의 포트들의 계약 — 인터페이스, 그 중첩 타입, 메서드 시그니처의 타입(제네릭 인자 포함)과 그 중첩 타입. */
    private static Set<String> contractOf(JavaClass origin) {
        Set<String> contract = new TreeSet<>();
        for (JavaClass port : origin.getAllRawInterfaces()) {
            String owner = contextOf(port.getPackageName());
            if (owner == null || !isPort(port, owner)) {
                continue;
            }
            java.util.Deque<JavaClass> todo = new java.util.ArrayDeque<>(List.of(port));
            while (!todo.isEmpty()) {
                JavaClass type = todo.pop();
                if (!isPort(type, owner) || !contract.add(type.getName())) {
                    continue;
                }
                CLASSES.stream().filter(nested -> nested.getName().startsWith(type.getName() + "$"))
                        .forEach(todo::push);
                if (type.equals(port)) {
                    for (com.tngtech.archunit.core.domain.JavaMethod method : port.getMethods()) {
                        // 제네릭 인자까지 본다 — List<DigestMessage.Chart>에서 원시 타입만 보면 List만 잡힌다
                        method.getReturnType().getAllInvolvedRawTypes().forEach(todo::push);
                        method.getParameterTypes().forEach(t -> t.getAllInvolvedRawTypes().forEach(todo::push));
                    }
                }
            }
        }
        return contract;
    }

    private static String contextOf(String pkg) {
        for (String context : CONTEXTS) {
            if (pkg.equals(ROOT + "." + context) || pkg.startsWith(ROOT + "." + context + ".")) {
                return context;
            }
        }
        return null;
    }

    /** {@code <ctx>.adapter.<in|out>.<vendor>} 하나가 한 모듈이다. 텔레그램 창구는 통째로 한 모듈이다. */
    private static Set<String> adapterModules() {
        Set<String> modules = new TreeSet<>();
        for (JavaClass type : CLASSES) {
            String pkg = type.getPackageName();
            if (pkg.startsWith(ROOT + ".telegram")) {
                modules.add(ROOT + ".telegram");
                continue;
            }
            int at = pkg.indexOf(".adapter.");
            if (at < 0) {
                continue;
            }
            String[] rest = pkg.substring(at + ".adapter.".length()).split("\\.");
            if (rest.length >= 2) {
                modules.add(pkg.substring(0, at) + ".adapter." + rest[0] + "." + rest[1]);
            }
        }
        return modules;
    }
}
