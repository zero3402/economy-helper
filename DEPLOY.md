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
| Health Check Path | `/actuator/health` |

GHCR 이미지(`ghcr.io/zero3402/economy-helper`)를 그대로 당겨 쓸 수도 있다 — 같은 Dockerfile로
CI가 구운 것이고, 그쪽이 빌드 시간을 아낀다. 무료 빌더에서 Gradle 빌드는 몇 분 걸린다.

### 환경변수

**포트가 핵심이다.** Render는 포트를 하나만 노출하는데 우리는 앱(8080)과 액추에이터(8081)를
나눠 쓴다. `PORT`와 `MANAGEMENT_PORT`를 **같은 값**으로 주면 한 포트로 합쳐진다.

| 변수 | 값 | 이유 |
|---|---|---|
| `PORT` | `8080` | Render 기본값은 10000이고 덮어쓸 수 있다. 앱은 `${PORT:8080}`을 읽는다 |
| `MANAGEMENT_PORT` | `8080` | **`PORT`와 같게.** 액추에이터가 같은 포트로 합쳐져 `/actuator/health`가 열린다 — keep-warm과 Render 헬스체크가 이걸 친다 |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics` | **`digest`를 뺀다.** 포트를 합치면 공개되므로 시크릿으로 막는 대신 엔드포인트 자체를 없앤다 |
| `REDIS_HOST` · `REDIS_PORT` | Key Value의 내부 주소 | |
| `REDIS_PASSWORD` · `REDIS_SSL` | 필요 시 | 관리형 Redis는 대개 요구한다 |
| `TZ` | `Asia/Seoul` | 이미지 기본값이지만 명시해 둔다 |
| 시크릿 6개 | `.env.example` 참조 | `TELEGRAM_*` · `GEMINI_API_KEY` · `KEXIM_API_KEY` · `DATA_API_KEY` · `FMP_API_KEY` |

그리고 GitHub 저장소에 **변수** `SERVICE_URL`을 넣는다(Settings → Secrets and variables →
Actions → Variables). `.github/workflows/keep-warm.yml`이 그 주소의 `/actuator/health`를
10분마다 쳐 인스턴스를 깨워 둔다.

### 무료 티어의 제약과 대응

| 제약 | 대응 |
|---|---|
| 15분 무활동 시 스핀다운, 재기동 ~1분 | `keep-warm.yml`이 10분마다 깨워 둔다 — 아래 참조 |
| 포트 하나만 노출 | 위 환경변수 두 개로 흡수. **코드는 안 바뀐다** |
| **Key Value가 in-memory 전용** — 재시작 시 데이터 소실 | 발송 창을 두 시간으로 닫아 노출을 줄였다. 아래 참조 |

### 상시 가동 — 왜 완전 24/7이 아닌가

`/news`·`/stock`은 언제 쳐도 즉답해야 하므로 계속 깨워 둔다. 다만 **산수가 설계를 정한다.**

```
750 인스턴스-시간 / 워크스페이스 / 월    ← 서비스별이 아니다
웹 서비스만 소모 — Key Value · Static Site는 안 먹는다
초과 시 그 달 내내 모든 무료 웹 서비스가 정지된다
```

| | 월 사용 | 여유 |
|---|---|---|
| 완전 24/7 | 744h (31일) | **6h** — 무료 웹 서비스를 하나만 더 붙여도 한 달을 잃는다 |
| **KST 04:00~06:00 재움** | **682h** | **68h** ← 이걸 쓴다 |

그 두 시간의 첫 요청만 ~1분 늦는다. **연간 가동률은 오히려 이쪽이 높다** —
한 달 통째로 정지될 위험이 없기 때문이다.

브리핑 창(KST 09:00~10:50)은 핑 구간 안이라 인스턴스가 깨어 있다.

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

배포 후 한 번 등록한다.

```bash
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook?url=$SERVICE_URL/telegram/webhook"
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
