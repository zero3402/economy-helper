# config

스프링 조립 — 캐시(`CacheConfig` · `CacheNames` · `CacheErrorConfig`), 회복탄력성(`ResilienceConfig`),
타임아웃(`HttpTimeouts`), 스케줄(`SchedulingConfig`), 설정 바인딩(`EconomyHelperProperties`).

## 검증

- `cd backend && ./gradlew test --tests 'io.saiden.economyhelper.config.*'`
- 여기 테스트 대부분은 **스프링을 띄워** 설정이 실제로 적용되는지 본다 — 값만 읽는 단언으로 바꾸지 않는다.

## 규칙

**캐시**

- **캐시 이름 하나에 타입 하나.** 등록 누락은 `CacheConfigTest`가 잡는다 → `docs/design.md` 4.2
- 판 번호는 **규칙이 바뀌었을 때**와 **담는 모양이 바뀌었을 때** 올린다. 모양 쪽은 수명이 짧아도 올린다 — 롤링 배포 중 옛 항목을 적중으로 읽는다(`CacheNames` javadoc) → `docs/design.md` 4.2
- 캐시는 리미터·브레이커보다 **바깥**이다(`@EnableCaching(order = LOWEST_PRECEDENCE - 10)`). 순서는 `ResilienceConfigTest`가 검사한다 → `docs/design.md` 4.2
- 캐시는 부가 기능이다 — Redis가 죽어도 답은 나간다(`CacheErrorConfig`).

**회복탄력성**

- 리미터는 앱키 단위, 브레이커는 출처 단위, 재시도는 폴백 없는 곳만 → `docs/design.md` 4.4
- ⚠️ `ignoreExceptions`·`retryExceptions`는 `baseConfig`를 **덮어쓴다**. 이어 붙지 않는다.
- ⚠️ `retry`의 `configs.default`는 **자동으로 안 붙는다** — 인스턴스마다 `baseConfig: default`를 적는다. 빠뜨리면 모든 예외에 세 번 부른다. `ratelimiter`에는 `configs`가 아예 없다.

**설정 바인딩**

- ⚠️ **설정은 `EconomyHelperProperties` 레코드로만 읽는다.** `@Value`를 되살려 겹쳐 읽지 않는다 — 결측 의미가 다르다(`EconomyHelperProperties` javadoc).
- 기본값은 성분에 `@DefaultValue`로 적는다. 기본값이 없는 값(`*base-url` 등)은 빠져도 조용히 `null`이라 `EconomyHelperPropertiesTest`가 그 자리를 지킨다.
- 새 환경변수는 `backend/.env.example`에 「없으면 무엇이 안 되는지」와 함께 적는다. 그 파일이 키 이름의 정본이다.
