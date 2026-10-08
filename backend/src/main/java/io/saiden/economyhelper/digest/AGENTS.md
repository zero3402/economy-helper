# digest

정기 발송 — 아침 브리핑(오전 9시, 환율·증시·코인·뉴스)과 날씨 알람(오전 8시). 수동 트리거는 `adapter/in/actuator`.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.digest.*'`
- `DigestIntegrationTest`는 Testcontainers Redis를 띄운다 — Docker가 없으면 이것만 실패한다.

## 규칙

- 크론은 10분마다 잡을 깨우고, 「오늘 보냈나」는 Redis의 발송 기록(`SendHistory`)이 답한다. 슬롯 하나에 한 번만 보낸다 → `docs/design.md` 3.1
- **한 통이라도 나갔으면 슬롯을 잡은 채로 둔다** — 중간에 죽어도 남은 통을 다시 보내지 않는다. 메시지 단위 발송 기록이 어긋나면 구독자 전원에게 중복이 간다 → `docs/design.md` 6
- 갈래 넷 중 하나가 죽어도 나머지는 나간다. **전부** 실패했을 때만 슬롯을 되돌려 다음 시도를 연다.
- 화면과 무관한 `DigestMessage`만 넘긴다. 글자 모양·차트·같은 방 발송 간격은 telegram이 맡는다 → `docs/design.md` 2.3
- 브리핑은 환율을 **한 번만** 조회해 끝까지 쓴다 — 통마다 다시 부르면 한 화면 안에서 환산 기준이 갈린다 → ADR-0006
