# infrastructure/llm

Gemini 전송 골격(`GeminiApi`)과 응답에서 JSON을 꺼내는 일(`LlmJson`). 해석기·번역기·관련도가 모두 이 코드를 거친다.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.infrastructure.llm.*'`

## 규칙

- **상대적인 표현을 날짜로 받지 않는다.** `내일`을 날짜로 받으면 캐시에 남아 내일이 영원히 그 날짜가 된다. `offsetDays`로 받고 날짜는 코드가 그날 계산한다 → `docs/design.md` 4.6
- **대응표를 LLM에게 묻지 않는다.** 업종코드·KIS 심볼은 확인된 사실이라 설정 파일에 둔다 → `docs/design.md` 4.6
- **막혔을 때의 대체값은 캐시하지 않는다**(전부 통과 관련도, 번역 안 된 원문). 담으면 일시 실패가 TTL 내내 남는다 → ADR-0015
- 리미터가 60초에 12회다. 재시도를 걸지 않는다 — 재시도가 리미터 바깥이라 시도마다 허용량을 더 쓴다 → `docs/design.md` 4.4
- 타임아웃은 30초다. 생성은 조회가 아니다 → `docs/design.md` 4.3
- 프롬프트를 고치면 그 해석을 담는 캐시의 **판 번호를 올린다** → `docs/design.md` 4.2
