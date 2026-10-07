# translate

번역 — 뉴스 제목의 한글 번역(`TranslationService`)과 검색어 단어의 영어 번역(`QueryTranslationService`). 출처는 Gemini.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.translate.*'`

## 규칙

- **번역은 요약하지 않는다.** 줄이면 제목이 기사와 다른 말을 한다 → ADR-0015
- **LLM이 막혔을 때의 대체값(번역 안 된 원문)은 캐시하지 않는다.** 담으면 일시 실패가 TTL 내내 남는다 → ADR-0015
- 캐시 미스를 구분해야 해서 캐시가 포트(`TranslationCache`)로 나와 있다 — `application`은 스프링 캐시를 모른다 → `docs/design.md` 2.2 규칙 2
- `SpringTranslationCache`도 Redis 실패를 캐시 미스로 바꾼다 — 캐시가 죽어도 답은 나간다 → `docs/design.md` 4.2
- Gemini 무료 한도가 60초에 12회이고 브리핑 한 번이 9회를 쓴다. 재시도를 걸지 않는다(강등 경로가 이미 있다) → `docs/design.md` 4.4
