# 테스트

**모든 테스트가 통과해야 다음 작업으로 넘어간다.** 테스트 개수는 적지 않는다(적어 둔 숫자는 반드시 낡는다).

## 돌리기

```bash
cd backend && ./gradlew test                                         # 전부
./gradlew test --tests 'io.saiden.economyhelper.telegram.*'          # 패키지 하나 — 각 패키지 AGENTS.md의 「검증」 참고
```

| 필요한 것 | 왜 |
|---|---|
| **JDK 21** | 툴체인이 21이다 |
| **Docker** | `DigestIntegrationTest`(Testcontainers Redis) 하나에만 필요하다. Docker가 없으면 이 테스트만 실패한다 |

CI(`.github/workflows/ci.yml`)는 테스트가 통과해야 이미지를 만들고, **시크릿 없이** 돈다. 외부 호출은 전부 WireMock, Redis는 Testcontainers라서 `application.yml`의 빈 기본값만으로 충분하다.

## 테스트 종류

| 종류 | 무엇으로 | 예 |
|---|---|---|
| 단위 | `new`로 만든 객체 + 가짜 포트 | `StockServiceTest` · `CommandParserTest` |
| 외부 API 계약 | WireMock + 실제 응답을 줄인 스텁 | `KisStockApiTest` · `TelegramClientTest` |
| 설정이 실제로 적용되는지 | 스프링 컨텍스트 | `CacheConfigTest` · `ResilienceConfigTest` · `HttpTimeoutsTest` · `EconomyHelperPropertiesTest` |
| 통합 | Testcontainers Redis | `DigestIntegrationTest` |
| 화면 | 골든 파일 | `RenderedOutputTest` ↔ `backend/src/test/resources/golden/messages.txt` |
| 구조 검사 | 컴파일된 바이트코드 | `ArchitectureTest` · `StrictDateParsingTest` · `CacheConfigTest` · `JavadocLinksTest` |

## 규칙

1. **테스트 하나에 주장 하나.** `@DisplayName`에 *무엇을 확인하는지와 왜인지*를 같이 쓴다.
   예: `"지수 등락률은 필드 이름이 종목과 다르다 — prdy_ctrt로 읽으면 조용히 사라진다"`
2. **외부 API는 실제로 호출해서 응답 모양을 확인하고, 그 응답을 줄여 스텁으로 쓴다.** API 문서가 아니라 실제 응답이 근거다.
3. **스프링을 띄우지 않는다.** WireMock과 `new`로 만든다. 스프링 컨텍스트가 필요한 것은 「설정이 실제로 적용되는지」 보는 테스트와 통합 테스트뿐이다.
   - ⚠️ **WireMock 서버는 클래스당 하나 — `testsupport.WireMockTest`를 상속한다.** 테스트 사이에는 `resetAll()`로 상태만 되돌린다.
     테스트마다 서버를 띄우고 내리면 포트가 재사용되는 틈에 다른 테스트의 스텁을 받는 일이 있었다. `WireMockLifecycleTest`가 이 규칙을 검사한다.
   - **테스트마다 새로 만들 것은 서버가 아니라 가짜 객체다.** 테스트가 바꾸거나 확인하는 것은 `@BeforeEach`에서 만든다.
   - ⚠️ **테스트에서는 크론이 꺼져 있다**(`src/test/resources/application.properties`). 켜져 있으면 운영 크론이 테스트 중에 돌아 실제 주소로 호출을 보낸다.
     `.yml`이 아니라 `.properties`인 이유: 같은 이름의 테스트용 `.yml`을 두면 본 `application.yml`을 통째로 가린다. `SchedulingOffInTestsTest`가 등록된 작업이 0개인지 확인한다.
4. **버그는 먼저 실패하는 테스트로 재현한 뒤 고친다.** 재현이 안 되면 고치지 않는다.
5. **아무것도 못 잡는 테스트는 지운다.** 예: 픽스처 때문에 항상 참인 단언, 골든 파일이 이미 글자 그대로 확인하는 부분 단언, 호출되지 않는 오버라이드. 커버리지 숫자만 올리고 아무것도 막지 못한다.

**구조 검사 테스트의 원칙**

- 소스 코드 문자열이 아니라 **컴파일된 결과**를 본다.
- **손으로 관리하는 목록은 반드시 낡는다** — 목록 대신 클래스를 훑는다.
- 클래스를 훑을 때 경로 구분자를 OS에 맞춰 읽는다. (Windows에서 0개를 읽은 적이 있다.)

## 골든 파일

`RenderedOutputTest`가 모든 화면 예시를 그려서 `golden/messages.txt`와 비교한다.
화면 버그는 코드를 읽어서는 잘 안 보이고 직접 찍어 봐야 보인다. 골든 파일을 처음 도입했을 때, 단위 테스트가 전부 초록인데도 숨어 있던 버그 셋이 나왔다 — 정밀도 불일치, `0 KRW`, 인자 없는 명령에 엉뚱한 사용법.

- 파일은 **줄 끝 문자까지 값이다.** `.gitattributes`가 LF로 고정한다.
- 화면을 바꾸면 차이가 **의도한 것인지** 확인한다.
- ⚠️ **골든은 생성한 뒤 눈으로 읽고 나서 커밋한다.** 안 읽고 굳히면 그때 있던 버그가 「정답」이 된다.

## 실물 감사

단위 테스트와 골든이 통과해도, 실제 API와 이어 붙이면 틀릴 수 있다. 실제 키로 실제 입력을 넣어 보는 방법은 [runbook.md](runbook.md#실물-감사)에 있다. (Claude Code에서는 `/real-audit`)
