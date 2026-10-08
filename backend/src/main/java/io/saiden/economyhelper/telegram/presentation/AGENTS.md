# telegram/presentation

텔레그램 메시지의 글자 모양(`*Formatter` · `MessageLayout`)과 일봉 차트 그림(`ChartRenderer`).

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.telegram.presentation.*'`
- 글자가 바뀌면 골든 파일(`backend/src/test/resources/golden/messages.txt`)의 차이가 의도한 것인지 확인한다.

## 규칙

- 없는 값은 줄째 뺀다(루트 원칙). `MessageLayout.money`·`oneDecimal`은 `null`을 받지 않는다 — 「-」를 지어내지 않는다.
- 차트 그림 안에 글자를 그리지 않는다. 글자·숫자는 사진 설명(caption)에 쓴다. 폰트를 설치해서 해결하지 않는다 → ADR-0007
- 그림의 모든 선은 실제 데이터에 있는 값이어야 한다. 값 범위가 0이면(가격 변동 없음) 고가·저가 표시를 찍지 않는다 → ADR-0007
- caption은 1024자 이내다. 넘으면 사진이 아예 안 나간다 → ADR-0007
- 일봉 데이터를 시세 캐시에 합치지 않는다 → ADR-0007
- 날짜는 `MessageLayout.DATE` 형식 하나만 쓴다. 기준 꼬리표(`(미국)` · `(고시)` · `(종가)`)는 라벨 뒤에 붙인다 → ADR-0006
