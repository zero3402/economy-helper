package io.saiden.economyhelper.market.fmp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.saiden.economyhelper.market.StockOutlook;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * FMP 전망의 실측 응답을 그대로 먹인다(2026-08-21, 무료 티어, {@code AAPL} 둘 다 200).
 *
 * <p>이 파일의 중심은 <b>「둘 중 하나만 와도 답이다」</b>다. 무료 티어가 심볼별 허용목록이라
 * 목표가와 실적발표일 중 한쪽만 402일 수 있고, 그때 살아 있는 쪽을 버리면 이유 없이 화면이 빈다.
 *
 * <p>⚠️ <b>{@code grades-consensus}는 더 이상 부르지 않는다.</b> 투자의견을 화면에서 걷어내면서
 * 그 호출과 등급 정규화 테스트를 함께 지웠다 — 심볼당 호출이 셋에서 둘로 줄었다.
 */
class FmpUsOutlookClientTest {

    private static final String TARGET = "/stable/price-target-consensus";
    private static final String EARNINGS = "/stable/earnings";
    private static final String API_KEY = "test-key-402";

    /**
     * <b>서버는 클래스당 하나다 — 테스트마다 띄우고 내리지 않는다.</b>
     *
     * <p>예전에는 {@code @BeforeEach}에서 {@code dynamicPort()}로 새로 띄우고
     * {@code @AfterEach}에서 내렸다. 그 모양에서 <b>전체 실행 때 드물게 떨어졌다</b>
     * (실측 2026-08-27 10:58 KST, {@code ignoresAZeroTarget}): 제 스텁이 200이라고 한
     * {@code /stable/price-target-consensus}에서 <b>정상적인 HTTP 500</b>을 받았는데,
     * 그 경로를 500으로 스텁하는 곳은 <b>이 클래스의 다른 메서드 하나뿐</b>이다
     * ({@code stub(TARGET, 500, "{}")}). 미매칭이면 404가 왔어야 하므로, 요청이
     * <b>앞 테스트의 서버 인스턴스</b>에 닿은 것이다 — {@code stop()}이 포트를 놓기 전에
     * 다음 서버가 같은 포트를 받는 창이 그 모양을 만든다.
     *
     * <p>⚠️ <b>연결 계열 실패가 아니었다.</b> {@code ARCHITECTURE.md} §7이 「실패는 언제나
     * 연결 계열이다」라고 적어 두고 있었는데 그 문장이 이 실측으로 깨졌다.
     *
     * <p>그래서 <b>포트를 한 번만 잡고 스텁만 비운다.</b> 이 저장소에 이미 선례가 둘 있다 —
     * {@code RetryLiveTest}와 {@code HackerNewsApiTest}가 같은 모양이다.
     * {@code resetAll()}은 스텁과 요청 기록을 함께 비우므로 {@code verify}도 그대로 성립한다.
     */
    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetStubs() {
        server.resetAll();
    }

