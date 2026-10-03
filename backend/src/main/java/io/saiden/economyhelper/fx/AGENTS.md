# fx

`/fx` — 원/달러 환율. 출처 순서: KIS(실시간) → 유럽중앙은행(Frankfurter) → 수출입은행.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.fx.*'`

## 규칙

- 출처 순서를 바꾸지 않는다. 매번 셋을 다 불러서 고르지 않는다 → ADR-0002
- 화면에 출처와 기준을 적는다 — KIS는 시각, 고시 출처는 날짜 + `(고시)` → ADR-0002
- 환율 일봉 차트는 Frankfurter 하나뿐이다(예비 출처 없음). KIS가 답한 날에도 차트가 빠질 수 있다 → ADR-0007
- KIS 호출은 `infrastructure/kis/AGENTS.md`를 따른다.
