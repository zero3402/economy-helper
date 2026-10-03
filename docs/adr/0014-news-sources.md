# ADR-0014 — 뉴스 매체: 무료로 읽히는 매체만, 그 매체의 기사만

- **상태**: 채택
- **범위**: `news/**` · `backend/src/main/resources/application.yml`(피드 목록)

## 맥락

브리핑과 `/news`의 기사는 RSS 피드에서 모은다. 링크를 눌러도 못 읽는 기사는 답이 아니다. 피드는 남의 기사를 실어 나르기도 하고,
코인 기사는 금융 일반 피드에 거의 실리지 않는다.

## 결정

| 무리 | 매체 |
|---|---|
| 경제 (글로벌 점유율 순) | Yahoo Finance · Investing.com · CNBC · BBC Business · AP News |
| 코인 | Investing.com *(암호화폐 섹션)* · CoinDesk · Cointelegraph |

1. **페이월 매체는 쓰지 않는다** — 블룸버그·로이터·파이낸셜 타임즈·이코노미스트를 뺐다. **페이월 우회 도구(archive.ph 등)로
   되살리지 않는다.** 새 매체도 같은 기준이다.
2. **그 매체 피드에서는 그 매체 기사만 쓴다**(`NewsSource.owns`, `syndicatedFromPaywall`).
3. **매체는 일곱, 피드는 여덟이다** — Investing.com만 본 섹션과 암호화폐 섹션을 함께 단다. 화면 표기는 Investing.com 그대로다.
4. **코인 매체 둘(CoinDesk·Cointelegraph)을 세운다** — 암호화폐 섹션 하나로는 코인 다섯 자리가 안 찬다.
5. **봇 검문을 우회하지 않는다.** 우리는 피드만 받고 기사 페이지는 받지 않는다.
6. **피드 주소 끝에 슬래시를 붙이지 않는다** — RestClient가 리다이렉트를 안 따라간다. `EconomyHelperPropertiesTest.feedUrlsDoNotEndWithASlash`가 그물이다.
7. 제목·요약의 HTML 엔티티를 풀고 태그를 걷는다(`RssFeedClient.clean`).

## 결과

- 통신사 자리를 AP로 대신한다.
- 관련도 채점이 매체당 1회라 매체를 늘리면 Gemini 여유가 준다(코인 매체 둘로 6 → 3).

## 근거 (실측)

- **페이월 섞임**: Yahoo 피드 48건 중 8건이 `wsj.com`·`investors.com`(페이월)이었다.
- **코인 기사 부족**: 일반 피드 Yahoo 49건 중 3건, 나머지 넷은 0건. Investing.com 암호화폐 섹션은 10건 중 24시간 안이 6건(2026-08-27).
  둘을 더해 코인 풀이 6건 → 60건대가 됐다.
- **CoinDesk·Cointelegraph** (2026-08-27): 피드 25·30항목, 자사 호스트, 요약 평문 87~166자, 페이월·로그인 없음. 앱 UA로 피드는 둘 다
  200. CoinDesk 기사 페이지는 앱 UA에 429 `Vercel Security Checkpoint`, `TelegramBot` UA에 200 — 봇 검문이지 페이월이 아니다.
- **수집량** (2026-08-27, 운영 코드 경로): 24시간 창 안에 코인 45건 · 경제 125건(AP 98 · BBC 18 · Investing 5 · CNBC·Yahoo 4 ·
  CoinDesk 19 · Cointelegraph 19 · Investing 암호화폐 7). 경제 125건 중 코인 오분류 0건.
- **끝 슬래시**: CoinDesk를 `.../outboundfeeds/rss/`로 적으면 308 → 15바이트 `Redirecting...` → `SAXParseException`, 수집 0건인데
  골든·단위 테스트는 초록이었다(실물 감사가 잡았다). `curl -L`로 재면 200으로 보인다.
- **엔티티** (2026-10-03): 「S&amp;amp;P」·「&lt;p&gt;」가 화면에 찍히던 것을 고쳤다.
