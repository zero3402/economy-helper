package io.saiden.economyhelper.config;

/**
 * 캐시 이름 전부 — <b>그리고 그중 어느 것에 판(version) 번호가 붙는지.</b>
 * (개수는 적지 않는다 — {@code CacheConfigTest}가 센다.)
 *
 * <p><b>왜 상수로 모으는가.</b> 이름은 {@code @Cacheable}과 {@link CacheConfig} 두 곳에서 쓰인다.
 * 어긋나면 그 캐시가 Redis 기본값(JDK 직렬화·무기한)으로 떨어져 레코드를 담는 순간 예외가 난다 —
 * {@code CacheConfigTest.configuresEveryDeclaredCache}가 그 그물이다.
 *
 * <h2>⚠️ 파생 규칙이나 프롬프트를 고치면 판 번호를 올린다</h2>
 *
 * <p>올리면 옛 키는 아무도 읽지 않고 TTL이 지나 스스로 사라진다. <b>안 올리면 고침이 최대
 * 한 달 뒤에 보인다</b> — 실제로 물렸고, 그 사고의 전말은 {@code docs/design.md} 4.2의
 * 「긴 TTL 캐시가 고침보다 오래 산다」절에 있다. 여기서는 규칙만 든다.
 *
 * <p><b>판을 매기는 것과 안 매기는 것</b>
 *
 * <ul>
 *   <li><b>매긴다</b> — 값이 <i>우리 규칙</i>의 산물인 캐시. 지오코딩(후보 선택 규칙)과
 *       해석기 셋(LLM 프롬프트)이다. 이것들은 상대가 준 것을 그대로 담지 않는다
 *   <li><b>안 매긴다</b> — 값이 <i>상대가 준 것</i>이고 수명이 초·분 단위인 캐시
 *       ({@code crypto-price} 10초 · {@code kis-quote} 1분 · {@code fx} 1분). 규칙을 고쳐도
 *       한 숨에 스스로 낫는다. 판을 매기면 노브만 늘고 지킬 것이 는다
 * </ul>
 *
 * <p>{@code accu-location}(30일)과 {@code kis:excd:*}(30일)도 오래 살지만 판을 안 매긴다 —
 * 담는 것이 상대가 확정한 식별자(지점 키·거래소 코드)라서 우리 규칙이 바뀌어도 값이 안 바뀐다.
 * ({@code kis:excd:*}는 애초에 {@code @Cacheable}이 아니라 {@code StringRedisTemplate} 직접
 * 사용이라 여기 없다.)
 */
public final class CacheNames {

    private CacheNames() {
    }

    // ── 판을 매긴 것 넷: 값이 우리 규칙의 산물이다 ──────────────────────────────

    /**
     * 지명 → 좌표. <b>v2</b> — {@code 72076e8}이 후보 선택 규칙을 바꿨다(인구 없는 후보를
     * 첫 줄로 집던 것을 빈손으로) 그리고 표시 이름도 바꿨다(로마자면 물어본 이름으로).
     * v1에는 그 전 규칙이 만든 {@code Seongnam} 같은 값이 30일 남아 있다.
     */
    public static final String GEOCODE = "geocode-v2";

    /**
     * 날씨 검색어 해석. <b>v4</b> — 키가 날짜 기호를 지키고({@code 1/11}≠{@code 11/1}), 프롬프트가 친 그대로의 검색어(띄어쓰기·날짜 기호)를 보고,
     * 「주말」을 일수 2가 아니라 {@code weekend}로 받는다. v3에는 공백을 지운 입력으로 낸 답과
     * 「주말 → 오늘·내일」이 7일 남는다(v3: LLM의 {@code "null"} 리터럴을 담기 전에 다듬었다).
     */
    public static final String WEATHER_RESOLVE = "weather-resolve-v4";

    /**
     * 종목 검색어 해석. <b>v5</b> — 프롬프트가 <b>국내 ETF</b>를 다루게 됐다({@code 타임나스닥100 →
     * TIME 미국나스닥100액티브}, 코드는 확실할 때만). v4에는 그 규칙이 없던 프롬프트가 만든
     * 빈손·지어낸 코드가 7일 남는다.
     */
    public static final String STOCK_RESOLVE = "stock-resolve-v5";

    /**
     * 코인 검색어 해석. <b>v3</b> — 담는 티커를 해석기가 다듬어(대문자·앞뒤 공백 제거) 넣는다.
     * v2에는 LLM이 준 날것이 담겨 있다.
     */
    public static final String CRYPTO_RESOLVE = "crypto-resolve-v3";

