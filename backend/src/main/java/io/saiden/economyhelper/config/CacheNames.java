package io.saiden.economyhelper.config;

/**
 * 캐시 이름 스물하나 — <b>그리고 그중 어느 것에 판(version) 번호가 붙는지.</b>
 *
 * <p><b>왜 상수로 모으는가.</b> 이름이 {@code @Cacheable}과 {@link CacheConfig} 두 곳에
 * 문자열로 적혀 있었다. 어긋나면 그 캐시가 Redis 기본값(JDK 직렬화·무기한)으로 떨어져
 * 레코드를 담는 순간 예외가 난다 — {@code CacheConfigTest.configuresEveryDeclaredCache}가
 * 그 누락을 잡으려고 있는 그물이다. 한 곳에서 오면 어긋날 자리가 없어진다.
 *
 * <h2>⚠️ 파생 규칙이나 프롬프트를 고치면 판 번호를 올린다</h2>
 *
 * <p>올리면 옛 키는 아무도 읽지 않고 TTL이 지나 스스로 사라진다. <b>안 올리면 고침이 최대
 * 한 달 뒤에 보인다</b> — 실제로 물렸고, 그 사고의 전말은 {@code CLAUDE.md}의
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

    /** 날씨 검색어 해석. <b>v2</b> — 프롬프트가 "정식 행정명으로" 규칙을 얻어 답이 바뀌었다. */
    public static final String WEATHER_RESOLVE = "weather-resolve-v2";

    /** 종목 검색어 해석. <b>v2</b> — 판 번호 관례를 넷에 함께 세운다. */
    public static final String STOCK_RESOLVE = "stock-resolve-v2";

    /** 코인 검색어 해석. <b>v2</b> — 같다. */
    public static final String CRYPTO_RESOLVE = "crypto-resolve-v2";

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

    /** 업비트 시세. 10초 — 시세다. 길게 잡으면 거짓말이 된다. */
    public static final String CRYPTO_PRICE = "crypto-price";

    /** 바이낸스 시세. 10초 — 두 거래소 값의 시각이 어긋나면 비교가 무의미해진다. */
    public static final String BINANCE_PRICE = "binance-price";

    /** 국내 전일 종가. 1시간 — 일 1만회 한도를 이걸로 지킨다. */
    public static final String STOCK_PRICE = "stock-price";

    /** 국내 지수 전일 종가. 담기는 타입이 달라 {@link #STOCK_PRICE}에 섞을 수 없다. */
    public static final String MARKET_INDEX = "market-index";

    /** FMP 미국 현재가. 1분 — 하루 250회는 캐시가 아니라 {@code FmpQuotaGuard}가 지킨다. */
    public static final String US_QUOTE = "us-quote";

    /** KIS 시세 셋(국내 종목·국내 지수·미국). 1분 — 가르는 것은 키 접두사다. */
    public static final String KIS_QUOTE = "kis-quote";

    /**
     * 국내 종목의 목표주가·투자의견. 시세와 <b>수명이 전혀 다르다</b> — 증권사는 분기에 몇 번
     * 낼 뿐이라 하루를 캐시해도 낡지 않는다. 그리고 그 길이가 이 기능의 실질 방어다:
     * 브리핑이 종목마다 조회를 하나 더 하는데 KIS는 <b>호출 사이 1초</b>를 지키므로,
     * 캐시가 없으면 브리핑이 종목 수만큼 더 늦어진다.
     */
    public static final String KIS_OUTLOOK = "kis-outlook";

    /** 유럽중앙은행 환율. */
    public static final String FX = "fx";

    /** 수출입은행 환율. 1시간 — 하루 1,000회 한도의 실질 방어다. */
    public static final String FX_KEXIM = "fx-kexim";

    /** KIS 환율. 1분 — 화면에 '읽은 시각'을 찍으므로 이보다 길면 안 된다. */
    public static final String FX_KIS = "fx-kis";

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
