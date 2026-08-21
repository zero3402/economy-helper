package io.saiden.economyhelper.market.fmp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.UsOutlookClient;
import io.saiden.economyhelper.support.FailureReason;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 미국 종목의 목표주가·투자의견 — FMP.
 *
 * <p><b>국내와 달리 FMP는 이미 합쳐서 준다.</b> {@code InvestOpinions}가 국내에서 증권사별
 * 최신 건을 손으로 접어 만드는 값을 FMP는 컨센서스로 바로 준다. 그래서 두 시장의 숫자가
 * 같은 뜻이 된다 — 「몇 곳이 낸 의견인가」도 양쪽 다 실제 곳 수다.
 *
 * <p>실측 2026-08-21, 무료 티어, {@code AAPL} 셋 다 200:
 *
 * <pre>
 * /stable/grades-consensus        {"strongBuy":1,"buy":69,"hold":32,"sell":9,
 *                                  "strongSell":0,"consensus":"Buy"}
 * /stable/price-target-consensus  {"targetHigh":400,"targetLow":245,
 *                                  "targetConsensus":340.72,"targetMedian":360}
 * /stable/earnings                [{"date":"2026-10-29","epsActual":null,"epsEstimated":1.98},
 *                                  {"date":"2026-07-30","epsActual":2.02, ...}]
 * </pre>
 *
 * <p>⚠️ <b>심볼당 호출이 셋이다.</b> 목표가·의견·실적발표일이 각각 다른 엔드포인트이고 FMP
 * 무료는 배치가 막혀 있다. 하루 250회에서 심볼 하나가 3회를 쓰므로 <b>12시간 캐시가 실질
 * 방어</b>다 — 그것 없이 브리핑(미국 종목 둘)과 검색이 겹치면 한도가 눈에 보이게 준다.
 *
 * <p>⚠️ <b>실적발표일이 여기에만 있다.</b> 국내에는 무료 출처가 없어 KIS 쪽 전망에는 이 값이
 * 없다 — 그래서 {@code StockOutlook}의 세 필드가 <b>시장에 따라 채워지는 것이 다르다</b>.
 *
 * <p>⚠️ <b>셋 중 하나만 와도 답이다.</b> 무료 티어는 심볼별 허용목록이라 시세와 마찬가지로
 * {@code ORCL}·{@code PATH}가 402일 수 있다(실측 2026-08-20). 하나가 402여도 다른 것은
 * 살아 있으므로 <b>따로 잡고 따로 버린다</b> — 「셋이 따로 논다」는 {@code StockOutlook}의
 * 규칙이 호출 층에서도 같다.
 *
 * <p>⚠️ <b>실패를 삼키지 않는다.</b> {@link UsOutlookClient}가 빈 값으로 실패한다고 적혀
 * 있었지만, 그러면 아래 {@code @CircuitBreaker}가 정상 반환을 보고 성공을 센다
 * ({@code HackerNewsApi}가 실제로 그 상태였다). <b>셋 다 실패하면 던지고</b> 삼키는 일은
 * {@code StockService}가 한다. 반대로 <b>빈 값은 「의견이 없다」는 값</b>이라 던지지 않는다.
 */
@Component
public class FmpUsOutlookClient implements UsOutlookClient {

    private static final Logger log = LoggerFactory.getLogger(FmpUsOutlookClient.class);

    private static final String GRADES = "/stable/grades-consensus";
    private static final String TARGET = "/stable/price-target-consensus";
    private static final String EARNINGS = "/stable/earnings";

