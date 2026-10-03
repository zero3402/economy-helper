# 운영 가이드 (Runbook)

배포 결과물은 **컨테이너 이미지 하나**다 — `ghcr.io/zero3402/economy-helper` (`linux/amd64` · `linux/arm64`).
앱 코드는 어디에 배포되는지 모른다. 호스트마다 다른 점은 전부 환경변수로 맞춘다.

| 하고 싶은 것 | 볼 곳 |
|---|---|
| 내 컴퓨터에서 돌리기 | [로컬 구동](#로컬-구동) |
| 실제 키로 답이 제대로 나오는지 보기 | [실물 감사](#실물-감사) |
| 서버에 올리기 | [배포](#배포) · [환경변수](#환경변수) |
| 봇이 명령에 반응하게 하기 | [텔레그램 웹훅](#텔레그램-웹훅) |
| 캐시를 지우고 싶을 때 | [캐시 비우기](#캐시-비우기) |
| 뭔가 이상할 때 | [장애 대응](#장애-대응) |

---

## 로컬 구동

**필요한 것**

- **JDK 21** — 가상 스레드를 쓴다. `build.gradle` 툴체인·CI·Docker 베이스 이미지 모두 21이다.
- **Docker** — 로컬 Redis와 `DigestIntegrationTest`에 쓴다.
- **API 키** — 없어도 앱은 뜬다. 키가 빈 출처만 실패하고 예비 출처로 넘어간다. 키마다 「없으면 무엇이 안 되는지」는 `backend/.env.example`에 적혀 있다.

```bash
docker compose up -d redis                         # 1. Redis만 컨테이너로
cp backend/.env.example backend/.env               # 2. 키 채우기 (.env는 git에 안 올라간다)
cd backend && ./gradlew test                       # 3. 테스트 — 무엇을 고치든 이것부터
set -a; . ./.env; set +a; ./gradlew bootRun        # 4. 실행 — .env를 환경변수로 풀어서 넘긴다
```

> **앱은 `.env` 파일을 직접 읽지 않는다.** 키는 환경변수로만 들어간다(`application.yml`에 `${TELEGRAM_BOT_TOKEN:}` 꼴로 적혀 있다).
> 배포처마다 넣는 방법이 다르기 때문이다 — Render는 대시보드, compose는 `env_file`, k8s는 Secret.
>
> ⚠️ `.env`에는 실제 키가 들어 있다. 에이전트는 이 파일을 읽지 않고, 실제 키가 필요한 실행은 사람이 자기 터미널에서 한다.

앱은 **포트 두 개**를 연다.

| 포트 | 용도 |
|---|---|
| `8080` | 텔레그램 웹훅 `POST /telegram/webhook` — 외부에서 들어오는 HTTP 입구는 이것 하나다 |
| `8081` | 액추에이터(관리용) — 외부에 공개하면 안 된다. 이유는 [design.md 3.2](design.md#32-명령-응답--웹훅에는-바로-200을-주고-답은-따로-만든다) |

### 정기 발송을 손으로 돌리기

```bash
# 마지막 실행 결과만 보기 (발송 안 함)
curl localhost:8081/actuator/digest

# 오늘 이미 보냈어도 다시 보내기
curl -X POST localhost:8081/actuator/digest  -H 'Content-Type: application/json' -d '{"force":true}'
curl -X POST localhost:8081/actuator/weather -H 'Content-Type: application/json' -d '{"force":true}'
```

> ⚠️ **`force`는 습관처럼 쓰지 않는다.** 구독자에게 같은 메시지가 한 번 더 간다.
> 「왜 안 왔지?」는 먼저 `GET`으로 확인한다. 운영 환경에서 `force`는 사람이 확인한 뒤에만 쓴다.

---

## 실물 감사

단위 테스트와 골든 파일은 **우리가 만든 가짜 응답**으로 화면을 그린다. 실물 감사는 **실제 키로 실제 명령을 넣어** 진짜 응답으로 그린 답을 보는 것이다.
텔레그램 주소를 로컬 가짜 서버(싱크)로 바꿔 두면 실제 그룹 채팅방에는 아무것도 안 간다. (Claude Code에서는 `/real-audit`)

**1단계 — 가짜 텔레그램 서버를 19999 포트에 띄운다**

어떤 POST에든 `{"ok":true,"result":{"message_id":1}}`로 답하고, 받은 본문(`sendMessage`의 `text`, `sendPhoto`의 `caption`)을 파일에 적는 서버면 된다.
⚠️ RestClient는 본문을 chunked로 보낸다. `Content-Length`만 보고 읽으면 본문이 비어 보인다.

**2단계 — 실제 키는 그대로 두고, 텔레그램 주소·포트·크론만 바꿔 띄운다**

```bash
set -a; . ./.env; set +a
SERVER_PORT=18080 MANAGEMENT_SERVER_PORT=18081 \
ECONOMY_HELPER_TELEGRAM_BASE_URL=http://127.0.0.1:19999 TELEGRAM_BOT_TOKEN=audit TELEGRAM_CHAT_ID=1 \
TELEGRAM_WEBHOOK_SECRET= TELEGRAM_SEARCH_TOPIC_ID= TELEGRAM_NOTICE_TOPIC_ID= \
ECONOMY_HELPER_DIGEST_CRON=- ECONOMY_HELPER_WEATHER_CRON=- ECONOMY_HELPER_KEEP_WARM_CRON=- ./gradlew bootRun
```

**3단계 — 텔레그램이 보내는 모양 그대로 명령을 넣는다**

```bash
curl -s -H 'content-type: application/json' \
  -d '{"update_id":1,"message":{"message_id":1,"chat":{"id":1},"text":"/stock 타임나스닥100"}}' \
  localhost:18080/telegram/webhook
```

**지킬 것**

| 할 일 | 안 하면 |
|---|---|
| **크론을 끈다** | 발송 시간대에 띄우면 브리핑 잡이 돌아 KIS·Gemini 한도를 쓴다 |
| **포트를 옮긴다** | 8080이 이미 쓰이고 있으면 기동 실패 → 재시작 반복 → KIS 토큰이 죽는다(1분에 1회만 발급) |
| **KIS 토큰을 지우지 않는다** | 로컬 Redis의 `kis:token`이 남아 있어야 재시작해도 새로 발급하지 않는다 |
| **캐시가 빈 상태에서 한다** | 캐시에 남은 값은 고치기 전의 답이다([design.md 4.2](design.md#42-캐시)) |

명령별로 여러 입력을 한 번에 넣어 보고 표로 정리하는 방식이 가장 효과적이었다. 로그의 `[webhook] … → N초`가 사용자가 느끼는 응답 시간이다.

---

## 배포

### 아무 Docker 호스트 (VPS · Oracle VM · 로컬)

```bash
cp backend/.env.example backend/.env
TAG=sha-abc1234 docker compose -f docker-compose.prod.yml up -d
```

- Redis도 함께 뜬다.
- 액추에이터(8081)는 `127.0.0.1`에만 열린다. `/actuator/digest`는 구독자 전원에게 즉시 발송하는 버튼이라 공개하면 안 된다.
- 이미지를 ARM용으로도 만드는 이유: 배포 후보인 Oracle Cloud Always Free가 ARM(Ampere)이다.

### CI

`push` → **테스트** → 통과하면 **이미지 빌드**(GHCR). 시크릿 없이 돈다([testing.md](testing.md)).

### Render (Web Service + Key Value, 둘 다 무료)

| 설정 항목 | 값 |
|---|---|
| Language / Runtime | **Docker** |
| Root Directory | `backend` |
| Dockerfile Path | `./Dockerfile` |
| Health Check Path | **`/actuator/health/liveness`** |

- ⚠️ **헬스체크에 `/actuator/health`를 쓰지 않는다.** 여기엔 Redis 상태가 포함돼서 Redis가 끊기면 503이 되고, Render가 앱을 재시작한다. **Redis 장애가 재시작 무한 반복이 된다.** `liveness`에는 Redis가 없다.
- GHCR 이미지를 그대로 받아 쓰면 빌드 시간을 아낀다.

**무료 티어의 제약과 대응**

| 제약 | 대응 |
|---|---|
| 15분 동안 요청이 없으면 잠든다 | `SELF_PING_URL`을 주면 앱이 10분마다 자기 자신을 호출한다(`SelfPing`). ⚠️ 반드시 공개 주소여야 한다 — `localhost`는 잠들기 타이머를 초기화하지 못한다 |
| 포트를 하나만 열 수 있다 | `PORT`와 `MANAGEMENT_PORT`를 같은 값으로 주고, `digest`·`weather` 엔드포인트를 공개 목록에서 뺀다 |
| Key Value(Redis)가 메모리 전용이다 | 재시작하면 발송 기록이 사라질 수 있다. 발송 시간대를 두 시간으로 좁혀 피해를 줄였다. 중복을 완전히 없애려면 영속 Redis로 `REDIS_*`만 바꾼다 |
| 한 달 750 인스턴스-시간 (워크스페이스 전체) | 24시간 × 31일 = 744시간, 여유 6시간뿐이다. **무료 웹 서비스는 이것 하나만 둔다** |

> 잠들지 않게 깨우는 일을 처음엔 GitHub Actions 예약 실행에 맡겼는데, 실제로 제때 실행된 비율이 6~11%라 걷어냈다. 지금은 앱이 스스로 호출한다.
> (참고: GitHub은 공개 저장소에 60일간 커밋이 없으면 예약 워크플로를 자동으로 끈다.)

---

## 환경변수

전체 키 목록의 정본은 `backend/.env.example`이다. 여기는 **틀리기 쉬운 것**만 적는다.

| 변수 | 값 | 주의할 점 |
|---|---|---|
| `PORT` · `MANAGEMENT_PORT` | 포트를 하나만 여는 호스트면 **같은 값**(예: 8080) | 액추에이터가 같은 포트로 합쳐진다 |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics` | 포트를 합칠 때는 **`digest`·`weather`를 뺀다** — 안 빼면 누구나 발송 버튼을 누를 수 있다 |
| `TELEGRAM_WEBHOOK_SECRET` | `openssl rand -hex 32` | 웹훅의 유일한 잠금장치. `setWebhook`의 `secret_token`과 같아야 한다 |
| `TELEGRAM_CHAT_ID` | 그룹 ID | 다른 방의 명령은 무시한다. 그룹이 슈퍼그룹으로 바뀌면 ID도 바뀐다 |
| `TELEGRAM_NOTICE_TOPIC_ID` · `TELEGRAM_SEARCH_TOPIC_ID` | 토픽 번호 | 포럼(토픽) 그룹일 때만. [아래](#포럼토픽-그룹) 참고 |
| `KIS_API_KEY` · `KIS_API_SECRET` | 한국투자증권 앱키·앱시크릿 | 환율·국내·미국 시세의 1순위. 실전과 모의투자는 키와 주소(`market.kis.base-url`)가 다르다. ⚠️ 토큰은 1분에 1회만 발급되고, 발급할 때마다 계정주에게 알림톡이 간다 — [ADR-0001](adr/0001-kis-calls.md) |
| `DATA_API_KEY` | data.go.kr 일반 인증키 | 주식·ETF 2순위와 기상청 1순위가 같이 쓴다. **활용신청은 API마다 따로** 해야 한다 — 안 한 API는 403 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` |
| `ACCU_API_KEY` | AccuWeather 키 | 국외 날씨 1순위·국내 2순위. 비우면 Open-Meteo가 대신한다 |
| `MASSIVE_API_KEY` | Polygon 무료 키 | 미국 배당 출처. 변수 이름과 발급처 이름이 다르니 주의. 없으면 미국 배당 줄만 빠진다 |
| `REDIS_HOST` · `REDIS_PORT` · `REDIS_PASSWORD` · `REDIS_SSL` | 관리형 Redis 접속 정보 | |
| `SELF_PING_URL` | `${RENDER_EXTERNAL_URL}/actuator/health/liveness` | 잠들지 않는 호스트면 비운다 |
| `TZ` | `Asia/Seoul` | 이미지 기본값이지만 명시한다 |

---

## 텔레그램 웹훅

**웹훅을 등록하지 않으면 봇은 아무 반응도 하지 않는다.** 배포가 성공하고 헬스체크가 UP이어도 마찬가지다. 배포한 뒤 한 번 등록한다.

```bash
set -a && . ./backend/.env && set +a
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook" \
  -d "url=$SERVICE_URL/telegram/webhook" \
  -d "secret_token=$TELEGRAM_WEBHOOK_SECRET" \
  -d "drop_pending_updates=true"
```

`drop_pending_updates=true`는 그동안 쌓인 명령을 버린다. 빼면 밀린 명령이 한꺼번에 쏟아진다.

**웹훅은 두 겹으로 막는다.** 주소가 인터넷에 공개되어 있고 FMP 무료 한도가 하루 250회라, 막지 않으면 남이 한도를 다 써 버릴 수 있다.

| 설정 | 막는 대상 | 결과 |
|---|---|---|
| `TELEGRAM_WEBHOOK_SECRET` | 우리 주소로 직접 요청을 보내는 사칭 | **403** |
| `TELEGRAM_CHAT_ID` | 텔레그램에서 봇을 찾아 말을 건 제3자 (정상 경로라 secret은 통과한다) | **무시 (200)** |

둘 다 비어 있으면 검사하지 않는다(로컬·CI용). 배포 환경에서 비어 있으면 기동 로그에 WARN이 찍힌다.

### 포럼(토픽) 그룹

토픽을 쓰는 그룹이면 보낼 토픽(`TELEGRAM_NOTICE_TOPIC_ID`)과 받을 토픽(`TELEGRAM_SEARCH_TOPIC_ID`) 번호가 필요하다.

- 번호는 **로그에서** 찾는 게 가장 쉽다. 명령마다 `[webhook] 채팅 -1002334455667 토픽 12 · /help → 0.1초`처럼 찍힌다.
- 메시지 링크로도 알 수 있다. `https://t.me/c/2334455667/12/34`라면 채팅 ID는 앞에 `-100`을 붙인 `-1002334455667`, 토픽은 `12`.
- 번호를 잘못 넣어 봇이 아예 반응하지 않으면 **`TELEGRAM_SEARCH_TOPIC_ID`를 비우기만 해도 살아난다.**
- ⚠️ **봇을 그룹 관리자로 지정한다.** privacy mode에서는 그냥 `/stock`이 가끔 무시된다.

### 등록 확인

```bash
# 포럼 그룹이면 is_forum: true
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getChat?chat_id=$TELEGRAM_CHAT_ID"

# url이 채워져 있고 last_error_message가 없어야 한다
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"

# secret 없이 보내면 403이 나와야 한다
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$SERVICE_URL/telegram/webhook" \
  -H 'Content-Type: application/json' -d '{"message":{"chat":{"id":1},"text":"/fx"}}'
```

---

## 캐시 비우기

| 하고 싶은 것 | 방법 |
|---|---|
| 항목 하나 | `DELETE /actuator/evict?name=…&key=…` — `name`과 `key` **둘 다** 필요하다. 하나만 주면 `{"evicted":false}` |
| 캐시 하나 전체 | `DELETE /actuator/caches/{이름}` (Spring Boot 기본 기능) |

- 전망 캐시(`us-outlook` · `kis-outlook` · `us-dividend`)는 TTL 12시간이고 판 번호(버전)를 붙이지 않는다. 그래서 필드를 새로 추가해 배포하면 최대 12시간 동안 옛 항목이 그 칸을 빈 채로 보여 준다. 기다리면 풀리고, 바로 보려면 그 캐시만 비운다.
- ⚠️ 캐시를 비우면 다음 조회가 API 한도를 다시 쓴다. 미국 종목은 심볼마다 FMP 2회 + Polygon 1회, 국내 종목은 종목마다 KIS 1~2초가 든다.
- ⚠️ **KIS 토큰(`kis:token`)과 운영 캐시 삭제는 사람의 확인을 받는다.** 토큰은 1분에 한 번만 발급된다.

---

## 장애 대응

| 증상 | 원인 | 조치 |
|---|---|---|
| 모든 명령에 반응이 없다 | 웹훅 미등록 · secret 불일치(403) · 다른 방/토픽 | `getWebhookInfo`의 `last_error_message`를 본다. 토픽 그룹이면 `TELEGRAM_SEARCH_TOPIC_ID`를 비워 본다 |
| 브리핑이 안 왔다 | 발송 시간대 밖에 기동 · 오늘 이미 보냄 · 텔레그램 거절 | `GET /actuator/digest`로 마지막 결과를 본다. `force`는 마지막 수단 |
| 브리핑이 두 번 왔다 | 메모리 전용 Redis가 발송 직후 비었다 | 영속 Redis로 `REDIS_*`를 바꾼다 |
| KIS가 HTTP 500 + `EGW00121` | 토큰이 무효가 됐다 | 앱이 자동으로 버리고 **발급 6시간 뒤** 다시 받는다(그 전에 받으면 같은 죽은 토큰이 온다). 그동안은 2순위 출처가 답한다 — [ADR-0001](adr/0001-kis-calls.md) |
| KIS가 HTTP 500 + `EGW00304`, 또는 403 `EGW00105` | 앱시크릿이 틀렸거나 끝에 줄바꿈이 붙었다 | 키를 고친다. 토큰은 버리지 않는다 |
| KIS `EGW00201` | 초당 호출 수 초과 | `KisCall`이 한 번 더 부른다. 자주 나오면 `KisThrottle` 간격을 본다 |
| 바이낸스 418·429 | IP 밴 — 밴 중에 또 부르면 밴이 길어진다 | 브레이커가 열려 호출을 멈춘다. 기다린다 — [ADR-0008](adr/0008-crypto.md) |
| FMP 402 | 무료 티어 허용 심볼이 아니다 | 정상이다. 미국 시세는 KIS가 1순위다 — [ADR-0004](adr/0004-us-quote-and-outlook.md) |
| AccuWeather 503 | 하루 한도 소진 | 재시도하지 않는다. Open-Meteo가 대신한다 |
| 고쳤는데 옛날 답이 나온다 | TTL이 긴 캐시에 옛 규칙으로 만든 값이 남았다 | 캐시 이름의 판 번호를 올린다([design.md 4.2](design.md#42-캐시)) |
| 뉴스가 영어로 오거나 검색 해석이 엉성하다 | Gemini 분당 한도(60초에 12회) 소진 | 정상적인 강등이다. 브리핑 직후에 `/n`을 치면 가장 잘 생긴다 |
| Redis 장애 | — | 캐시는 미스로 처리돼 답은 나간다. KIS 토큰은 프로세스 안 사본으로 버틴다. 락과 발송 기록은 같이 멈춘다 |
