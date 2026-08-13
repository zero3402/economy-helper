# economy-helper 백엔드 — 어디든 가져다 띄울 수 있는 표준 이미지.
#
# 이 프로젝트는 벤더 때문에 이미 세 번 갈아엎었다(Oracle 가입 실패, 토스 IP 허용목록,
# KIS 계좌 요구). 배포도 같은 일을 당할 수 있으므로 산출물을 이미지 하나로 두고
# 호스트별 차이는 전부 환경변수로 흡수한다 — Dockerfile에 특정 PaaS 이야기를 넣지 않는다.

# --- 빌드 ---------------------------------------------------------------
# --platform=$BUILDPLATFORM: 멀티아키로 구울 때도 이 스테이지는 러너의 네이티브 아키에서
# 한 번만 돈다. jar는 바이트코드라 아키와 무관하므로 arm64용 Gradle 빌드를 QEMU로
# 에뮬레이션할 이유가 없다 — 안 하면 빌드가 10분대로 늘어난다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /build

# 래퍼와 빌드 스크립트를 먼저 넣어 의존성 해석을 캐시한다.
# 소스만 바뀌면 이 아래 레이어만 다시 돈다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src src
# 테스트는 CI가 별도 잡에서 돌린다. 여기서 또 돌리면 이미지 빌드가 두 배로 느려진다
RUN ./gradlew --no-daemon bootJar -x test

# Boot 4.1에서 -Djarmode=layertools는 제거됐다. tools 모드를 쓰고 --layers를 명시해야
# 실제로 레이어가 갈린다(--launcher는 JarLauncher로 뜨게 해 준다).
RUN java -Djarmode=tools -jar build/libs/*-SNAPSHOT.jar extract --layers --launcher --destination extracted

# --- 실행 ---------------------------------------------------------------
# alpine을 쓰지 않는다. temurin의 alpine 태그는 amd64 전용이라(실측: 17-jre-alpine → amd64만,
# 17-jre → amd64·arm·arm64·ppc64le·s390x) Apple Silicon에서 빌드가 아예 안 되고
# arm 서버에도 못 올린다. 이미지 100MB를 아끼려고 아키텍처 종속을 만들 이유가 없다.
FROM eclipse-temurin:17-jre
WORKDIR /app

# 비루트로 돌린다. 컨테이너가 뚫려도 호스트로 번지는 경로를 하나 줄인다
RUN groupadd -r app && useradd -r -g app app

# 바뀌는 빈도가 낮은 것부터 넣는다 — 코드만 고치면 마지막 레이어(~1MB)만 새로 올라간다.
# 통짜 jar로 넣으면 매번 의존성 60MB를 다시 밀어 올린다.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app

# 8080 앱 / 8081 액추에이터. /actuator/digest는 텔레그램 방송을 즉시 일으키므로
# 8081을 공개 네트워크에 바인딩하면 안 된다(docker-compose.prod.yml은 127.0.0.1로 묶는다).
# 한 포트만 여는 PaaS라면 MANAGEMENT_SERVER_PORT=8080으로 덮는다 — 코드는 그대로다.
EXPOSE 8080 8081

# -Xmx를 박지 않는다. 숫자를 박는 순간 그게 특정 호스트에 대한 종속이 된다 —
# MaxRAMPercentage면 512MB 호스트든 4GB 호스트든 컨테이너 한도를 보고 알아서 맞춘다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k"

# 코드가 ZoneId.of("Asia/Seoul")을 명시하므로 동작은 TZ와 무관하지만,
# 로그 타임스탬프가 KST여야 발송 시각을 눈으로 대조할 수 있다.
ENV TZ=Asia/Seoul

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp . org.springframework.boot.loader.launch.JarLauncher"]