    // ── 판을 안 매긴 것들: 상대가 준 값이거나 수명이 짧다 ────────────────────────

    /** 기사 목록. 10분 — 09시 발송 창(09~10시, 10분마다)이 같은 기사를 다시 집지 않게 하는 값이다. */
    public static final String FEED = "feed";

    /** 기사 번역. 7일 — 같은 기사를 두 번 번역하지 않는다. */
    public static final String TRANSLATION = "translation";

    /** Hacker News 반응. 1시간 — 천천히 쌓인다. */
    public static final String HN_BUZZ = "hn-buzz";

    /** 검색어 번역. 30일 — {@code 비트코인 → bitcoin}은 낡지 않는다. */
    public static final String QUERY = "query";

    /** 기사 관련도. 7일 — 배치 단위다. 기사별로 쪼개면 Gemini 호출이 늘어난다. */
    public static final String RELEVANCE = "relevance";

    /** 업비트 원화 마켓 목록. 6시간 — 상장·폐지는 드물다. */
    public static final String UPBIT_MARKETS = "upbit-markets";

    /**
     * KIS 종목 마스터(국내 상장 종목 전부 — 이름·코드·시가총액). 6시간 — 파일이 하루 한 번(07:40)
     * 갱신되고 상장·폐지는 드물다. 「이름 → 코드」 색인의 재료라 이것이 없으면 국내 ETF를
     * 이름으로 못 찾는다. 판을 안 매긴다 — 상대가 준 값 그대로다.
     */
    public static final String KR_LISTINGS = "kr-listings";

    /**
     * 업비트 시세. 10초 — 시세다. 길게 잡으면 거짓말이 된다.
     *
     * <p>⚠️ <b>v2: 담는 모양이 바뀌었다</b>(업비트 응답의 snake_case·비율·epoch millis → 도메인 {@code UpbitTicker}의 값 객체).
     * 수명이 짧아도 판을 올린다 — 롤링 배포 중에는 옛 인스턴스가 옛 모양을 계속 쓰고, 새 인스턴스는
     * 그것을 <b>적중</b>으로 읽어 가격·거래대금이 {@code null}인 시세를 낸다(Jackson은 모르는 필드를
     * 조용히 버린다). 짧은 캐시가 판을 안 매기는 것은 <b>규칙</b>이 바뀔 때이지 <b>모양</b>이 바뀔 때가 아니다.
     */
    public static final String CRYPTO_PRICE = "crypto-price-v2";

    /**
     * 바이낸스 시세. 10초 — 두 거래소 값의 시각이 어긋나면 비교가 무의미해진다.
     *
     * <p>⚠️ <b>v2: 담는 모양이 바뀌었다</b>(맨 숫자 → {@code Price}·{@code PercentChange}, 필드
     * {@code priceChangePercent} → {@code change}). 옛 판에 담긴 {@code 0}은 {@code Price}가 읽기를
     * 거절한다 — 판을 안 올리면 롤링 배포 중 캐시 적중이 예외가 된다.
     */
    public static final String BINANCE_PRICE = "binance-price-v2";

    /** 국내 전일 종가. 1시간 — 일 1만회 한도를 이걸로 지킨다. */
    public static final String STOCK_PRICE = "stock-price";

    /** 국내 지수 전일 종가. 담기는 타입이 달라 {@link #STOCK_PRICE}에 섞을 수 없다. */
    public static final String MARKET_INDEX = "market-index";

    /** FMP 미국 현재가. 1분 — 하루 250회는 캐시가 아니라 {@code FmpQuotaGuard}가 지킨다. */
    public static final String US_QUOTE = "us-quote";

    /** KIS 시세 셋(국내 종목·국내 지수·미국). 1분 — 가르는 것은 키 접두사다. */
    public static final String KIS_QUOTE = "kis-quote";

    /**
     * 국내 종목의 <b>목표주가·배당</b>. 시세와 <b>수명이 전혀 다르다</b> — 증권사는 분기에 몇 번
     * 낼 뿐이고 배당 일정도 분기에 한 번 잡히므로 하루를 캐시해도 낡지 않는다. 그리고 그 길이가
     * 이 기능의 실질 방어다: 브리핑이 종목마다 <b>조회를 둘</b> 더 하는데(투자의견·배당일정)
     * KIS는 <b>호출 사이 1초</b>를 지키므로, 캐시가 없으면 브리핑이 종목 수의 두 배만큼 더 늦어진다.
     *
     * <p><b>판은 안 매긴다</b> — 담기는 것이 상대가 준 값이다. 모양이 바뀌면 옛 항목의 새 필드가
     * {@code null}로 읽혀 최대 12시간 그 블록이 안 나오고 스스로 낫는다.
     */
    public static final String KIS_OUTLOOK = "kis-outlook";

