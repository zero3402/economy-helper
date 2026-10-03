# infrastructure/kis

한국투자증권(KIS) 호출 공통 코드 — 토큰(`KisTokenStore`) · 헤더 검증(`KisHeaders`) · 호출 간격(`KisThrottle`) · 재시도(`KisCall`).
국내·미국 시세와 환율 1순위가 모두 이 코드를 거친다.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.infrastructure.kis.*'`
- 실제 확인은 `/real-audit`. 앱을 연달아 재시작하지 않는다(토큰은 1분에 한 번만 발급된다).

## 규칙

- KIS는 무효 토큰에 401이 아니라 HTTP 500(`EGW00121`)을 준다. 500을 보면 토큰부터 의심한다 → ADR-0001
- 무효 토큰은 버리고 발급 6시간 뒤에 다시 받는다. 앞당겨 재발급하지 않는다 → ADR-0001
- KIS 5xx는 재시도하지 않는다. `EGW00201`(초당 호출 수 초과)만 호출 간격을 다시 지킨 뒤 한 번 더 부른다 → ADR-0001
- 토큰 캐시를 지우지 않는다 — 1분 1회 발급 제한을 어기면 무효 토큰이 생긴다 → ADR-0001
- 한도는 「초당 몇 건」이 아니라 「호출 사이 1초」(`market.kis.min-interval`)다. 리미터로 대신하지 않는다 → ADR-0001
- `rt_cd=0`(성공)이어도 값이 비어 있거나 `0`일 수 있다 → ADR-0001
- 토큰 발급은 프로세스 안에서 한 번에 하나씩만 한다. Redis 락은 내가 건 값일 때만 지운다 → ADR-0001
