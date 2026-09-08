package io.saiden.economyhelper.market.fmp;

import java.util.Comparator;
import java.util.Objects;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.StockOutlook.Dividend;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.UsOutlookClient;
import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.support.FailureReason;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 미국 종목의 목표주가·실적발표일·배당 — FMP.
 *
 * <p>실측, 무료 티어. 목표가·실적발표는 2026-08-21 {@code AAPL}, 배당은 2026-09-07 {@code NVDA}(셋 다 200):
 *
 * <pre>
 * /stable/price-target-consensus  [{"targetHigh":400,"targetLow":245,
 *                                   "targetConsensus":340.72,"targetMedian":360}]
 * /stable/earnings                [{"date":"2026-10-29","epsActual":null,"epsEstimated":1.98},
 *                                  {"date":"2026-07-30","epsActual":2.02, ...}]
 * /stable/dividends               [{"date":"2026-09-10","recordDate":"2026-09-10","paymentDate":"2026-10-01",
 *                                   "declarationDate":"2026-08-26","adjDividend":0.25,"dividend":0.25,
 *                                   "yield":0.2257,"frequency":"Quarterly"},
 *                                  {"date":"2026-06-04","recordDate":"2026-06-04","paymentDate":"2026-06-26", ...}]
 * </pre>
 *
 * <p>⚠️ <b>심볼당 호출이 셋이다.</b> 목표가·실적발표일·배당이 다른 엔드포인트이고 FMP 무료는
 * 배치가 막혀 있다. 하루 250회에서 심볼 하나가 3회를 쓰므로 <b>12시간 캐시가 실질 방어</b>다 —
 * 브리핑의 미국 종목 둘(엔비디아·애플)이 하루 6회를 쓴다.
 *
 * <p><b>셋 → 둘 → 셋이었다.</b> {@code grades-consensus}를 함께 불러 투자의견과 곳 수를 만들다가
 * 그 줄을 화면에서 걷어내면서 <b>호출도 함께 지웠고</b>(화면에서만 빼면 심볼당 하루 한 번을 아무도 안
 * 보는 값에 쓴다), 배당이 그 자리에 왔다.
 *
 * <p>⚠️ <b>실적발표일이 여기에만 있다.</b> 국내에는 무료 출처가 없어 KIS 쪽 전망에는 이 값이
 * 없다 — 그래서 {@code StockOutlook}의 필드가 <b>시장에 따라 채워지는 것이 다르다</b>.
 * 배당은 두 시장 다 있다(국내는 예탁원 배당일정).
 *
 * <p>⚠️ <b>셋 중 하나만 와도 답이다.</b> 무료 티어는 심볼별 허용목록이라 시세와 마찬가지로
 * {@code ORCL}·{@code PATH}가 402일 수 있고(실측 2026-08-20), 배당도 같은 목록에 걸린다
 * ({@code SCHD} 402 — 실측 2026-09-07). 하나가 402여도 다른 것은 살아 있으므로 <b>따로 잡고
 * 따로 버린다</b> — 「셋이 따로 논다」는 {@code StockOutlook}의 규칙이 호출 층에서도 같다.
 *
 * <p>⚠️ <b>실패를 삼키지 않는다.</b> {@link UsOutlookClient}가 빈 값으로 실패한다고 적혀
 * 있었지만, 그러면 아래 {@code @CircuitBreaker}가 정상 반환을 보고 성공을 센다
 * ({@code HackerNewsApi}가 실제로 그 상태였다). <b>셋 다 실패하면 던지고</b> 삼키는 일은
 * {@code StockService}가 한다. 반대로 <b>빈 값은 「값이 없다」는 값</b>이라 던지지 않는다.
 */
@Component
public class FmpUsOutlookClient implements UsOutlookClient {

    private static final Logger log = LoggerFactory.getLogger(FmpUsOutlookClient.class);

