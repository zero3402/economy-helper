# ADR-0014 — 뉴스 매체

> **한 줄 요약**: 링크를 눌러 무료로 읽을 수 있고 자체 편집국이 직접 쓰는 매체만 쓰며, 그 매체 피드에서는 그 매체가 쓴 기사만 쓴다. 페이월·봇 검문은 우회하지 않는다.

| 상태 | 범위 |
|---|---|
| 채택 | `news/**` · `backend/src/main/resources/application.yml`(피드 목록) |

## 맥락

브리핑과 `/news`의 기사는 RSS 피드에서 모은다.

- 링크를 눌러도 못 읽는 기사는 답이 아니다.
- 피드에는 다른 매체의 기사도 섞여 있다.
- 코인 기사는 일반 금융 피드에 거의 실리지 않는다.

## 결정

| 카테고리 | 매체 |
|---|---|
| 경제 | CNBC Markets · BBC Business · The Guardian Business · DW Business · AP News |
| 코인 | CoinDesk · The Block · Decrypt |

1. **페이월 매체는 쓰지 않는다.** 블룸버그 · 로이터 · 파이낸셜 타임즈 · 이코노미스트를 뺐다. **페이월 우회 도구(archive.ph 등)로 되살리지 않는다.** 새 매체도 같은 기준을 따른다.
2. **자체 편집국이 직접 쓰는 매체만 쓴다.** 남의 기사를 모아 싣는 곳, 짧은 속보·시황 요약 위주인 곳, 홍보성 기사가 섞이는 곳은 쓰지 않는다. 2026-11-24에 Yahoo Finance(재게재 위주) · Investing.com(본 섹션·암호화폐 섹션, 속보 요약 위주) · Cointelegraph(홍보성 기사)를 빼고 Guardian · DW · The Block · Decrypt를 넣었다. 브리핑 자리 수(코인 5 · 경제 5)와 점수 문턱은 그대로다 — 자리를 채우는 매체의 질로 고른다.
3. **한 매체의 피드에서는 그 매체 기사만 쓴다**(`NewsSource.owns`, `syndicatedFromPaywall`).
4. **코인 매체 세 곳(CoinDesk · The Block · Decrypt)을 둔다.** 코인 기사는 금융 일반 피드에 드물게 실려 이 셋이 코인 다섯 자리를 채운다. 그중 **Decrypt는 코인 전용 피드로 치지 않는다**(`NewsSource.cryptoSection` 거짓) — AI 기사가 많아 피드째 코인으로 세면 코인 자리를 먹는다. 일반 피드처럼 코인 낱말로 가른다(ADR-0015).
5. **봇 검문을 우회하지 않는다.** 피드만 받고 기사 페이지는 받지 않는다.
6. **피드 주소 끝에 슬래시를 붙이지 않고, 리다이렉트 뒤 최종 주소를 적는다.** RestClient가 리다이렉트를 따라가지 않는다(Guardian `/business/rss`는 302다). `EconomyHelperPropertiesTest.feedUrlsDoNotEndWithASlash`가 검사한다.
7. 제목·요약의 HTML 엔티티를 풀고 태그를 지운다(`RssFeedClient.clean`).

## 결과

- 통신사 자리는 AP가 대신한다.
- 매체는 8개, 피드도 8개다. 한 매체가 피드를 둘 다는 일이 없어졌지만 링크 중복 제거(`NewsService.distinctByLink`)는 남긴다 — 같은 링크가 두 피드에 실리는 일은 매체를 바꿔도 다시 생긴다.
- 테스트 픽스처는 실물 형식을 따른 합성본이다(제목·요약·이름·링크 경로를 지어 넣었다). 남의 기사를 저장소에 복제하지 않는다.
- 매체를 늘리면 관련도 프롬프트 한 번에 실리는 제목 수가 는다(전 매체 후보를 모아 한 번에 묻는다 → ADR-0015. 2026-11-19 전에는 매체마다 한 번씩이라 매체 수만큼 Gemini 한도를 썼다). ⚠️ 그 길이에서 답의 개수·점수가 어긋나지 않는지는 **아직 실측하지 않았다**.

