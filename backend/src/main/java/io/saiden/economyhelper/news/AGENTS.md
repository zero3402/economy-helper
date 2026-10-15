# news

아침 브리핑 뉴스(코인 5 + 경제 5)와 `/news`(`/n`) — RSS 수집, 점수 매기기, 한글 번역.

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.news.*'`
- 피드 URL을 바꿨다면 `--tests '*EconomyHelperPropertiesTest'`도 돌린다.

## 규칙

**매체**

- 페이월 매체는 쓰지 않는다. 한 매체의 피드에서는 그 매체 기사만 쓴다(`NewsSource.owns`) → ADR-0014
- 피드 URL 끝에 슬래시를 붙이지 않는다 → ADR-0014
- 기사 페이지의 봇 검문을 우회하지 않는다(피드만 받는다) → ADR-0014
- 수집량이 이상하면 ADR의 기준 수치와 비교한다 → ADR-0014
- 피드 수집 실패는 빈 목록이 아니라 예외다(`ArticleFeed.Unavailable`). 설정이 없는 매체도 실패다. 한 매체는 건너뛰고, 전부 실패하면 `NewsFacade.Unavailable` — 「찾지 못했습니다」가 아니라 「잠시 후 다시」다
- 기준 시각보다 1시간 넘게 미래인 기사는 버린다(`NewsService.recent`) — 시간대가 틀린 피드가 신선도 만점으로 늘 1위에 서던 것

**분류와 배치**

- 브리핑은 카테고리(`NewsCategory`)마다 다섯 자리씩 채운다. 모자란 카테고리를 다른 카테고리 기사로 채우지 않는다 → ADR-0015
- 화면에서는 점수순으로 섞고 카테고리 제목을 달지 않는다. 검색 답은 카테고리로 나누지 않는다 → ADR-0016
- 코인 판정은 「코인 전용 피드인가 → 코인 단어가 있는가」 두 규칙뿐이다. LLM에게 묻지 않고, 캐시에 담지 않는다. 짧은 티커는 단어 목록에 넣지 않는다 → ADR-0015

**검색과 번역**

- `/news`만 검색어 없이도 답한다(`Command`의 인자 상태 세 가지) → ADR-0016
- 검색어의 영문 단어는 단어 경계로 맞추고 `&`·`.`을 지킨다. 조사는 떼고도 3글자 이상 남을 때만 뗀다. 번역할 단어는 최대 5개 → ADR-0016
- 번역 규칙은 `translate/AGENTS.md`, Gemini 호출 규칙은 `infrastructure/llm/AGENTS.md`를 따른다.
