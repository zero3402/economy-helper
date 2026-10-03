# 테스트

**전부 통과가 전제 조건이다** — 통과해야 다음 것을 진행한다. 개수는 적지 않는다(적은 숫자는 반드시 낡는다).

## 돌리기

```bash
cd backend && ./gradlew test                                         # 전부
./gradlew test --tests 'io.saiden.economyhelper.telegram.*'          # 패키지 하나 — 패키지 AGENTS.md의 「검증」
```

- **JDK 21** 툴체인이 필요하다.
- **Docker**가 필요한 것은 `DigestIntegrationTest`(Testcontainers Redis) 하나다. Docker가 없으면 그것만 실패한다.
- CI(`.github/workflows/ci.yml`)는 테스트가 통과해야 이미지를 굽고, **시크릿 없이** 돈다 — 외부 호출은 전부 WireMock,
  Redis는 Testcontainers라 `application.yml`의 빈 기본값만으로 돈다.

## 층

| 층 | 무엇으로 | 예 |
|---|---|---|
| 단위 | `new`로 만든 객체 · 가짜 포트 | `StockServiceTest` · `CommandParserTest` |
| 출처 계약 | WireMock + 실측 응답을 줄인 스텁 본문 | `KisStockApiTest` · `TelegramClientTest` |
| 설정이 실제로 붙는가 | 스프링 컨텍스트 | `CacheConfigTest` · `ResilienceConfigTest` · `HttpTimeoutsTest` · `EconomyHelperPropertiesTest` |
| 통합 | Testcontainers Redis | `DigestIntegrationTest` |
| 화면 | 골든 파일 | `RenderedOutputTest` ↔ `backend/src/test/resources/golden/messages.txt` |
| 구조 그물 | 컴파일된 바이트코드 | `ArchitectureTest` · `StrictDateParsingTest` · `CacheConfigTest` · `JavadocLinksTest` |

## 규칙

1. **주장 하나에 테스트 하나.** `@DisplayName`에 *무엇을 주장하는지와 왜인지*를 함께 쓴다
   — `"지수 등락률은 필드 이름이 종목과 다르다 — prdy_ctrt로 읽으면 조용히 사라진다"`.
2. **외부 API는 실호출로 계약을 확정하고, 그 응답을 줄여 스텁 본문으로 쓴다.** 문서가 아니라 실물이 근거다.
3. **스프링을 띄우지 않는다.** WireMock + `new`로 만든다. 컨텍스트가 필요한 것은 설정이 실제로 붙는지 보는 것과 통합
   테스트뿐이다.
   - ⚠️ **WireMock 서버는 클래스당 하나다 — `testsupport.WireMockTest`를 상속한다.** 테스트 사이에는 `resetAll()`로
     상태만 되돌린다. 테스트마다 띄우고 내리면 포트 재활용 창이 열려 다른 메서드의 스텁을 받는 일이 있었다.
     `WireMockLifecycleTest`가 이 규칙을 훑는다.
   - **테스트마다 새로 만들 것은 서버가 아니라 가짜들이다** — 테스트가 변형하거나 단언하는 것은 `@BeforeEach`에.
   - ⚠️ **테스트 컨텍스트에서는 크론이 꺼져 있다**(`src/test/resources/application.properties`). 켜져 있으면 운영 크론이
     테스트 도중 발화해 실주소로 실제 호출을 낸다. `.yml`이 아닌 이유는 같은 이름의 테스트 `.yml`이 본 yml을 통째로
     가려서다. `SchedulingOffInTestsTest`가 등록된 작업이 0개인지 본다.
4. **버그는 실패하는 테스트로 먼저 재현하고 고친다.** 재현이 안 되면 고치지 않는다.
5. **못 지나가는 테스트는 지운다.** 픽스처가 언제나 참을 주는 단언, 골든이 글자 그대로 덮는 부분 단언, 호출되지
   않는 오버라이드 — 커버리지 숫자만 올리고 아무것도 못 막는다.

구조 그물의 공통 원칙은 **소스 문자열이 아니라 컴파일된 결과를 본다**는 것, 그리고 **손으로 유지하는 목록은 반드시
낡는다**는 것이다. 클래스를 훑는 테스트는 경로 구분자를 OS에 맞춰 읽는다(Windows에서 0개를 읽던 적이 있다).

## 골든 파일

`RenderedOutputTest`가 화면 사례 전부를 렌더해 `golden/messages.txt`와 대조한다. 출력은 읽어서가 아니라 찍어 봐야
드러난다 — 들여오자마자 단위 테스트가 전부 초록인 채로 살아 있던 버그 셋(정밀도 불일치, `0 KRW`, 인자 없는 명령의
엉뚱한 사용법)이 나왔다.

- 파일은 **줄 끝까지 값이다** — `.gitattributes`가 LF로 고정한다.
- 화면을 바꾸면 차이가 **의도인지** 본다.
- ⚠️ **골든은 만들고 눈으로 읽은 뒤 커밋한다.** 안 읽고 굳히면 그때의 버그가 요구사항이 된다.

## 실물 감사

단위 테스트와 골든이 통과해도 이어 붙이면 틀릴 수 있다. 실제 키로 실제 입력을 돌려 보는 감사는
[runbook.md](runbook.md#실물-감사)에 있다(Claude Code는 `/real-audit`).
