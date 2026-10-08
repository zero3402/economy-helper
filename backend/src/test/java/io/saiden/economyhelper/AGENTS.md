# 테스트 코드

테스트 대상 패키지의 규칙은 `src/main` 쪽 같은 경로의 `AGENTS.md`에 있다 — 테스트를 고치기 전에 그것부터 읽는다.
테스트 종류·골든 파일은 `docs/testing.md`.

## 규칙

- ⚠️ **WireMock 서버는 클래스당 하나 — `testsupport.WireMockTest`를 상속한다.** 테스트 사이에는 `resetAll()`로 상태만 되돌린다. 서버를 만들어도 되는 자리는 `@BeforeAll` 하나뿐이다(`WireMockLifecycleTest`가 검사한다). 테스트마다 띄우고 내리면 포트가 재사용되는 틈에 다른 테스트의 스텁을 받았다 — 2026-10-07 `BinanceApiTest` 간헐 실패.
- **테스트마다 새로 만들 것은 서버가 아니라 가짜 객체다.** 테스트가 바꾸거나 확인하는 것은 `@BeforeEach`에서 만든다. 여러 테스트가 같은 가짜를 쓰면 `testsupport/`에 둔다.
- ⚠️ **테스트에서는 크론이 꺼져 있다**(`src/test/resources/application.properties`). `.yml`로 바꾸지 않는다 — 같은 이름의 테스트용 `.yml`은 본 `application.yml`을 통째로 가린다(`SchedulingOffInTestsTest`).
- 시간에 기대는 단언(「몇 ms 안에」)을 쓰지 않는다 — 동시성은 `CountDownLatch`로 서로를 기다리게 해서 본다.