    /**
     * 미국 종목의 <b>목표주가·실적발표일</b>(FMP). <b>심볼당 FMP 호출이 둘</b>
     * ({@code price-target-consensus}·{@code earnings}가 다른 엔드포인트이고 무료 티어는 배치가
     * 막혔다)이라 하루 250회에서 심볼 하나가 2회를 쓴다 — 이 캐시가 그 한도의 실질 방어다.
     * 배당은 {@link #US_DIVIDEND}에 따로 담긴다.
     *
     * <p><b>판은 안 매긴다</b> — 담기는 것이 상대가 준 값이다.
     */
    public static final String US_OUTLOOK = "us-outlook";

    /**
     * 미국 종목의 <b>배당</b> — Polygon. {@link #US_OUTLOOK}과 <b>따로 둔다.</b>
     *
     * <p>출처가 다르므로 한쪽 장애가 다른 쪽 답을 지우면 안 된다. FMP가 막힌 심볼
     * ({@code SCHD}·{@code JEPI}·{@code QQQ})은 목표가·실적발표일이 없는 채로 배당만 나오는데,
     * 한 항목에 담으면 그 빈 전망이 배당까지 12시간 덮는다.
     *
     * <p>판은 안 매긴다 — 상대가 준 값이다.
     */
    public static final String US_DIVIDEND = "us-dividend";

    /**
     * 환율 일봉 — 차트가 그리는 것.
     *
     * <p><b>시세({@link #FX})와 따로 두는 이유가 수명이다.</b> 시세는 1분이고 일봉은 하루에
     * 한 번 바뀐다 — 한 이름에 담으면 하루에 한 번 바뀌는 값을 1분마다 다시 받는다.
     * 그리고 「이름 하나에 타입 하나」라 담기는 것이 {@code List<DailyBar>}인 이 값은 제 이름이 필요하다.
     */
    public static final String FX_SERIES = "fx-series";

    /**
     * 코인 일봉. <b>세 도메인 중 여기만 진짜 새 호출</b>이라(환율·주식은 시세 응답에 이미 온다)
     * 캐시가 하는 일이 가장 크다 — 업비트는 키가 없지만 초당 한도는 있다.
     */
    public static final String CRYPTO_SERIES = "crypto-series";

    /**
     * 국내 종목 일봉. <b>시세 응답에 이미 오는 값이지만 따로 담는다</b> — 시세는 1분이고
     * 일봉은 하루에 한 번 바뀌어서, 한 이름에 담으면 하루치 값을 1분마다 다시 받는다.
     */
    public static final String STOCK_SERIES = "stock-series";

    // ⚠️ 환율 셋은 v2다 — 담는 모양이 바뀌었다(FxRate에 live가 붙었다). 옛 판의 항목은 live가 없어
    //    거짓으로 읽히고, 실시간 값이 「(종가)」로 찍힌다. 수명이 짧아도 모양이 바뀌면 올린다(CRYPTO_PRICE와 같다)

    /** 유럽중앙은행 환율. */
    public static final String FX = "fx-v2";

    /** 수출입은행 환율. 1시간 — 하루 1,000회 한도의 실질 방어다. */
    public static final String FX_KEXIM = "fx-kexim-v2";

    /** KIS 환율. 1분 — 화면에 '읽은 시각'을 찍으므로 이보다 길면 안 된다. */
    public static final String FX_KIS = "fx-kis-v2";

    /** 예보. 10분 — 길게 잡으면 '오늘 날씨'가 어제 예보가 된다. */
    public static final String WEATHER = "weather";

    /**
     * 강수 시각(날짜별 토막). 예보와 같은 급인 10분이다.
     *
     * <p>⚠️ {@link #WEATHER}와 나눠 쓰지 않는다 — 그쪽은 {@code Weather} 타입으로 못 박혀 있어
     * 다른 것을 담으면 쓸 때는 넘어가고 <b>읽을 때 깨진다</b>. 「이름 하나에 타입 하나」다.
     */
    public static final String PRECIPITATION_HOURS = "precipitation-hours";

    /** 좌표 → AccuWeather 지점 키. 30일 — 하루 50회 한도의 실질 방어다. */
    public static final String ACCU_LOCATION = "accu-location";
}
