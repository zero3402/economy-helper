# shared

누구나 쓰는 값(`domain`: `Price` · `PercentChange` · `DailyBar` · `DailySeries`)과 도구
(`support`: `Failover` · `Concurrently` · `Fetched` · `Permit` · `FailureReason` · `QueryNormalizer`).

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.shared.*'`
- 경계는 `ArchitectureTest`가 본다 — 여기 무엇을 올리든 그 테스트가 정본이다.

## 규칙

- **바닥은 위를 모른다.** `shared`는 업무 컨텍스트도, `infrastructure`도, `config`도 모른다 → `docs/design.md` 2.2 규칙 7
- **정책을 넣지 않는다.** 순서 목록과 로그는 부르는 쪽이 쥔다 — `Failover`는 순서대로 부르기만 한다. (`[stock]` 태그가 `/crypto` 실패에 붙은 사고가 그 이유다.)
- `Failover.first`는 `RuntimeException`만 삼킨다. `Error`는 올린다 — 이중화로 감쌀 문제가 아니다.
- **실패는 빈 값이 아니라 예외로 알린다.** 빈 값을 돌려주면 다음 출처로 넘어가지 않는다 → `docs/design.md` 4.1
- `Concurrently`는 실패를 숨기지 않는다. 「하나가 죽어도 나머지는 보낸다」는 부르는 쪽이 정한다 → `docs/design.md` 4.5
- 벤더별 상위 타입을 여기 만들지 않는다 — `*Source` 열거형을 합치지 않은 이유가 `docs/design.md` 6에 있다.
