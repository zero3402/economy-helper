# telegram/presentation

텔레그램 통의 글자 모양(`*Formatter`·`MessageLayout`)과 일봉 차트 그림(`ChartRenderer`).

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.telegram.*'`
- 글자가 바뀌면 골든(`backend/src/test/resources/golden/messages.txt`)의 차이가 의도인지 본다.

## 규칙

- 없는 값은 줄을 안 적는다 — `0`·「-」로 채우지 않는다.
- 그림에 글자를 그리지 않는다 — 낱말·숫자는 caption에 둔다. 폰트를 깔아 해결하지 않는다 → ADR-0007
- 그림의 모든 선은 자료에 있는 것이어야 한다. 값 폭이 0이면 고가·저가 고리를 찍지 않는다 → ADR-0007
- caption은 1024자 안이다(넘기면 사진이 통째로 안 나간다) → ADR-0007
- 일봉을 시세 캐시에 합치지 않는다 → ADR-0007
- 날짜는 `MessageLayout.DATE` 모양 하나로 쓰고, 기준 꼬리표(`(미국)`·`(고시)`·`(종가)`)는 이름표에 단다 → ADR-0006
