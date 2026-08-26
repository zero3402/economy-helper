# 배포

산출물은 **표준 컨테이너 이미지 하나**다. 배포처를 아는 코드는 앱에 없고, 호스트별 차이는
전부 환경변수로 흡수한다.

```
ghcr.io/zero3402/economy-helper:latest      linux/amd64 · linux/arm64 (public)
```

## 아무 Docker 호스트 (VPS · Oracle VM · 로컬)

```bash
git clone … && cd economy-helper
cp backend/.env.example backend/.env    # 키 6개를 채운다
docker compose -f docker-compose.prod.yml up -d
```

Redis까지 함께 뜬다. 액추에이터(8081)는 `127.0.0.1`에만 묶여 있다 —
`/actuator/digest`는 텔레그램 방송을 즉시 일으키므로 공개하면 안 된다.

## Render (Web Service + Key Value, 둘 다 무료)

### 빌드 설정

Dockerfile이 저장소 루트가 아니라 `backend/`에 있다.

| 항목 | 값 |
|---|---|
| Language / Runtime | **Docker** |
| Root Directory | `backend` |
| Dockerfile Path | `./Dockerfile` (Root Directory 기준) |
| Health Check Path | **`/actuator/health/liveness`** |

**`/actuator/health`를 헬스체크로 쓰면 안 된다.** 거기엔 Redis가 포함돼 있어 Redis가 끊기면
503이 나가고, Render는 그걸 죽은 인스턴스로 보고 재시작한다 — **Redis 장애가 재시작 루프가 된다.**
`liveness` 그룹에는 Redis가 없어 앱이 살아 있는지만 본다. Redis 상태는 `/actuator/health`로 따로 본다.

GHCR 이미지(`ghcr.io/zero3402/economy-helper`)를 그대로 당겨 쓸 수도 있다 — 같은 Dockerfile로
CI가 구운 것이고, 그쪽이 빌드 시간을 아낀다. 무료 빌더에서 Gradle 빌드는 몇 분 걸린다.

### 환경변수

**포트가 핵심이다.** Render는 포트를 하나만 노출하는데 우리는 앱(8080)과 액추에이터(8081)를
나눠 쓴다. `PORT`와 `MANAGEMENT_PORT`를 **같은 값**으로 주면 한 포트로 합쳐진다.

