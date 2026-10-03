# ADR-0014 — 뉴스 매체

> **한 줄 요약**: 링크를 눌러 무료로 읽을 수 있는 매체만 쓰고, 그 매체 피드에서는 그 매체가 쓴 기사만 쓴다. 페이월·봇 검문은 우회하지 않는다.

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
| 경제 (글로벌 점유율 순) | Yahoo Finance · Investing.com · CNBC · BBC Business · AP News |
| 코인 | Investing.com *(암호화폐 섹션)* · CoinDesk · Cointelegraph |

1. **페이월 매체는 쓰지 않는다.** 블룸버그 · 로이터 · 파이낸셜 타임즈 · 이코노미스트를 뺐다. **페이월 우회 도구(archive.ph 등)로 되살리지 않는다.** 새 매체도 같은 기준을 따른다.
2. **한 매체의 피드에서는 그 매체 기사만 쓴다**(`NewsSource.owns`, `syndicatedFromPaywall`).
3. **매체는 7개, 피드는 8개다.** Investing.com만 일반 섹션과 암호화폐 섹션 두 피드를 쓴다. 화면에는 둘 다 Investing.com으로 표시한다.
4. **코인 전문 매체 두 곳(CoinDesk · Cointelegraph)을 둔다.** Investing.com 암호화폐 섹션 하나로는 코인 다섯 자리가 안 찬다.
5. **봇 검문을 우회하지 않는다.** 피드만 받고 기사 페이지는 받지 않는다.
6. **피드 주소 끝에 슬래시를 붙이지 않는다.** RestClient가 리다이렉트를 따라가지 않는다. `EconomyHelperPropertiesTest.feedUrlsDoNotEndWithASlash`가 검사한다.
7. 제목·요약의 HTML 엔티티를 풀고 태그를 지운다(`RssFeedClient.clean`).

## 결과

- 통신사 자리는 AP가 대신한다.
- 관련도 채점을 매체마다 한 번씩 하므로, 매체를 늘리면 Gemini 여유가 줄어든다(코인 매체 둘을 추가해 여유가 6 → 3).

## 근거 (실측)

| 항목 | 관찰 |
|---|---|
| **페이월 기사 섞임** | Yahoo 피드 48건 중 8건이 `wsj.com` · `investors.com`(페이월)이었다 |
| **코인 기사 부족** (2026-08-27) | 일반 피드 Yahoo 49건 중 3건, 나머지 넷은 0건. Investing.com 암호화폐 섹션은 10건 중 24시간 이내가 6건. 코인 매체 둘을 더해 코인 기사 풀이 6건 → 60건대가 됐다 |
| **CoinDesk · Cointelegraph** (2026-08-27) | 피드 25 · 30항목, 자사 도메인, 평문 요약 87~166자, 페이월·로그인 없음. 앱 UA로 피드는 둘 다 200. CoinDesk 기사 페이지는 앱 UA에 429 `Vercel Security Checkpoint`, `TelegramBot` UA에는 200 — 페이월이 아니라 봇 검문이다 |
| **수집량** (2026-08-27, 운영 코드 경로) | 24시간 안에 코인 45건 · 경제 125건(AP 98 · BBC 18 · Investing 5 · CNBC · Yahoo 4 · CoinDesk 19 · Cointelegraph 19 · Investing 암호화폐 7). 경제 125건 중 코인 오분류 0건. 수집량이 이상하면 이 숫자와 비교한다 |
| **끝 슬래시** | CoinDesk를 `.../outboundfeeds/rss/`로 적으면 308 → 15바이트 `Redirecting...` → `SAXParseException`으로 수집 0건이 됐는데, 골든·단위 테스트는 초록이었다(실물 감사가 잡았다). `curl -L`로 재면 리다이렉트를 따라가서 200으로 보인다 |
| **HTML 엔티티** (2026-10-03) | 「S&amp;amp;P」 · 「&lt;p&gt;」가 화면에 그대로 찍히던 것을 고쳤다 |