    /**
     * 실적발표일을 자르는 달력 — <b>그 시장의 것</b>이다.
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
    @Cacheable(cacheNames = CacheNames.US_OUTLOOK, key = "#symbol")
    @RateLimiter(name = "fmp")
    @CircuitBreaker(name = "fmp")
    public Optional<StockOutlook> outlook(String symbol) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FMP API 키가 없습니다");
        }

        Fetched<Grades> grades = fetch(GRADES, symbol,
                new ParameterizedTypeReference<List<Grades>>() {});
        Fetched<Target> target = fetch(TARGET, symbol,
                new ParameterizedTypeReference<List<Target>>() {});
        Fetched<List<Earnings>> schedule = fetchAll(EARNINGS, symbol,
                new ParameterizedTypeReference<List<Earnings>>() {});
        // ⚠️ **빈 배열과 조회 실패를 구분해야 한다.** 둘을 다 null로 뭉치면 「의견을 낸 곳이
        //    없다」(값)와 「못 물어봤다」(실패)가 같아지고, 그러면 브레이커가 실패를 못 본다.
        //    처음에 그렇게 써 뒀고 FmpUsOutlookClientTest가 그것을 잡았다
        if (grades.failed() && target.failed() && schedule.failed()) {
            throw new IllegalStateException("FMP 전망 조회 실패 (" + symbol + ")");
        }

        StockOutlook.Rating rating = grades.value() == null
                ? null
                : StockOutlook.Rating.ofLabel(grades.value().consensus()).orElse(null);
        StockOutlook outlook = new StockOutlook(
                nextEarnings(schedule.value()),
                target.value() == null ? null : positive(target.value().targetConsensus()),
                rating,
                grades.value() == null ? null : grades.value().analystCount(),
                StockSource.FMP, clock.instant());
        return outlook.isEmpty() ? Optional.empty() : Optional.of(outlook);
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
    private java.time.LocalDate nextEarnings(List<Earnings> schedule) {
        if (schedule == null) {
            return null;
        }
        java.time.LocalDate today = java.time.LocalDate.now(clock.withZone(NEW_YORK));
        return schedule.stream()
                .map(Earnings::announcedOn)
                .filter(java.util.Objects::nonNull)
                .filter(date -> !date.isBefore(today))
                .min(java.util.Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 한 엔드포인트에서 <b>첫 줄만</b> — 컨센서스 둘은 심볼당 한 줄이 온다.
     *
     * <p><b>실패를 여기서 삼킨다</b> — 부르는 쪽이 「셋 다 실패했나」를 판단해야 하기 때문이다.
     * 하나가 402여도 다른 하나가 살아 있으면 그것만으로 답이 된다.
     */
    private <T> Fetched<T> fetch(String path, String symbol,
                                 ParameterizedTypeReference<List<T>> type) {
        Fetched<List<T>> found = fetchAll(path, symbol, type);
        List<T> rows = found.value();
        // 빈 배열은 성공이다 — 「그 심볼에 의견을 낸 곳이 없다」는 값이다
        return new Fetched<>(rows == null || rows.isEmpty() ? null : rows.get(0), found.failed());
    }

    /**
     * 한 엔드포인트의 <b>줄 전체</b> — 실적발표는 여러 분기가 온다.
     *
     * <p>{@link #fetch}가 이것을 감싼다. 호출 하나를 두 모양으로 읽는 것이라 <b>URL 조립과
     * 키 가리기가 한 곳에만</b> 있다 — 두 벌로 두면 키를 가리는 쪽만 고쳐지는 날이 온다.
     */
    private <T> Fetched<List<T>> fetchAll(String path, String symbol,
                                          ParameterizedTypeReference<List<T>> type) {
        if (!quota.tryAcquire()) {
            // 어차피 거절당한다. 부르지 않는 편이 빠르고 로그도 깨끗하다
            throw new IllegalStateException("FMP 일일 호출 한도를 소진했습니다");
        }
        String uri = baseUrl + path + "?symbol=" + encode(symbol) + "&apikey=" + apiKey;
        try {
            return new Fetched<>(restClient.get().uri(URI.create(uri)).retrieve().body(type), false);
        } catch (RuntimeException e) {
            // ⚠️ 예외 메시지에 apikey가 박힌 URL이 들어 있다 — 그대로 흘리면 키가 유출된다.
            //    FailureReason은 상태 코드와 예외 이름만 주므로 키가 새지 않는다
            log.info("[fmp] '{}' {} 조회 실패: {}", symbol, path, FailureReason.of(e));
            return new Fetched<>(null, true);
        }
    }

    /**
     * 한 엔드포인트의 결과 — <b>「없다」와 「못 물어봤다」를 가른다.</b>
     *
     * @param value  받은 것. 빈 배열이면 {@code null}이고 그건 <b>값</b>이다
     * @param failed 조회가 실패했나. 셋 다 참이면 부르는 쪽이 던진다
     */
    private record Fetched<T>(T value, boolean failed) {
    }

    /** {@code 0}은 목표가가 아니다 — 평균에 넣으면 실제보다 낮은 값이 화면에 나간다. */
    private static BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * ⚠️ <b>{@code consensus}는 글자다</b>({@code "Buy"}). 국내와 같은 정규화를 타야 한다 —
     * 버킷 수({@code buy} 등)로 최빈을 다시 계산하면 FMP가 이미 낸 판단을 우리가 덮어쓴다.
     *
     * <p>{@code analystCount}는 다섯 버킷의 합이다. 실측 {@code AAPL}에서
     * 1+69+32+9+0 = 111이었다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Grades(String consensus, Integer strongBuy, Integer buy, Integer hold,
                  Integer sell, Integer strongSell) {

        Integer analystCount() {
            int total = zero(strongBuy) + zero(buy) + zero(hold) + zero(sell) + zero(strongSell);
            // 0곳이면 「0곳이 매수」가 되어 뜻이 없다 — 그때는 곳 수를 안 적는다
            return total == 0 ? null : total;
        }

        private static int zero(Integer value) {
            return value == null ? 0 : value;
        }
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
    record Earnings(@JsonProperty("date") java.time.LocalDate announcedOn) {
    }
}