| 변수 | 값 | 이유 |
|---|---|---|
| `PORT` | `8080` | Render 기본값은 10000이고 덮어쓸 수 있다. 앱은 `${PORT:8080}`을 읽는다 |
| `MANAGEMENT_PORT` | `8080` | **`PORT`와 같게.** 액추에이터가 같은 포트로 합쳐져 `/actuator/health`가 열린다 — `SelfPing`과 Render 헬스체크가 이걸 친다. keep-warm은 `/actuator/health/liveness`를 친다 — `/actuator/health`는 Redis가 끊기면 503이라 깨우는 용도로는 시끄럽다 |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics` | **`digest`와 `weather`를 뺀다.** 둘 다 구독자 전원에게 즉시 방송을 날리는 트리거다 — 포트를 합치면 공개되므로 시크릿으로 막는 대신 엔드포인트 자체를 없앤다 |
| `REDIS_HOST` · `REDIS_PORT` | Key Value의 내부 주소 | |
| `REDIS_PASSWORD` · `REDIS_SSL` | 필요 시 | 관리형 Redis는 대개 요구한다 |
| `TZ` | `Asia/Seoul` | 이미지 기본값이지만 명시해 둔다 |
| `TELEGRAM_WEBHOOK_SECRET` | `openssl rand -hex 32` | **웹훅 엔드포인트의 유일한 자물쇠.** 아래 참조 |
| `TELEGRAM_NOTICE_TOPIC_ID` · `TELEGRAM_SEARCH_TOPIC_ID` | 토픽 번호 | 포럼(토픽) 그룹일 때만. 아래 참조 |
| 시크릿 9개 | `.env.example` 참조 | `TELEGRAM_*` · `GEMINI_API_KEY` · `KEXIM_API_KEY` · `DATA_API_KEY` · `FMP_API_KEY` · `ACCU_API_KEY` · `KIS_API_KEY` · `KIS_API_SECRET` |
| `KIS_API_KEY` · `KIS_API_SECRET` | 발급받은 앱키/앱시크릿 | 환율·국내 시세·미국 시세의 1순위. **실전과 모의의 키가 다르고 도메인도 다르다** — `application.yml`의 `market.kis.base-url`을 함께 맞춘다. ⚠️ **토큰은 1분에 한 번만 발급되고 발급마다 계정주에게 알림톡이 간다.** Redis 캐시(`kis:token`)가 필수이며, Redis가 죽으면 프로세스 사본으로 버틴다. 비워도 앱은 돈다 — 대신 **국내 시세가 전부 전일 종가로 내려앉는다** |
| `DATA_API_KEY` | 발급받은 키 | **두 곳이 나눠 쓴다** — 주식 국내 2순위(금융위)와 **날씨 국내 1순위(기상청 단기예보)**. data.go.kr 일반 인증키가 계정당 하나라 새로 받을 것이 없다. 비우면 국내 날씨가 AccuWeather로 내려앉는다 |
| `ACCU_API_KEY` | 발급받은 키 | 국외 날씨 1순위이자 국내 2순위. **비워도 앱은 돈다** — 그 조회가 실패로 잡혀 Open-Meteo가 받는다(키가 없는 쪽이라 한도에 안 걸린다) — 국외에서는 2순위, 국내에서는 기상청 다음의 3순위다. 다만 그 상태면 매 조회가 헛호출 한 번을 태우므로, 안 쓸 거면 키를 넣지 말고 그대로 두는 편이 낫다 |

**깨어 있게 유지하는 일은 앱이 한다.** `SELF_PING_URL`에 공개 주소를 주면 앱이 10분마다
자기를 친다(`SelfPing`). ⚠️ **반드시 공개 주소여야 한다** — `localhost`로 치면 호스트
라우터를 거치지 않아 유휴 타이머가 리셋되지 않는다. Render라면 대시보드에서
`SELF_PING_URL = ${RENDER_EXTERNAL_URL}/actuator/health/liveness`로 이어 준다.
비워 두면 이 기능은 없는 것과 같으므로, 잠들지 않는 호스트로 옮기면 값만 비우면 된다.

**밖에 의존하지 않는다.** 예약 실행을 GitHub Actions에 두었었는데 실측 전달률이
**6~11%**였다(4시간 28분에 3회, 예정 27~53회). 앱이 스스로 치면 우리 스케줄러라 확실하다.

### 무료 티어의 제약과 대응

| 제약 | 대응 |
|---|---|
| 15분 무활동 시 스핀다운, 재기동 ~1분 | 앱이 10분마다 자기를 친다(`SelfPing`) — 아래 참조 |
| 포트 하나만 노출 | 위 환경변수 두 개로 흡수. **코드는 안 바뀐다** |
| **Key Value가 in-memory 전용** — 재시작 시 데이터 소실 | 발송 창을 두 시간으로 닫아 노출을 줄였다. 아래 참조 |

### 상시 가동 — 왜 24시간인가

`/news`·`/stock`은 언제 쳐도 즉답해야 하므로 계속 깨워 둔다. **산수를 알고 고른 것이다.**

```
750 인스턴스-시간 / 워크스페이스 / 월    ← 서비스별이 아니다
웹 서비스만 소모 — Key Value · Static Site는 안 먹는다
초과 시 그 달 내내 모든 무료 웹 서비스가 정지된다
```

| | 월 사용 | 여유 |
|---|---|---|
| **완전 24/7** | **744h** (31일) | **6h** ← 이걸 쓴다 |
| KST 04:00~06:00 재움 | 682h | 68h |

한동안 아래(재우는 쪽)를 썼다. 여유가 68시간으로 넉넉했기 때문이다. 그런데 **재우면
밖에서 깨워 줄 것이 반드시 필요하다** — 잠든 앱은 스스로 못 깨어나고, 크론이 다시 돌
시각이 와도 실행할 프로세스가 없다. 09시 브리핑까지 잠들어 있게 된다.

그 역할을 GitHub Actions에 맡겼다가 전달률이 6~11%인 것을 실측하고 걷어냈다.
**밖에 매이지 않는 쪽을 택했고, 그 대가가 여유 6시간이다.**

> ⚠️ **여유 6시간이라는 뜻**
> - 무료 웹 서비스는 **이것 하나뿐**이어야 한다. 하나 더 붙이면 즉시 초과다
> - **배포할 때마다 구·신 인스턴스가 잠깐 겹쳐** 시간이 조금씩 더 붙는다
> - 31일 달만 빠듯하다. 30일은 720h로 여유 30h다
> - **첫 달은 월말에 사용 시간을 확인할 것.** 넘칠 기미가 보이면 되돌릴 길은 둘이다 —
>   창을 되살리고 밖에서 깨우는 수단(cron-job.org · Cloudflare Workers Cron)을 붙이거나,
>   유료로 올린다

브리핑 창(KST 09:00~10:50)을 두 시간으로 둔 이유는 남는다 — 재배포·크래시 직후에도
창 안의 다음 틱이 발송을 집어 든다.

> ⚠️ **무료 웹 서비스는 이것 하나여야 한다.** 프론트엔드(Vue)는 Static Site로 올리면
> 인스턴스 시간을 먹지 않는다.
>
> ⚠️ GitHub은 **공개 저장소에서 60일간 커밋이 없으면 예약 워크플로를 자동 비활성화**한다.

### ⚠️ 남는 위험 — 중복 발송

Free Key Value는 재시작하면 데이터가 전부 사라진다("whenever an instance restarts, all of its
data is lost"). 발송 기록(`digest:sent:{날짜}`)이 날아가면 "오늘 안 보냄"으로 보인다.

**09:00~11:00 사이에, 발송 직후 Key Value가 비면 브리핑이 두 번 나간다.**
그 시간대 밖에서 비는 것은 무해하다 — 창 밖에서는 잡이 아예 돌지 않는다.

없애려면 **영속 Redis**(Upstash 무료 등)로 `REDIS_*`만 바꾸면 된다. **앱은 한 줄도 안 바뀐다.**

`fmp:quota:{날짜}` 카운터도 함께 비지만 무해하다 — 한도를 덜 쓴 것으로 보일 뿐이고
FMP가 250회를 넘으면 거절하는 것이 최종 방어다.

## 텔레그램 웹훅

**등록하지 않으면 봇은 아무 반응도 하지 않는다.** 배포가 성공하고 `/actuator/health`가 UP이어도
그렇다 — 텔레그램은 우리 주소를 모르고 명령을 자기 큐에 쌓아 둘 뿐이다. 배포 후 한 번 등록한다.

```bash
set -a && . ./backend/.env && set +a
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook" \
  -d "url=$SERVICE_URL/telegram/webhook" \
  -d "secret_token=$TELEGRAM_WEBHOOK_SECRET" \
  -d "drop_pending_updates=true"