    private FmpUsOutlookClient client() {
        return new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(), API_KEY,
                new AlwaysAllow(), Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));
    }

    private void stub(String path, int status, String body) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /**
     * 실측 그대로(2026-08-21, 무료 티어, {@code AAPL} 셋 다 200).
     *
     * <p>실적발표는 <b>최신순으로 여러 분기</b>가 온다 — 첫 행이 앞날이고 나머지가 지난 것이다.
     */
    private void stubAll() {
        stub(TARGET, 200, """
                [{"symbol":"AAPL","targetHigh":400,"targetLow":245,
                  "targetConsensus":340.72,"targetMedian":360}]""");
        stub(EARNINGS, 200, """
                [{"symbol":"AAPL","date":"2026-10-29","epsActual":null,"epsEstimated":1.98,
                  "revenueEstimated":113340900000,"lastUpdated":"2026-08-21"},
                 {"symbol":"AAPL","date":"2026-07-30","epsActual":2.02,"epsEstimated":1.89,
                  "revenueActual":109417000000,"lastUpdated":"2026-08-21"},
                 {"symbol":"AAPL","date":"2026-04-30","epsActual":2.01,"epsEstimated":1.95,
                  "revenueActual":111184000000,"lastUpdated":"2026-07-29"}]""");
    }

    @Test
    @DisplayName("고가가 아니라 컨센서스를 쓴다 — 고가는 가장 낙관적인 한 곳이다")
    void usesConsensusNotTheHigh() {
        stubAll();

        assertThat(client().outlook("AAPL").orElseThrow().targetPrice())
                .as("400(고가)을 쓰면 목표주가가 부풀려진다")
                .isEqualByComparingTo(new BigDecimal("340.72"));
    }

    @Test
    @DisplayName("실적발표일이 없어도 목표가는 살린다 — 무료 티어는 심볼별 허용목록이다")
    void keepsTheTargetWhenEarningsAreBlocked() {
        stub(TARGET, 200, """
                [{"symbol":"ORCL","targetConsensus":250.5}]""");

        StockOutlook outlook = client().outlook("ORCL").orElseThrow();

        assertThat(outlook.targetPrice()).isEqualByComparingTo(new BigDecimal("250.5"));
        assertThat(outlook.earningsDate()).as("못 구한 날짜는 null이다 — 지어내지 않는다").isNull();
    }

    @Test
    @DisplayName("둘 다 실패하면 던진다 — 「값이 없다」와 구분해야 한다")
    void throwsWhenAllFail() {
        stub(TARGET, 402, "{}");
        stub(EARNINGS, 402, "{}");

        // 삼키면 그 위의 @CircuitBreaker가 정상 반환을 보고 성공을 센다.
        // 삼키는 일은 StockService가 한다
        assertThatThrownBy(() -> client().outlook("ORCL"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 배열은 값이다 — 목표가를 낸 곳이 없는 것이라 던지지 않는다")
    void emptyArrayIsAValue() {
        stub(TARGET, 200, "[]");

        assertThat(client().outlook("AAPL"))
                .as("실패가 아니라 「없다」이므로 빈 값이다")
                .isEmpty();
    }

    @Test
    @DisplayName("목표가 0은 값이 아니다 — 「목표가 0달러」가 화면에 나가면 거짓이다")
    void ignoresAZeroTarget() {
        stub(TARGET, 200, """
                [{"symbol":"AAPL","targetConsensus":0}]""");

        assertThat(client().outlook("AAPL")).isEmpty();
    }

    @Test
    @DisplayName("한도를 소진했으면 부르지 않는다 — 어차피 FMP가 거절한다")
    void skipsTheCallWhenQuotaIsGone() {
        FmpUsOutlookClient limited = new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(),
                API_KEY, new AlwaysDeny(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> limited.outlook("AAPL")).hasMessageContaining("한도");

        server.verify(0, getRequestedFor(urlPathEqualTo(TARGET)));
    }

    @Test
    @DisplayName("키가 없으면 부르지 않는다 — 빈 키로 호출하면 한도만 축낸다")
    void skipsTheCallWithoutAKey() {
        FmpUsOutlookClient keyless = new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(),
                "", new AlwaysAllow(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> keyless.outlook("AAPL")).hasMessageContaining("키");

        server.verify(0, getRequestedFor(urlPathEqualTo(TARGET)));
    }

    @Test
    @DisplayName("실패 메시지에 API 키가 새지 않는다 — 예외 메시지에 URL이 실려 온다")
    void neverLeaksTheApiKey() {
        stub(TARGET, 500, "{}");

        assertThatThrownBy(() -> client().outlook("AAPL"))
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    @DisplayName("다음 실적발표일을 읽는다 — 화면의 「실적발표」 줄이 이 값이다")
    void readsTheNextEarningsDate() {
        stubAll();

        assertThat(client().outlook("AAPL").orElseThrow().earningsDate())
                .as("실측 첫 행이 2026-10-29이고 나머지 둘은 지난 분기다")
                .isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    @DisplayName("지난 분기를 집지 않는다 — 응답 순서가 어긋나도 앞날을 고른다")
    void ignoresPastQuarters() {
        // ⚠️ 첫 행을 그냥 집는 구현이면 여기서 2026-04-30이 「다음 발표」로 나간다
        stub(TARGET, 200, "[]");
        stub(EARNINGS, 200, """
                [{"symbol":"AAPL","date":"2026-04-30","epsActual":2.01},
                 {"symbol":"AAPL","date":"2026-10-29","epsActual":null},
                 {"symbol":"AAPL","date":"2026-07-30","epsActual":2.02}]""");

        assertThat(client().outlook("AAPL").orElseThrow().earningsDate())
                .isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    @DisplayName("앞날이 하나도 없으면 안 적는다 — 지난 날짜를 「예정」이라 부르지 않는다")
    void omitsEarningsWhenEveryDateIsPast() {
        stub(TARGET, 200, "[]");
        stub(EARNINGS, 200, """
                [{"symbol":"AAPL","date":"2026-07-30","epsActual":2.02},
                 {"symbol":"AAPL","date":"2026-04-30","epsActual":2.01}]""");

        assertThat(client().outlook("AAPL"))
                .as("셋이 다 비면 붙일 것이 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("목표가가 402여도 실적발표일은 살린다 — 둘이 따로 논다")
    void keepsEarningsWhenTheTargetIsBlocked() {
        stub(TARGET, 402, "{\"Error Message\":\"Exclusive Endpoint\"}");
        stub(EARNINGS, 200, """
                [{"symbol":"ORCL","date":"2026-09-10","epsActual":null}]""");

        StockOutlook outlook = client().outlook("ORCL").orElseThrow();

        assertThat(outlook.earningsDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(outlook.targetPrice()).isNull();
    }

    @Test
    @DisplayName("오늘을 미국 달력으로 자른다 — KST로 자르면 하루가 어긋난다")
    void cutsTodayByTheMarketCalendar() {
        // 2026-10-29T02:00Z는 뉴욕에서 10-28 22시이고 서울에서 10-29 11시다.
        // 10-28 발표 건은 미국 달력으로 「오늘」이라 아직 예정이고, KST로 자르면 지난 것이 된다
        FmpUsOutlookClient client = new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(),
                API_KEY, new AlwaysAllow(),
                Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC));
        stub(TARGET, 200, "[]");
        stub(EARNINGS, 200, """
                [{"symbol":"AAPL","date":"2026-10-28","epsActual":null}]""");

        assertThat(client.outlook("AAPL").orElseThrow().earningsDate())
                .as("현지로 아직 오늘인 발표를 지난 것으로 버리면 안 된다")
                .isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    @DisplayName("한도가 두 호출 사이에서 끝나도 목표가는 살린다 — 이미 받아 둔 것을 버리지 않는다")
    void keepsTheTargetWhenQuotaRunsOutMidway() {
        // ⚠️ 한도 퍼밋은 목표가 → 실적발표일 순서로 **먼저** 잡는다. 두 HTTP를 겹치면서 각자 퍼밋을
        //    잡게 뒀더니 어느 쪽이 마지막 하나를 가져가는지가 경쟁이 됐고, 실적발표일이 가져간 날은
        //    목표가 쪽이 「한도 소진」으로 던져 답이 통째로 없어졌다 — 이 테스트가 그것을 잡았다.
        //    예외는 캐시되지 않으므로 그때는 다음 조회도 같은 자리에서 막힌다
        stub(TARGET, 200, """
                [{"symbol":"AAPL","targetHigh":400,"targetConsensus":340.72}]""");
        FmpUsOutlookClient client = new FmpUsOutlookClient(RestClient.builder(), server.baseUrl(),
                API_KEY, new DeniesAfterFirst(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC));

        StockOutlook outlook = client.outlook("AAPL").orElseThrow();

        assertThat(outlook.targetPrice())
                .as("한도가 둘째 호출에서 끝났다고 첫째가 받아 온 값을 버리면 안 된다")
                .isEqualByComparingTo(new BigDecimal("340.72"));
        assertThat(outlook.earningsDate())
                .as("못 물어본 값은 없는 것이다 — 지어내지 않는다")
                .isNull();
        server.verify(0, getRequestedFor(urlPathEqualTo(EARNINGS)));
    }

    /** 첫 호출만 허용한다 — 한도가 두 호출 사이에서 끝나는 경계를 만든다. */
    private static final class DeniesAfterFirst extends FmpQuotaGuard {
        private boolean used;

        private DeniesAfterFirst() {
            super(null, Clock.systemUTC(), 240);
        }

        @Override
        public boolean tryAcquire() {
            if (used) {
                return false;
            }
            used = true;
            return true;
        }
    }

    /** 한도를 세지 않는 가드 — 세는 규칙은 {@code FmpQuotaGuard}가 스스로 시험한다. */
    private static final class AlwaysAllow extends FmpQuotaGuard {
        private AlwaysAllow() {
            super(null, Clock.systemUTC(), 240);
        }

        @Override
        public boolean tryAcquire() {
            return true;
        }
    }

    private static final class AlwaysDeny extends FmpQuotaGuard {
        private AlwaysDeny() {
            super(null, Clock.systemUTC(), 240);
        }

        @Override
        public boolean tryAcquire() {
            return false;
        }
    }
}