    private static final String TARGET = "/stable/price-target-consensus";
    private static final String EARNINGS = "/stable/earnings";
    private static final String DIVIDENDS = "/stable/dividends";

    /**
     * 실적발표일·배당 날짜를 자르는 달력 — <b>그 시장의 것</b>이다.
     *
     * <p>FMP가 주는 날짜는 미국 거래일이므로 KST로 자르면 하루가 어긋난다. 오전 9시 브리핑에서
     * 어제(현지) 발표를 「다음 예정」으로 적는 일이 생긴다 — 날씨가 「조회한 지역의 현지
     * 달력으로 자른다」고 세운 규칙과 같은 자리다.
     */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final FmpQuotaGuard quota;
    private final Clock clock;

    public FmpUsOutlookClient(RestClient.Builder builder,
                              @Value("${economy-helper.market.fmp.base-url}") String baseUrl,
                              @Value("${economy-helper.market.fmp.api-key:}") String apiKey,
                              FmpQuotaGuard quota, Clock clock) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.quota = quota;
        this.clock = clock;
    }

    /**
     * @param symbol {@code AAPL}. 지수({@code ^IXIC})는 부르지 않는다 — 목표주가를 낼 주체가 없다
     * @return 셋 다 못 구했으면 빈 값. 하나라도 있으면 그것만 담아 돌려준다
     * @throws IllegalStateException 키가 없거나 한도를 소진했거나, <b>셋 다</b> 실패했을 때
     */
    @Override
    // ⚠️ Optional을 돌려주던 때가 있었다 — 빈 답이 캐시되지 않아 그 심볼을 검색할 때마다 FMP 하루 250회에서
    //    **셋**을 다시 썼다. 12시간 캐시가 한도의 실질 방어라는 말이 빈 답에서는 거짓이었다. 지금은 빈 값 객체를 담는다
    @Cacheable(cacheNames = CacheNames.US_OUTLOOK, key = "#symbol", unless = "#result == null")
    @RateLimiter(name = "fmp")
    // ⚠️ 브레이커는 시세와 <b>따로</b>다. 리미터는 같이 쓴다 — 하루 250회가 한 예산이라
    //    초당 연타를 막는 일은 둘이 함께 해야 하지만, 「상대가 죽었나」는 갈린다:
    //    허용목록 밖 심볼(PATH·ORCL)의 전망은 언제나 402여서, 한 브레이커면 그 402가
    //    미국 시세의 2순위까지 끊는다. KIS를 kisFx·kisStock으로 나눈 것과 같은 판단이다
    @CircuitBreaker(name = "fmpOutlook")
    public StockOutlook outlook(String symbol) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FMP API 키가 없습니다");
        }

        // ⚠️ 한도 퍼밋은 **순서대로 먼저** 잡는다 — 목표가, 실적발표일, 배당. 호출을 겹친 뒤 각자 퍼밋을
        //    잡게 뒀더니 한도가 하나만 남은 날 어느 쪽이 가져가는지가 경쟁이 됐고, 실적발표일이 가져가면
        //    목표가 쪽이 「한도 소진」으로 던져 답이 통째로 없어졌다(FmpUsOutlookClientTest가 잡았다).
        //    처음부터 한도가 없으면 여기서 그 사유 그대로 던진다
        if (!quota.tryAcquire()) {
            // 어차피 거절당한다. 부르지 않는 편이 빠르고 로그도 깨끗하다
            throw new IllegalStateException("FMP 일일 호출 한도를 소진했습니다");
        }
        // 한도가 호출 사이에서 끝나면 — 이미 잡은 앞 퍼밋을 버리지 않는다. 걸리면 그 심볼의 뒷값이
        // 자정(KST)까지 안 나오는데, 빈손보다 낫다
        boolean earningsPermitted = permit(symbol, "실적발표일");
        boolean dividendsPermitted = permit(symbol, "배당");

        // HTTP만 겹친다 — 세 호출은 서로를 모르고 콜드 1.7초씩이라(실측 p50 1,678ms) 겹치면 하나가 된다.
        // 리미터는 이 메서드에 걸려 있어 퍼밋 수는 그대로다. 각자 HTTP 실패를 값(Fetched.failed)으로
        // 삼키므로 한쪽이 죽어도 다른 쪽이 버려지지 않는다
        Concurrently.Triple<Fetched<Target>, Fetched<List<Earnings>>, Fetched<List<DividendRow>>> fetched =
                Concurrently.three(
                        () -> fetch(TARGET, symbol, new ParameterizedTypeReference<List<Target>>() {}),
                        () -> earningsPermitted
                                ? fetchAll(EARNINGS, symbol, new ParameterizedTypeReference<List<Earnings>>() {})
                                : Fetched.<List<Earnings>>skipped(),
                        () -> dividendsPermitted
                                ? fetchAll(DIVIDENDS, symbol, new ParameterizedTypeReference<List<DividendRow>>() {})
                                : Fetched.<List<DividendRow>>skipped());
        Fetched<Target> target = fetched.first();
        Fetched<List<Earnings>> schedule = fetched.second();
        Fetched<List<DividendRow>> dividends = fetched.third();
        // ⚠️ **빈 배열과 조회 실패를 구분해야 한다.** 둘을 다 null로 뭉치면 「목표가를 낸 곳이
        //    없다」(값)와 「못 물어봤다」(실패)가 같아지고, 그러면 브레이커가 실패를 못 본다.
        //    처음에 그렇게 써 뒀고 FmpUsOutlookClientTest가 그것을 잡았다
        //
        // ⚠️⚠️ **하나라도 받았으면 그것으로 답하고 담는다 — 실패의 종류를 여기서 따지지 않는다.**
        //    한때 「일시 실패(500)가 섞이면 던진다」로 좁혔다. 반쪽이 12시간 캐시에 굳는 것을 막으려는
        //    것이었는데 **대가가 훨씬 컸다**: 던지면 아무것도 안 담겨 그 심볼을 볼 때마다 **퍼밋 3개**를
        //    다시 쓰고(담았을 때는 하루 3개다), 그 예산은 **미국 시세 2순위와 한 지갑**이다.
        //    브레이커도 그것을 못 막는다 — 열려도 HALF_OPEN이 60초마다 3회를 허용해 시간당 540개가
        //    나가고 하루 240이 **27분**에 마른다. 멀쩡한 심볼이 섞이면 실패율이 50%에 못 닿아
        //    **아예 안 열린다.** 한도가 마르면 KIS가 흔들리는 날 미국 시세가 통째로 빈손이 된다 —
        //    이 브레이커를 시세와 가른 이유가 바로 그 모양을 막으려는 것이었다.
        //
        //    그래서 우선순위를 적어 둔다: **한도·폴백 보호 > 보충 한 줄의 신선도.**
        //    치르는 값은 「500 한 번에 배당 줄이 최대 반나절 안 보인다」이고 스스로 낫는다.
        //    (되돌린 것이다 — 적대적 리뷰가 좁히라고 짚었고, 두 번째 검증이 그 대가를 실측했다.)
        if (!target.succeeded() && !schedule.succeeded() && !dividends.succeeded()) {
            // ⚠️ **아무것도 못 받았다.** 전부 402·403이면 그 심볼에 **영영 없는 값**이므로 빈 값을
            //    담는다 — 안 담으면 허용목록 밖 심볼(ORCL·PATH)이 조회마다 퍼밋 3개를 영영 쓴다.
            //    「빈 답도 값이라 담는다」가 이 자리에서만 안 지켜지고 있었다(변경 이전부터).
            //    다시 될 여지가 있는 실패가 하나라도 섞였으면 던진다 — 담을 「값」이 아니기 때문이다.
            if (everyLegPermanentlyBlocked(target, schedule, dividends)) {
                // 이 한 줄이 「그래서 내 한도를 계속 태우나」에 답한다 — 담으므로 안 태운다
                log.info("[fmp] '{}' 전망이 없는 심볼입니다 — 빈 값을 담아 12시간 동안 다시 묻지 않습니다",
                        symbol);
                return StockOutlook.none(StockSource.FMP, clock.instant());
            }
            throw new IllegalStateException("FMP 전망 조회 실패 (" + symbol + ")");
        }

        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        StockOutlook outlook = new StockOutlook(
                nextEarnings(schedule.value(), today),
                target.value() == null ? null : positive(target.value().targetConsensus()),
                nextDividend(dividends.value(), today),
                StockSource.FMP, clock.instant());
        // 비어도 돌려준다 — 값이라 캐시된다. 컨센서스 없는 심볼을 검색할 때마다 FMP 3회를 다시 쓰던 자리다
        return outlook;
    }

    /**
     * 세 다리가 <b>다 물어봤고 다 요금제로 막혔나</b> — 그렇다면 그 심볼의 전망은 <b>영영 없는 값</b>이다.
     *
     * <p>그때만 빈 값을 담는다. <b>「안 물었다」가 섞이면 아니다</b> — 한도로 못 물은 다리는 자정에
     * 다시 물을 수 있으므로, 그것을 「없는 값」으로 12시간 굳히면 오늘 못 본 값을 내일까지 못 본다.
     * 500·타임아웃이 섞이면 더욱 아니다.
     */
    private static boolean everyLegPermanentlyBlocked(Fetched<?>... calls) {
        return java.util.Arrays.stream(calls).allMatch(call -> call.asked()
                && call.failure() != null && FmpApi.planBlocked(call.failure()));
    }

    /** 뒷 호출 하나의 퍼밋 — 못 잡으면 그 값만 빠진다고 로그에 남기고 앞 값은 그대로 간다. */
    private boolean permit(String symbol, String what) {
        boolean permitted = quota.tryAcquire();
        if (!permitted) {
            log.info("[fmp] '{}' {}을 못 물었습니다 — 받은 것만 내보냅니다: 일일 호출 한도 소진", symbol, what);
        }
        return permitted;
    }

    /**
     * 다음 실적발표 예정일 — <b>가장 가까운 앞날 하나.</b>
     *
     * <p>FMP는 지난 분기들을 함께 주고 최신순으로 온다(실측 {@code AAPL}: {@code 2026-10-29}가
     * 첫 행이고 그 아래가 {@code 2026-07-30}·{@code 2026-04-30}). <b>지난 것은 화면이 쓰지
     * 않으므로</b> 오늘 이후만 남기고 그중 가장 이른 것을 고른다.
     *
     * <p>⚠️ <b>첫 행을 그냥 집지 않는다.</b> 정렬을 응답이 지켜 준다는 보장이 없고, 한 번
     * 어긋나면 「다음 발표」 자리에 <b>지난 분기</b>가 적힌다 — 틀린 날짜가 빈손보다 나쁘다.
     *
     * <p>⚠️ <b>{@code epsActual}이 비었는지로 가르지 않는다.</b> FMP가 발표 후 며칠간 그 칸을
     * 안 채우는 일이 있어(실측 {@code 2026-08-19} 발표 건에 {@code epsActual}이 이미 있었지만
     * 그 반대는 확인하지 못했다) 달력이 더 믿을 만하다.
     */
    private static LocalDate nextEarnings(List<Earnings> schedule, LocalDate today) {
        if (schedule == null) {
            return null;
        }
        return schedule.stream()
                .map(Earnings::announcedOn)
                .filter(Objects::nonNull)
                .filter(date -> !date.isBefore(today))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 다음 배당 — 고르는 규칙은 {@link Dividend#nextOf}에 있다(예탁원 쪽과 같은 규칙이다).
     *
     * <p>FMP도 지난 배당을 수십 행 함께 준다(실측 {@code AAPL} 92행·{@code NVDA} 56행, 최신순으로
     * 보였다). 실적발표일과 같은 이유로 첫 행을 집지 않는다. 실측 {@code AAPL}(2026-09-07)은 마지막
     * 배당이 8월에 끝나고 다음이 미선언이라 <b>셋 다 지난 모양</b>이었다 — 그때는 {@code null}이고
     * 화면에 배당 블록이 없다. 그것이 정상이다.
     */
    private static Dividend nextDividend(List<DividendRow> rows, LocalDate today) {
        if (rows == null) {
            return null;
        }
        return Dividend.nextOf(rows.stream()
                .filter(Objects::nonNull)
                .map(row -> Dividend.row(row.recordDate(), row.paymentDate(), row.dividend()))
                .toList(), today);
    }

    /**
     * 한 엔드포인트에서 <b>첫 줄만</b> — 목표가 컨센서스는 심볼당 한 줄이 온다.
     *
     * <p><b>실패를 여기서 삼킨다</b> — 부르는 쪽이 「셋 다 실패했나」를 판단해야 하기 때문이다.
     * 하나가 402여도 다른 것이 살아 있으면 그것만으로 답이 된다.
     */
    private <T> Fetched<T> fetch(String path, String symbol,
                                 ParameterizedTypeReference<List<T>> type) {
        Fetched<List<T>> found = fetchAll(path, symbol, type);
        List<T> rows = found.value();
        // 빈 배열은 성공이다 — 「그 심볼에 목표가를 낸 곳이 없다」는 값이다
        return new Fetched<>(rows == null || rows.isEmpty() ? null : rows.get(0),
                found.failure(), found.asked());
    }

    /**
     * 한 엔드포인트의 <b>줄 전체</b> — 실적발표는 여러 분기가, 배당은 여러 해가 온다.
     *
     * <p>{@link #fetch}가 이것을 감싼다. 호출 하나를 두 모양으로 읽는 것이라 <b>URL 조립과
     * 키 가리기가 한 곳에만</b> 있다 — 두 벌로 두면 키를 가리는 쪽만 고쳐지는 날이 온다.
     *
     * <p>한도 퍼밋은 여기서 잡지 않는다 — {@link #outlook}이 세 호출 몫을 순서대로 먼저 잡는다.
     */
    private <T> Fetched<List<T>> fetchAll(String path, String symbol,
                                          ParameterizedTypeReference<List<T>> type) {
        String uri = baseUrl + path + "?symbol=" + encode(symbol) + "&apikey=" + apiKey;
        try {
            return new Fetched<>(restClient.get().uri(URI.create(uri)).retrieve().body(type),
                    null, true);
        } catch (RuntimeException e) {
            // ⚠️ 예외 메시지에 apikey가 박힌 URL이 들어 있다 — 그대로 흘리면 키가 유출된다.
            //    FailureReason은 상태 코드와 예외 이름만 주므로 키가 새지 않는다.
            //    예외 **객체**는 들고 있되(영구·일시를 가르려면 상태 코드가 필요하다)
            //    밖으로 나가는 메시지에는 그 객체를 얹지 않는다
            //
            // ⚠️ **영구와 일시를 로그가 갈라 말해야 한다.** 「조회 실패: HTTP 402」라고만 적던 동안
            //    허용목록 밖 심볼(JEPI·SCHD)의 줄이 **고칠 것처럼 읽혀** 신고가 들어왔다 —
            //    그건 요금제가 그 심볼을 안 주는 것이고 다시 물어도 같다. FmpApi가 시세 쪽에서
            //    같은 구분을 이미 하고 있었는데(planBlocked) 이 로그만 안 하고 있었다
            if (FmpApi.planBlocked(e)) {
                log.info("[fmp] '{}' {} — 요금제가 이 심볼을 안 줍니다(다시 물어도 같습니다)",
                        symbol, path);
            } else {
                log.info("[fmp] '{}' {} 조회 실패: {}", symbol, path, FailureReason.of(e));
            }
            return new Fetched<>(null, e, true);
        }
    }

    /**
     * 한 엔드포인트의 결과 — <b>세 상태다: 값을 받았다 · 실패했다 · 한도로 못 물었다.</b>
     *
     * <p>둘로는 못 든다. 「없다」(빈 배열)와 「못 물어봤다」를 뭉치면 브레이커가 실패를 못 보고,
     * <b>실패의 종류</b>를 잃으면 허용목록 402(캐시해도 되는 것)와 500(안 되는 것)을 못 가른다 —
     * 그래서 {@code boolean}이 아니라 <b>예외 객체</b>를 든다.
     *
     * <p>⚠️ 그 예외를 <b>메시지로 흘리지 않는다</b> — FMP 예외에는 apikey가 박힌 URL이 들어 있다.
     * 상태 코드를 읽는 데만 쓰고, 밖으로 나가는 것은 {@link FailureReason}이 만든 줄이다.
     *
     * @param value   받은 것. 빈 배열이면 {@code null}이고 그건 <b>값</b>이다
     * @param failure 실패했으면 그 예외. 물었고 이것이 {@code null}이면 성공이다
     * @param asked   실제로 물었나. 한도가 없어 안 물은 다리는 거짓이다
     */
    private record Fetched<T>(T value, RuntimeException failure, boolean asked) {

        /** 한도가 없어 묻지 않은 것 — 성공은 아니지만 <b>다시 물어도 오늘은 같다</b>. */
        static <T> Fetched<T> skipped() {
            return new Fetched<>(null, null, false);
        }

        boolean succeeded() {
            return asked && failure == null;
        }
    }

    /** {@code 0}은 목표가가 아니다 — 평균에 넣으면 실제보다 낮은 값이 화면에 나간다. */
    private static BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * ⚠️ <b>{@code targetConsensus}를 쓴다.</b> {@code targetHigh}는 가장 낙관적인 한 곳이라
     * 화면에 내면 목표주가를 부풀린다(실측 {@code AAPL}: 고가 400 · 컨센서스 340.72).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Target(BigDecimal targetConsensus) {
    }

    /**
     * 실적발표 한 분기.
     *
     * <p>⚠️ <b>{@code date}가 예약어처럼 겹치기 쉬워 이름을 달리 둔다.</b> {@code @JsonProperty}로
     * 붙이므로 필드 이름은 우리 마음이고, {@code announcedOn}이 「그날 발표한다」를 그대로 말한다.
     *
     * <p><b>실적 수치는 담지 않는다.</b> {@code epsEstimated}·{@code revenueActual}이 함께 오지만
     * 화면이 쓰는 것은 <b>날짜뿐</b>이다 — 담아 두면 「언젠가 쓸지도 모르는 필드」가 되고,
     * 이번 세션에 계속 걷어낸 부류가 그것이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Earnings(@JsonProperty("date") LocalDate announcedOn) {
    }

    /**
     * 배당 한 건 — 화면이 쓰는 셋만 담는다.
     *
     * <p>⚠️ <b>{@code date}(배당락일)는 읽지 않는다.</b> 화면은 <b>기준일</b>을 적기로 했다 — 두 시장의
     * 출처가 다 기준일을 직접 주고, 국내는 락일을 만들 휴장일 달력이 없어 한 통에 두 뜻의 날짜가 서는
     * 것을 피한다. {@code yield}·{@code frequency}·{@code declarationDate}도 같은 이유로 안 담는다.
     *
     * <p><b>{@code adjDividend}가 아니라 {@code dividend}다.</b> 조정치는 분할을 거슬러 옛 배당을
     * 지금 주식 수로 환산한 값이라 지난 행에서만 갈린다 — 우리는 앞날만 보므로 선언된 금액 그대로가 맞다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DividendRow(LocalDate recordDate, LocalDate paymentDate, BigDecimal dividend) {
    }
}