```

`secret_token`은 **Render의 `TELEGRAM_WEBHOOK_SECRET`과 반드시 같아야 한다.** 다르면 모든
명령이 403으로 죽는다. 어긋났는지는 `getWebhookInfo`의 `last_error_message`로 바로 보인다.

`drop_pending_updates=true`는 그동안 큐에 쌓인 명령을 버린다. 빼면 밀린 것들이 한꺼번에
쏟아져 0.1 CPU에서 외부 API 호출이 동시에 터지고 지난 답장이 뭉치로 날아온다.

### 엔드포인트를 두 겹으로 막는다

이 주소는 텔레그램이 부를 수 있어야 하므로 인터넷에 열린다. 인증이 없으면 아무나 아래를 쏴
봇을 대신 부릴 수 있고, **FMP 무료 한도가 하루 250회**라 몇 분이면 그날 미국 시세가 죽는다.

```
POST $SERVICE_URL/telegram/webhook
{"message":{"chat":{"id":아무거나},"text":"/stock AAPL"}}
```

| | 막는 것 |
|---|---|
| `TELEGRAM_WEBHOOK_SECRET` | 우리 주소로 직접 쏘는 사칭 → **403** |
| `TELEGRAM_CHAT_ID` | 텔레그램에서 봇을 찾은 제3자. 정상 경로라 secret은 통과한다 → **무시(200)** |

둘 다 **비어 있으면 검증하지 않는다** — 로컬과 CI가 설정 없이 돌아야 하기 때문이다.
배포 환경에서 비면 기동 로그에 WARN이 찍힌다.

### 포럼(토픽) 그룹

토픽을 쓰면 **보낼 때도 받을 때도 토픽 번호가 필요하다.** 안 주면 브리핑이 General 토픽으로
떨어지고 명령은 토픽 구분 없이 받는다.

```
TELEGRAM_NOTICE_TOPIC_ID   브리핑을 보낼 토픽
TELEGRAM_SEARCH_TOPIC_ID   명령을 받을 토픽 — 다른 토픽의 명령은 무시한다
```

번호를 아는 방법은 둘이다. **로그를 보는 쪽이 쉽다.**

**① 해당 토픽에서 아무 명령이나 친 뒤 로그를 본다.** 명령 한 건마다 한 줄이 찍힌다.

```
[webhook] 채팅 -1002334455667 토픽 12 · /help → 0.1초
```

**② 또는** 토픽의 아무 메시지 → 우클릭(모바일은 길게) → **Copy Message Link**에서 읽는다.

```
https://t.me/c/2334455667/12/34
              └─ 채팅 ─┘ └토픽┘ └메시지┘
  TELEGRAM_CHAT_ID = -1002334455667      ← 앞에 -100 을 붙인다
  토픽 ID          = 12
