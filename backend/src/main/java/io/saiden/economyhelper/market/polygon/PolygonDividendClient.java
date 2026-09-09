package io.saiden.economyhelper.market.polygon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.StockOutlook.Dividend;
import io.saiden.economyhelper.market.UsDividendClient;
import io.saiden.economyhelper.support.FailureReason;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 미국 종목의 <b>배당</b> — Polygon.
 *
 * <p><b>왜 FMP가 아닌가.</b> FMP 무료 티어는 <b>심볼별 허용목록</b>이라 배당도 그 목록 밖 심볼에는
 * 안 준다 — 실측(2026-09-08) 열다섯 개 중 다섯이 402였고({@code ORCL}·{@code SNOW}·{@code SCHD}·
 * {@code QQQ}·{@code SOXL}) 「{@code /s 슈드}에 배당이 안 나온다」로 신고됐다. 그 문을 다 두드려 봤다:
 * 심볼별 셋 전부 402, 달력은 <b>날짜 범위 자체가 유료 파라미터</b>이고 범위 없이 부르면 200이지만
 * <b>허용목록 심볼만</b> 담는다(실측 46행에 {@code GOOGL}은 있고 {@code SCHD}는 없다).
 *
 * <p>Polygon은 <b>같은 심볼들을 다 준다</b>(실측 2026-09-09: {@code SCHD}·{@code JEPI}·{@code QQQ}·
 * {@code AAPL} 전부 200). 그리고 {@code AAPL}에서 <b>FMP와 값이 일치했다</b> —
 * 기준일 {@code 2026-08-10} · 지급 {@code 2026-08-13} · 0.27. 교차 검증된 셈이다.
 *
 * <p><b>덤으로 FMP 한도가 풀렸다.</b> 배당을 여기로 옮기면서 FMP 호출이 심볼당 <b>셋에서 둘로</b>
 * 줄었다 — 하루 240회에서 심볼 하나가 2회를 쓴다.
 *
 * <p>⚠️ <b>정렬을 응답에 맡기지 않는다.</b> {@code sort}·{@code order}를 명시해 최신부터 받는다 —
 * 「첫 행을 그냥 집지 않는다」와 같은 자리이고, 창(limit)이 무엇을 담는지가 정렬에 달려 있다.
 * 월배당({@code JEPI}, frequency 12)이 있으므로 {@value #ROWS}행이면 한 해가 덮인다.
 *
 * <p>⚠️ <b>무료 티어가 분당 5회다.</b> 그래서 리미터를 걸고 12시간 캐시를 둔다. 하루 총량 제한은
 * 없어서 {@code FmpQuotaGuard} 같은 날짜 카운터는 필요 없다.
 *
 * <p><b>보충이지 폴백이 아니다.</b> 실패하면 그 줄만 빠지고 시세와 나머지 전망은 그대로 나간다 —
 * 삼키는 일은 {@code StockService}가 한다({@code FmpUsOutlookClient}와 같은 계약).
 */
@Component
public class PolygonDividendClient implements UsDividendClient {

    private static final Logger log = LoggerFactory.getLogger(PolygonDividendClient.class);

    private static final String PATH = "/v3/reference/dividends";

    /** 몇 행을 받을지 — 월배당이 있어 한 해를 덮으려면 열둘이 필요하다. */
    private static final int ROWS = 12;

    /**
     * 배당 날짜를 자르는 달력 — <b>그 시장의 것</b>이다({@code FmpUsOutlookClient}와 같은 판단).
     * KST로 자르면 기준일 당일 아침에 그 줄이 사라진다.
     */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final Clock clock;

    public PolygonDividendClient(RestClient.Builder builder,
                                 @Value("${economy-helper.market.polygon.base-url}") String baseUrl,
                                 @Value("${economy-helper.market.polygon.api-key:}") String apiKey,
                                 Clock clock) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.clock = clock;
    }

    /**
     * @param symbol {@code SCHD}. 지수는 부르지 않는다 — 지수에는 배당이 없다
     * @return 고른 한 건. 배당이 없으면 {@link Dividend#none()} — <b>{@code null}이 아니다</b>
     * @throws IllegalStateException 키가 없거나 조회가 실패했을 때. 삼키는 것은 {@code StockService}다
     */
    // ⚠️ **빈 답을 null로 돌려주지 않는다.** 스프링 캐시가 null을 거절해(disableCachingNullValues)
    //    IllegalArgumentException이 튀고, 배당 안 주는 종목을 검색할 때마다 상대를 다시 부른다 —
    //    전망 캐시가 실물 감사에서 물렸던 그 자리다. Dividend.none()이 담긴다
    @Cacheable(cacheNames = CacheNames.US_DIVIDEND, key = "#symbol", unless = "#result == null")
    @RateLimiter(name = "polygon")
    @CircuitBreaker(name = "polygon")
    @Override
    public Dividend dividend(String symbol) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Polygon API 키가 없습니다");
        }
        String uri = baseUrl + PATH + "?ticker=" + encode(symbol)
                + "&limit=" + ROWS + "&sort=ex_dividend_date&order=desc&apiKey=" + apiKey;
        Dividends response;
        try {
            response = restClient.get().uri(URI.create(uri)).retrieve().body(Dividends.class);
        } catch (RuntimeException e) {
            // ⚠️ 예외 메시지에 apiKey가 박힌 URL이 들어 있다 — FailureReason만 흘린다
            log.info("[polygon] '{}' 배당 조회 실패: {}", symbol, FailureReason.of(e));
            throw new IllegalStateException("Polygon 배당 조회 실패 (" + symbol + "): "
                    + FailureReason.of(e));
        }
        if (response == null) {
            throw new IllegalStateException("Polygon 배당 응답이 비어 있습니다 (" + symbol + ")");
        }
        Dividend chosen = Dividend.nextOf(rowsOf(response), LocalDate.now(clock.withZone(NEW_YORK)));
        // 빈 값도 값이라 담긴다 — 「그 종목은 배당을 안 준다」는 12시간 안에 안 바뀐다
        return chosen == null ? Dividend.none() : chosen;
    }

    private static List<Dividend> rowsOf(Dividends response) {
        if (response.results() == null) {
            return List.of();
        }
        return response.results().stream()
                .filter(Objects::nonNull)
                .map(row -> Dividend.row(row.recordDate(), row.payDate(), row.amount()))
                .toList();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 응답 봉투. {@code next_url}이 함께 오지만 최신 열둘로 충분하므로 안 따라간다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dividends(List<Row> results) {
    }

    /**
     * 배당 한 건 — 화면이 쓰는 셋만 담는다.
     *
     * <p>⚠️ <b>{@code ex_dividend_date}는 읽지 않는다.</b> 화면은 <b>기준일</b>을 적기로 했고,
     * 락일을 기준일 대신 쓸 수도 없다 — 실측으로 두 날짜가 갈린다(FMP 최근 12행에서 {@code AAPL}은
     * 8개, {@code NVDA}는 10개만 같았다). {@code declaration_date}·{@code frequency}·
     * {@code dividend_type}도 화면이 안 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(@JsonProperty("record_date") LocalDate recordDate,
               @JsonProperty("pay_date") LocalDate payDate,
               @JsonProperty("cash_amount") BigDecimal amount) {
    }
}
