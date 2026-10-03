---
name: real-audit
description: 실제 키로 봇을 띄우고 텔레그램을 로컬 싱크로 갈아 끼워 명령 답을 받아 보는 실물 감사. 인자는 넣어 볼 명령들(예: "/stock 타임나스닥100" "/weather 일요일 미금").
disable-model-invocation: true
---

# 실물 감사

실제 키로 앱을 띄워 명령을 넣고, 진짜 응답으로 만든 답을 확인한다. 텔레그램 대신 로컬 가짜 서버(싱크)가 메시지를 받으므로 실제 채팅방에는 아무것도 가지 않는다.

절차의 정본은 `docs/runbook.md`의 「실물 감사」이고, 이 스킬은 그것을 단계별로 옮긴 것이다. 다른 점은 하나다:
**실제 키가 든 `.env`는 에이전트가 읽지 않는다**(`.claude/hooks/block-env.sh`가 막는다). 그래서 앱 실행은 사람이 한다.

넣어 볼 명령: $ARGUMENTS (비어 있으면 사용자에게 묻는다)

## 1. 가짜 텔레그램 서버를 띄운다 (에이전트)

`node .claude/skills/real-audit/sink.mjs <scratchpad>/audit-sink.log`를 **백그라운드로** 띄운다.

- 19999 포트에서 어떤 POST든 `{"ok":true,"result":{"message_id":1}}`로 답한다.
- 받은 `text` · `caption`을 시각과 함께 로그 파일에 적는다(chunked 본문도 끝까지 읽는다).

## 2. 앱 실행을 사람에게 부탁한다

아래 명령을 **사용자의 터미널**에서 `backend/` 폴더에서 실행해 달라고 그대로 보여 준다.

```bash
set -a; . ./.env; set +a
SERVER_PORT=18080 MANAGEMENT_SERVER_PORT=18081 \
ECONOMY_HELPER_TELEGRAM_BASE_URL=http://127.0.0.1:19999 TELEGRAM_BOT_TOKEN=audit TELEGRAM_CHAT_ID=1 \
TELEGRAM_WEBHOOK_SECRET= TELEGRAM_SEARCH_TOPIC_ID= TELEGRAM_NOTICE_TOPIC_ID= \
ECONOMY_HELPER_DIGEST_CRON=- ECONOMY_HELPER_WEATHER_CRON=- ECONOMY_HELPER_KEEP_WARM_CRON=- ./gradlew bootRun
```

이렇게 바꾸는 이유:

- **크론을 끈다** — 켜 두면 브리핑이 돌아 KIS 20회 · Gemini 9회를 쓴다.
- **포트를 옮긴다** — 8080 충돌로 재시작을 반복하면 KIS 토큰이 죽는다(1분에 1회만 발급).

`curl -s localhost:18081/actuator/health`가 `UP`이 될 때까지 기다린다(Monitor로 조건 대기, sleep 반복 금지).

## 3. 명령을 넣는다

텔레그램이 보내는 모양 그대로 넣는다. `update_id`와 `message_id`는 명령마다 1씩 늘린다.

```bash
curl -s -H 'content-type: application/json' \
  -d '{"update_id":1,"message":{"message_id":1,"chat":{"id":1},"text":"<명령>"}}' localhost:18080/telegram/webhook
```

⚠️ **Windows Git Bash에서는 한글을 `-d '…'`로 보내지 않는다.** 명령줄 인자가 CP949로 넘어가 앱이 깨진 글자를 받는다
(실측 2026-10-03: `삼성전자`가 `�Ｚ����`로 도착). 본문을 UTF-8 파일로 저장한 뒤 `--data-binary @파일`로 보낸다.
`node -e 'fs.writeFileSync(…, JSON.stringify({…}))'`로 파일을 만들면 확실하다. macOS · Linux는 위 명령 그대로 된다.

## 4. 결과를 읽고 보고한다

- 싱크 로그에서 명령별 답 전문을 그대로 보여 준다.
- 해당 패키지 `AGENTS.md`의 화면 규칙과 비교한다.
  - 없는 값은 줄이 없어야 한다(`0`이나 「-」 금지).
  - 출처·기준 줄이 실제로 답한 출처와 맞아야 한다.
- 메시지 사이 시간 차(1초에 한 통)와, 사용자 터미널 로그의 `[webhook] … → N초`를 같이 적는다.
- **캐시가 빈 상태에서 해야 한다.** 무언가를 고친 뒤 확인하는 감사라면, 해당 캐시를 먼저 비웠는지 묻는다(`docs/design.md` 4.2).
- 새로 발견한 사실은 ADR(`docs/adr/`)의 「근거」에 **날짜와 파라미터까지** 남긴다.

## 5. 정리

싱크를 멈추고, 사용자에게 bootRun을 멈춰 달라고 말한다.
