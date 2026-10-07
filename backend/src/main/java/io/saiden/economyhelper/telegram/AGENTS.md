# telegram

텔레그램 입출구 — 웹훅·명령 파싱(`adapter/in/web`), 발송(`adapter/out`), 화면(`presentation`, 별도 `AGENTS.md`).

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.telegram.*'`

## 규칙

**웹훅 (`adapter/in/web`)**

- **웹훅에는 먼저 200을 준다.** 답을 만드는 데 몇 초가 걸리므로 여기서 기다리면 텔레그램이 타임아웃 뒤 같은 업데이트를 다시 보내 답이 두 번 나간다 → `docs/design.md` 3.2
- 같은 `update_id`는 한 번만 답한다(`ProcessedUpdates`) — 우리 200이 닿지 못한 재전송마다 같은 답이 또 나갔다.
- 설정한 채팅방에서 온 것만 처리한다. `secret_token` 헤더가 다르면 403이다 — 403이어야 `getWebhookInfo`의 `last_error_message`에 찍혀 설정이 어긋난 것을 눈으로 본다.
- 명령의 인자 상태는 셋이다(`Command`) — `/news`만 검색어 없이도 답한다 → ADR-0016

**발송 (`adapter/out`)**

- 간격은 「통 사이에 1초 쉬기」가 아니라 **같은 방에서 발송 시작 사이 1초**다. 방마다 따로 센다 → `docs/design.md` 4.5
- 429는 한 번만 다시 보낸다. 텔레그램 429는 잠깐 기다리면 풀려서 브레이커에서 뺐다 → `docs/design.md` 4.4
- 브리핑 한 번의 통 수와 그 소요 시간은 ADR-0015에 적는다 — 코드에 숫자를 복사하지 않는다.
- **설정에서 읽은 토큰·방 번호·secret은 끝을 다듬는다.** 붙여 넣은 값 끝의 개행이 토큰이면 경로에 `%0A`로 실려 발송이 전부 404가 되고, secret이면 비교가 어긋나 전부 403이 된다(KIS의 403 `EGW00105`와 같은 자리다).