```

> ⚠️ **일반 그룹이 슈퍼그룹(=토픽 가능)으로 승격되면 채팅 ID가 바뀐다.** 토픽을 켠 뒤에는
> `TELEGRAM_CHAT_ID`를 반드시 다시 확인한다. 옛날 번호로는 아무것도 오지 않는다.

번호를 잘못 넣어 봇이 통째로 막히면 **`TELEGRAM_SEARCH_TOPIC_ID`를 비우는 것만으로 되살아난다** —
비면 토픽 검사를 하지 않는다. 거절될 때마다 실제 채팅·토픽 번호가 로그에 찍히므로 거기서 읽으면 된다.

> ⚠️ **봇을 그룹 관리자로 올린다.** privacy mode(기본 켜짐)에서 봇이 받는 것은
> `/명령@봇이름`과, "the bot was the last bot to send a message to the group"일 때의 일반 명령뿐이다.
> 그냥 `/stock`은 **가끔 씹힌다.** 문서: "bot admins always receive all messages".
> privacy mode를 끄는 방법도 있지만 그러면 그룹의 모든 대화가 서버로 흘러든다.

### 확인

```bash
# 채팅 ID가 그 방이 맞는가 — 포럼이면 is_forum: true
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getChat?chat_id=$TELEGRAM_CHAT_ID"

# 등록됐는가 — url이 차 있고 last_error_message가 없어야 한다
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo"

# 위조가 막히는가 — secret 없이 직접 쏜다
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$SERVICE_URL/telegram/webhook" \
  -H 'Content-Type: application/json' -d '{"message":{"chat":{"id":1},"text":"/fx"}}'
#   기대: 403, 그리고 텔레그램에 아무것도 오지 않음
```

### 단일 포트 구성 실물 확인

위 환경변수 그대로 로컬에서 띄워 확인한 결과다.

```
Tomcat started on port 8080          ← 8081 커넥터가 뜨지 않는다
GET  /actuator/health   → 200 {"status":"UP"}
POST /actuator/digest   → 404        ← 노출 목록에서 빠져 존재하지 않는다
:8081                   → 연결 없음
POST /telegram/webhook  → 200
```
