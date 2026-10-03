# fx

`/fx` — 원/달러 환율. 이중화 3단: KIS(실시간) → 유럽중앙은행(Frankfurter) → 수출입은행.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.fx.*'`

## 규칙

- 순서를 바꾸지 않고, 매번 셋을 다 불러 고르지 않는다 → ADR-0002
- 화면은 출처와 기준을 밝힌다 — KIS는 시각, 고시 출처는 날짜 + `(고시)` → ADR-0002
- 환율 일봉은 Frankfurter 하나다(이중화 없음) — KIS가 답한 날에도 차트가 빠질 수 있다 → ADR-0007
- KIS 호출은 `infrastructure/kis/AGENTS.md`를 따른다.