## 근거 (실측)

| 항목 | 관찰 |
|---|---|
| **매체 교체** (2026-11-24, UA `economy-helper/1.0`, 24시간 안 / 피드 전체) | Guardian `https://www.theguardian.com/uk/business/rss` 200, 17/40 · DW `https://rss.dw.com/rdf/rss-en-bus` 200, 3/20(RSS 1.0, `dc:date`) · The Block `https://www.theblock.co/rss.xml` 200, 20/20(기사 페이지는 앱 UA에 403 — 피드만 받는다) · Decrypt `https://decrypt.co/feed` 200, 17/37. 남은 매체: CoinDesk 19/25 · BBC 20/57 · CNBC 3/30. 링크 호스트는 전부 자사였다. 코인 풀 약 56건 · 경제 풀 약 140건(AP 포함) |
| **Decrypt 피드 구성** (2026-11-24, 37건) | 14건이 코인 낱말 없는 AI·엔비디아·양자 기사(카테고리 `Artificial Intelligence` 8 등). 피드째 코인으로 세면 이 14건이 코인 자리 후보가 되고, 낱말로 가르면 낱말 없는 코인 기사 3건(모네로 토큰 해킹 · FTX · 무기한 선물 안내)이 경제로 간다 — 14 대 3이라 낱말 규칙을 골랐다. The Block은 20건 전부 코인 주제였다 |
| **후보에서 뺀 곳** (2026-11-24) | Nikkei Asia(구독 벽) · SCMP(301, 일부 유료) · Blockworks(`/feed` 308) · NPR 경제(미국 국내 위주, 3/10) · Al Jazeera(경제 섹션 피드 없음) · Guardian `/business/rss`(302) |
| **페이월 기사 섞임** | 옛 Yahoo 피드 48건 중 8건이 `wsj.com` · `investors.com`(페이월)이었다 |
| **코인 기사 부족** (2026-08-27, 당시 매체) | 일반 피드 Yahoo 49건 중 3건, 나머지 넷은 0건. Investing.com 암호화폐 섹션은 10건 중 24시간 이내가 6건. 코인 매체 둘을 더해 코인 기사 풀이 6건 → 60건대가 됐다 |
| **CoinDesk · Cointelegraph** (2026-08-27) | 피드 25 · 30항목, 자사 도메인, 평문 요약 87~166자, 페이월·로그인 없음. 앱 UA로 피드는 둘 다 200. CoinDesk 기사 페이지는 앱 UA에 429 `Vercel Security Checkpoint`, `TelegramBot` UA에는 200 — 페이월이 아니라 봇 검문이다 |
| **수집량** (2026-08-27, 운영 코드 경로, 당시 매체 — 지금 매체로는 아직 다시 재지 않았다) | 24시간 안에 코인 45건 · 경제 125건(AP 98 · BBC 18 · Investing 5 · CNBC · Yahoo 4 · CoinDesk 19 · Cointelegraph 19 · Investing 암호화폐 7). 경제 125건 중 코인 오분류 0건. 수집량이 이상하면 이 숫자와 비교한다 |
| **끝 슬래시** | CoinDesk를 `.../outboundfeeds/rss/`로 적으면 308 → 15바이트 `Redirecting...` → `SAXParseException`으로 수집 0건이 됐는데, 골든·단위 테스트는 초록이었다(실물 감사가 잡았다). `curl -L`로 재면 리다이렉트를 따라가서 200으로 보인다 |
| **HTML 엔티티** (2026-10-03) | 「S&amp;amp;P」 · 「&lt;p&gt;」가 화면에 그대로 찍히던 것을 고쳤다 |
