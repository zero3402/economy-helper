# Runbook — 구동 · 배포 · 운영

산출물은 **표준 컨테이너 이미지 하나**다(`ghcr.io/zero3402/economy-helper`, `linux/amd64` · `linux/arm64`).
배포처를 아는 코드는 앱에 없고, 호스트별 차이는 전부 환경변수로 흡수한다.

| 하고 싶은 것 | 절 |
|---|---|
| 내 컴퓨터에서 돌리기 | [로컬 구동](#로컬-구동) |
| 실제 키로 답 모양 확인 | [실물 감사](#실물-감사) |
| 서버에 올리기 | [배포](#배포) · [환경변수](#환경변수) |
| 봇이 반응하게 하기 | [텔레그램 웹훅](#텔레그램-웹훅) |
| 뭔가 이상할 때 | [장애 대응](#장애-대응) |

---

## 로컬 구동

**필요한 것**: JDK 21(가상 스레드 — `build.gradle` 툴체인·CI·Docker 베이스가 같은 21), Docker(로컬 Redis ·
`DigestIntegrationTest`). API 키는 하나도 없어도 앱은 뜬다 — 빈 키는 그 출처만 실패시키고 폴백으로 내려간다.
키가 없을 때 무엇이 나빠지는지는 `backend/.env.example`에 출처마다 적혀 있다.

```bash
docker compose up -d redis                         # 1. Redis만 컨테이너로
cp backend/.env.example backend/.env               # 2. 시크릿 — .env는 gitignore 대상이다
cd backend && ./gradlew test                       # 3. 테스트 — 무엇을 고치든 여기가 먼저다
set -a; . ./.env; set +a; ./gradlew bootRun        # 4. 실행 — .env를 셸에 풀어서 넘긴다
```

> **`.env`는 앱이 읽지 않는다.** 시크릿은 환경변수로만 들어간다(`application.yml`이 `${TELEGRAM_BOT_TOKEN:}` 꼴로
> 받는다). 배포처마다 주입 방식이 다르기 때문이다 — Render는 대시보드, compose는 `env_file`, k8s는 Secret.
> ⚠️ `.env`에는 실제 키가 있다. 에이전트는 읽지 않고, 실제 키가 필요한 실행은 사람이 자기 터미널에서 한다.

앱은 **포트를 둘** 연다. `8080`이 텔레그램 웹훅(`POST /telegram/webhook` — HTTP 진입점은 이 하나뿐이다),
`8081`이 액추에이터다([design.md 3.2](design.md#32-명령-응답--요청-스레드에서-답을-만들지-않는다)).

### 수동 트리거

```bash
curl localhost:8081/actuator/digest                                         # 마지막 실행 결과만 보기 (발송 없음)
curl -X POST localhost:8081/actuator/digest  -H 'Content-Type: application/json' -d '{"force":true}'
curl -X POST localhost:8081/actuator/weather -H 'Content-Type: application/json' -d '{"force":true}'
```

> ⚠️ **`force`를 습관처럼 쓰지 않는다.** 이미 보낸 슬롯을 다시 보내는 것이라 구독자에게 중복이 나간다.
> 「왜 안 왔지」는 `GET`으로 먼저 본다. 운영에서 `force`는 사람의 확인을 받고 쓴다.

## 실물 감사

단위 테스트·골든은 우리 픽스처를 렌더한다. **실제 키로 실제 입력을 돌려** 상대가 준 값의 렌더를 본다.
텔레그램 `base-url`을 로컬 HTTP 싱크로 돌리면 그룹 채팅을 더럽히지 않는다(Claude Code는 `/real-audit`).

```bash
# 1. 아무 POST에 {"ok":true,"result":{"message_id":1}}로 답하고 본문(sendMessage의 text, sendPhoto의 caption)을
#    파일에 적는 HTTP 서버를 19999에 띄운다. ⚠️ RestClient가 chunked로 보내므로 Content-Length만 읽으면 본문이 빈다.
# 2. 실제 키는 그대로, 텔레그램·포트·크론만 바꿔 띄운다
set -a; . ./.env; set +a
SERVER_PORT=18080 MANAGEMENT_SERVER_PORT=18081 \
ECONOMY_HELPER_TELEGRAM_BASE_URL=http://127.0.0.1:19999 TELEGRAM_BOT_TOKEN=audit TELEGRAM_CHAT_ID=1 \
TELEGRAM_WEBHOOK_SECRET= TELEGRAM_SEARCH_TOPIC_ID= TELEGRAM_NOTICE_TOPIC_ID= \
ECONOMY_HELPER_DIGEST_CRON=- ECONOMY_HELPER_WEATHER_CRON=- ECONOMY_HELPER_KEEP_WARM_CRON=- ./gradlew bootRun
# 3. 텔레그램이 보내는 모양 그대로 명령을 넣는다
curl -s -H 'content-type: application/json' \
  -d '{"update_id":1,"message":{"message_id":1,"chat":{"id":1},"text":"/stock 타임나스닥100"}}' \
  localhost:18080/telegram/webhook
```

- **크론을 끈다** — 창 시간에 띄우면 브리핑 잡이 그대로 돌아 KIS·Gemini 한도를 태운다.
- **포트를 옮긴다** — 8080이 잡혀 있으면 기동 실패로 재시작을 되풀이하다 KIS 토큰을 죽인다(1분 1회 발급).
- **KIS 토큰은 한 번만 받는다** — 로컬 Redis의 `kis:token`이 남아 재시작해도 재발급하지 않는다. 지우지 않는다.
- **찬 캐시에서 한다** — 더운 캐시는 고치기 전 값을 답한다([design.md 4.2](design.md#42-캐시)).
- 명령별 입력 행렬을 한 번 태워 표로 훑는 것이 가장 잘 들었다. 로그의 `[webhook] … → N초`가 곧 체감 시간이다.

---

## 배포

### 아무 Docker 호스트 (VPS · Oracle VM · 로컬)

```bash
cp backend/.env.example backend/.env
TAG=sha-abc1234 docker compose -f docker-compose.prod.yml up -d
```

Redis까지 함께 뜬다. 액추에이터(8081)는 `127.0.0.1`에만 묶여 있다 — `/actuator/digest`는 방송을 즉시 일으키므로
공개하면 안 된다. 이미지가 멀티아키인 이유는 배포 후보인 Oracle Cloud Always Free가 Ampere(ARM)라서다.

### CI

`push` → **테스트** → (통과했을 때만) **이미지**(GHCR). 시크릿 없이 돈다([testing.md](testing.md)).

### Render (Web Service + Key Value, 둘 다 무료)

| 항목 | 값 |
|---|---|
| Language / Runtime | **Docker** |
| Root Directory | `backend` |
| Dockerfile Path | `./Dockerfile` |
| Health Check Path | **`/actuator/health/liveness`** |

- ⚠️ **`/actuator/health`를 헬스체크로 쓰지 않는다.** Redis가 포함돼 있어 Redis가 끊기면 503이고, Render가 재시작한다
  — **Redis 장애가 재시작 루프가 된다.** `liveness`에는 Redis가 없다.
- GHCR 이미지를 그대로 당겨 쓰면 빌드 시간을 아낀다.

**무료 티어의 제약과 대응**

| 제약 | 대응 |
|---|---|
| 15분 무활동 시 스핀다운 | `SELF_PING_URL`을 주면 앱이 10분마다 자기를 친다(`SelfPing`). ⚠️ 반드시 공개 주소 — `localhost`는 유휴 타이머를 리셋하지 않는다 |
| 포트 하나만 노출 | `PORT`와 `MANAGEMENT_PORT`를 같은 값으로 주고 `digest`·`weather` 엔드포인트를 노출에서 뺀다 |
| Key Value가 in-memory 전용 | 발송 창을 두 시간으로 닫았다. 중복을 없애려면 영속 Redis로 `REDIS_*`만 바꾼다 |
| 월 750 인스턴스-시간(워크스페이스 전체) | 24/7이면 31일 달에 744h — 여유 6h. **무료 웹 서비스는 이것 하나여야 한다** |

깨우는 일을 GitHub Actions 예약에 맡겼다가 실측 전달률 6~11%를 보고 걷어냈다 — 앱이 스스로 친다.
GitHub은 공개 저장소에서 60일간 커밋이 없으면 예약 워크플로를 자동 비활성화한다.

## 환경변수

키 이름의 정본은 `backend/.env.example`이다. 여기는 **틀리기 쉬운 것**만 적는다.

| 변수 | 값 | 이유 |
|---|---|---|
| `PORT` · `MANAGEMENT_PORT` | 단일 포트 호스트면 **같은 값**(예: 8080) | 액추에이터가 같은 포트로 합쳐진다 |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics` | 포트를 합칠 때 **`digest`·`weather`를 뺀다** — 공개 방송 트리거가 된다 |
| `TELEGRAM_WEBHOOK_SECRET` | `openssl rand -hex 32` | 웹훅의 유일한 자물쇠. `setWebhook`의 `secret_token`과 같아야 한다 |
| `TELEGRAM_CHAT_ID` | 그룹 ID | 다른 방의 명령은 무시한다. 슈퍼그룹으로 승격되면 바뀐다 |
| `TELEGRAM_NOTICE_TOPIC_ID` · `TELEGRAM_SEARCH_TOPIC_ID` | 토픽 번호 | 포럼 그룹일 때만. 아래 참조 |
| `KIS_API_KEY` · `KIS_API_SECRET` | 앱키·앱시크릿 | 환율·국내·미국 시세 1순위. 실전과 모의는 키와 도메인(`market.kis.base-url`)이 다르다. ⚠️ 토큰은 1분 1회 발급, 발급마다 알림톡 — [ADR-0001](adr/0001-kis-calls.md) |
| `DATA_API_KEY` | data.go.kr 일반 인증키 | 주식·ETF 2순위와 기상청 1순위가 나눠 쓴다. **활용신청은 API마다 따로**다 — 안 한 API는 403 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` |
| `ACCU_API_KEY` | 키 | 국외 날씨 1순위·국내 2순위. 비우면 Open-Meteo가 받는다 |
| `MASSIVE_API_KEY` | Polygon 무료 키 | 미국 배당의 출처(이름과 발급처가 다르다). 없으면 미국 배당 줄만 빠진다 |
| `REDIS_HOST` · `REDIS_PORT` · `REDIS_PASSWORD` · `REDIS_SSL` | 관리형 Redis 값 | |
| `SELF_PING_URL` | `${RENDER_EXTERNAL_URL}/actuator/health/liveness` | 잠들지 않는 호스트면 비운다 |
| `TZ` | `Asia/Seoul` | 이미지 기본값이지만 명시한다 |

## 텔레그램 웹훅

**등록하지 않으면 봇은 아무 반응도 하지 않는다** — 배포가 성공하고 헬스가 UP이어도 그렇다. 배포 후 한 번 등록한다.

```bash
set -a && . ./backend/.env && set +a
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook" \
  -d "url=$SERVICE_URL/telegram/webhook" \
  -d "secret_token=$TELEGRAM_WEBHOOK_SECRET" \
  -d "drop_pending_updates=true"
```

`drop_pending_updates=true`는 큐에 쌓인 명령을 버린다 — 빼면 밀린 것들이 한꺼번에 쏟아진다.

**엔드포인트를 두 겹으로 막는다.** 주소가 인터넷에 열리고 FMP 무료 한도가 하루 250회라, 막지 않으면 한도가 곧 가용성이다.

| | 막는 것 |
|---|---|
| `TELEGRAM_WEBHOOK_SECRET` | 우리 주소로 직접 쏘는 사칭 → **403** |
| `TELEGRAM_CHAT_ID` | 텔레그램에서 봇을 찾은 제3자(정상 경로라 secret은 통과한다) → **무시(200)** |

둘 다 비어 있으면 검증하지 않는다(로컬·CI용). 배포 환경에서 비면 기동 로그에 WARN이 찍힌다.

### 포럼(토픽) 그룹

토픽을 쓰면 보낼 때(`TELEGRAM_NOTICE_TOPIC_ID`)도 받을 때(`TELEGRAM_SEARCH_TOPIC_ID`)도 번호가 필요하다.

- 번호는 **로그에서** 읽는 것이 쉽다 — 명령 한 건마다 `[webhook] 채팅 -1002334455667 토픽 12 · /help → 0.1초`.
- 또는 메시지 링크 `https://t.me/c/2334455667/12/34`에서: 채팅 ID는 앞에 `-100`을 붙이고(`-1002334455667`), 토픽은 `12`.
- 번호를 잘못 넣어 봇이 통째로 막히면 **`TELEGRAM_SEARCH_TOPIC_ID`를 비우는 것만으로 되살아난다.**
- ⚠️ **봇을 그룹 관리자로 올린다.** privacy mode에서는 그냥 `/stock`이 가끔 씹힌다.

### 확인

```bash
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getChat?chat_id=$TELEGRAM_CHAT_ID"   # 포럼이면 is_forum: true
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"                     # url이 차 있고 last_error_message가 없다
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$SERVICE_URL/telegram/webhook" \
  -H 'Content-Type: application/json' -d '{"message":{"chat":{"id":1},"text":"/fx"}}'        # 기대: 403
```

---

## 캐시 비우기

| 하고 싶은 것 | 명령 |
|---|---|
| 항목 하나 | `DELETE /actuator/evict?name=…&key=…` — `name`과 `key`가 **둘 다** 필요하다. 하나만 주면 `{"evicted":false}` |
| 캐시 하나 통째로 | `DELETE /actuator/caches/{이름}` (Boot 기본) |

- 전망 캐시(`us-outlook`·`kis-outlook`·`us-dividend`)는 12시간이고 판 번호를 안 매긴다. 필드를 더한 판을 올리면 최대
  12시간 옛 항목이 그 칸을 비운 채 읽힌다 — 기다리면 낫고, 바로 보려면 그 캐시만 비운다.
- ⚠️ 비우면 다음 조회가 한도를 다시 쓴다(미국은 심볼당 FMP 2회 + Polygon 1회, 국내는 종목당 KIS 간격 1~2초).
- ⚠️ **KIS 토큰(`kis:token`)과 운영 캐시 삭제는 사람의 확인을 받는다** — 토큰은 1분에 한 번만 발급된다.

## 장애 대응

| 증상 | 원인 | 조치 |
|---|---|---|
| 모든 명령이 무반응 | 웹훅 미등록 · secret 불일치(403) · 다른 방/토픽 | `getWebhookInfo`의 `last_error_message`를 본다. 토픽이면 `TELEGRAM_SEARCH_TOPIC_ID`를 비워 본다 |
| 브리핑이 안 왔다 | 창 밖 기동 · 슬롯 이미 사용 · 텔레그램 거절 | `GET /actuator/digest`로 마지막 결과를 본다. `force`는 마지막 수단 |
| 브리핑이 두 번 왔다 | 비영속 Redis가 발송 직후 비었다 | 영속 Redis로 `REDIS_*`를 바꾼다 |
| KIS가 HTTP 500 + `EGW00121` | 토큰 무효 | 자동으로 버리고 **발급 6시간 뒤** 재발급한다(그 전에는 같은 죽은 토큰이 온다). 그동안 2순위가 받는다 — [ADR-0001](adr/0001-kis-calls.md) |
| KIS가 HTTP 500 + `EGW00304` · 403 `EGW00105` | 앱시크릿이 틀렸거나 끝에 개행이 붙었다 | 키를 고친다. 토큰을 버리지 않는다 |
| KIS `EGW00201` | 초당 거래건수 초과 | `KisCall`이 한 번 더 부른다. 잦으면 `KisThrottle` 간격을 본다 |
| 바이낸스 418·429 | IP 밴 — 밴 중의 호출이 밴을 연장한다 | 브레이커가 열려 호출을 멈춘다. 기다린다 — [ADR-0008](adr/0008-crypto.md) |
| FMP 402 | 무료 티어 심볼 허용목록 밖 | 정상이다. 미국 시세는 KIS가 1순위다 — [ADR-0004](adr/0004-us-quote-and-outlook.md) |
| AccuWeather 503 | 일 한도 소진 | 재시도하지 않는다. Open-Meteo가 받는다 |
| 고쳤는데 옛 답이 나온다 | 긴 TTL 캐시에 옛 규칙의 값 | 캐시 이름의 판 번호를 올린다([design.md 4.2](design.md#42-캐시)) |
| 번역 없이 영문 뉴스 · 해석이 엉성하다 | Gemini 분당 한도(12/60초) 소진 | 강등이 정상 동작이다. 브리핑 직후 `/n`이 가장 쉽게 만든다 |
| Redis 장애 | — | 캐시는 미스로 돌고(답은 나간다), KIS 토큰은 프로세스 사본으로 버틴다. 락·발송 이력은 함께 죽는다 |
