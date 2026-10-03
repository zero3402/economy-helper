# economy-helper — 에이전트 진입점

재테크에 필요한 값(환율·주식·코인·뉴스·날씨)을 **텔레그램으로 밀어 주고, 물으면 답하는** 봇. **시니어 개발.**

## 시작

1. 이 파일과, 루트부터 대상 경로까지의 `AGENTS.md`를 모두 읽는다. 패키지는 `backend/src/main/java/io/saiden/economyhelper/<패키지>/`이고,
   하위 문서가 더 좁은 규칙과 검증 명령을 둔다. 규칙 줄 끝의 `→ ADR-00NN`이 그 근거다 — 필요할 때 `docs/adr/`에서 읽는다.
2. 구조·트레이드오프가 걸리면 `docs/design.md`, 구동·배포·환경변수는 `docs/runbook.md`, 테스트는 `docs/testing.md`를 읽는다.
3. 요청과 직접 관련된 코드·설정·문서만 연다.

| 패키지 | 맡는 것 |
|---|---|
| `stock/` | `/stock` 시세·목표주가·실적발표일·배당, 국내 ETF |
| `infrastructure/kis/` | 한국투자증권 토큰·간격·재시도 |
| `fx/` · `crypto/` · `news/` · `weather/` | 각 명령과 그 출처 |
| `telegram/presentation/` | 통의 글자 모양·일봉 차트 그림 |
| `digest/` | 아침 브리핑(오전 9시)·날씨 알람(오전 8시) 잡 |

## 명령

```bash
cd backend && ./gradlew test                                   # 전부 — JDK 21, DigestIntegrationTest는 Docker 필요
./gradlew test --tests 'io.saiden.economyhelper.<패키지>.*'     # 패키지 하나 — 하위 AGENTS.md의 「검증」
```

- 골든 `backend/src/test/resources/golden/messages.txt`는 줄 끝까지 값이다(LF 고정). 화면을 바꾸면 차이가 의도인지 본다.
- 실제 키로 확인하는 실물 감사는 `docs/runbook.md`의 「실물 감사」.
- CI(`.github/workflows/ci.yml`)는 테스트가 통과해야 이미지를 굽고, 시크릿 없이 돈다(WireMock·Testcontainers).

## 원칙

- **반드시 테스트를 넘겨야 다음 것을 진행한다.** 버그는 실패하는 테스트로 먼저 재현하고 고친다.
- **틀린 값이 빈손보다 나쁘다.** 모르는 것은 지어내지 않고 **줄을 아예 안 적는다** — `0`이나 「-」는 값이다.
- **실측이 근거다.** 출처의 성질은 실물 호출로 확인하고, 적을 때는 **날짜와 파라미터까지** 적는다(ADR의 「근거」).
- **보충은 폴백이 아니다.** 전망·차트·강수 시각 같은 덧붙임은 실패해도 그 줄만 빠지고 답은 나간다.
- **LLM은 해석만 하고 확정하지 않는다.** 실재·좌표·상장 여부는 출처가 확정한다.
- 외부 API는 Rate Limit·응답 에러 처리를 주의해서 연동한다 — 리미터는 앱키 단위, 브레이커는 출처 단위.
- 캐시 판 번호 규칙과 벤더 날짜 STRICT 파싱은 구조 규칙이다 — `docs/design.md` 4.2 · 4.8.

## 경계

사용자에게 먼저 확인할 작업:

- 새 외부 의존성·새 외부 출처 추가, `build.gradle`·`.github/workflows/` 변경
- 요청받지 않은 commit·push·PR
- 정기 발송의 `force` 재발송(구독자에게 중복이 나간다), KIS 토큰 캐시(`kis:token`)·운영 캐시 삭제(토큰은 1분에 한 번만 발급된다)

하지 않는다:

- `.env`를 읽거나 셸에 풀거나 커밋하지 않는다 → 키 이름은 `backend/.env.example`을 보고, 실제 키가 필요한 실행은 사람이 자기 터미널에서 한다.
- 페이월 우회 도구·봇 검문 우회를 쓰지 않는다 → 그런 출처는 쓰지 않는다.

## 문서

| 문서 | 맡는 것 |
|---|---|
| `AGENTS.md`(루트·패키지) | 에이전트 규칙·검증·경계 |
| `docs/design.md` | 구조와 이유·대가(Design Doc) |
| `docs/adr/` | 결정 하나에 파일 하나 — 맥락·결정·결과·근거(실측) |
| `docs/runbook.md` · `docs/testing.md` | 운영 · 테스트 |
| `README.md` | 사람용 소개 |

- **한 사실은 한 곳에만 둔다.** 새 규칙은 가장 좁은 패키지 `AGENTS.md`에 한 줄로 두고, 경위·표·실측은 ADR에 둔다.
- 코드·설정으로 알 수 있는 것과 일반론은 쓰지 않는다. 루트는 100줄 안, 패키지 `AGENTS.md`는 규칙 한 줄씩.
- 패키지에 `AGENTS.md`를 새로 두면 옆에 `@AGENTS.md` 한 줄짜리 `CLAUDE.md`도 둔다 — Claude Code는 그래야 그 폴더에서 자동으로 불러온다.

## Git

커밋 메시지는 한국어 한 줄로 무엇을 왜 바꿨는지 적는다(`git log`의 기존 모양을 따른다).
