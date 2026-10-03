# infrastructure/kis

한국투자증권 호출 공통 — 토큰(`KisTokenStore`)·헤더 검증(`KisHeaders`)·간격(`KisThrottle`)·재시도(`KisCall`). 국내·미국 시세와 환율 1순위가 모두 이 길을 탄다.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.infrastructure.kis.*'`
- 실물 확인은 `/real-audit` — 앱을 연달아 재시작하지 않는다(토큰은 1분에 한 번 발급된다).

## 규칙

- KIS는 무효 토큰에 401이 아니라 HTTP 500(`EGW00121`)을 준다. 500을 보면 토큰부터 의심한다 → ADR-0001
- 무효 토큰은 버리고 6시간 뒤에 다시 받는다 — 앞당겨 재발급하지 않는다 → ADR-0001
- KIS 5xx는 다시 부르지 않는다. `EGW00201`(초당 건수 초과)만 간격 문을 다시 지나 한 번 더 부른다 → ADR-0001
- 토큰 캐시를 비우지 않는다 — 1분 1회 제한을 어기면 무효 토큰이 생긴다 → ADR-0001
- 한도는 「초당 몇 건」이 아니라 「호출 사이 1초」(`market.kis.min-interval`)다. 리미터로 대신하지 않는다 → ADR-0001
- `rt_cd=0`이어도 값이 비거나 `0`일 수 있다 → ADR-0001
- 토큰 발급은 프로세스 안에서 줄 세우고, Redis 락은 제 값일 때만 지운다 → ADR-0001
