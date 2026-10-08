# src/main/resources

`application.yml` — 출처 주소·캐시 수명·resilience4j 인스턴스. 설정을 읽는 쪽 규칙은 `config/AGENTS.md`.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.config.*'`

## 규칙

- ⚠️ `ignoreExceptions`·`retryExceptions`는 `baseConfig`를 **덮어쓴다**. 이어 붙지 않는다.
- ⚠️ `retry`의 `configs.default`는 **자동으로 안 붙는다** — 인스턴스마다 `baseConfig: default`를 적는다. 빠뜨리면 모든 예외에 세 번 부른다. `ratelimiter`에는 `configs`가 아예 없다.
- 리미터는 앱키 단위, 브레이커는 출처 단위다. 보충(전망·시간별 강수)은 브레이커 이름을 본 조회와 나눈다 → `docs/design.md` 4.4
