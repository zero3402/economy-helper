package io.saiden.economyhelper.market.polygon;

import io.saiden.economyhelper.support.WireMockTest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.market.StockOutlook;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Polygon 배당의 실측 응답을 그대로 먹인다(2026-09-09).
 *
 * <p><b>왜 FMP에서 갈렸나.</b> FMP 무료 티어가 심볼별 허용목록이라 배당도 목록 밖 심볼에는 안 준다 —
 * 실측 열다섯 중 다섯이 402였고({@code ORCL}·{@code SNOW}·{@code SCHD}·{@code QQQ}·{@code SOXL})
 * 그것이 「{@code /s 슈드}에 배당이 안 나온다」로 신고됐다. Polygon은 그 심볼들을 다 준다.
 *
 * <p><b>값이 교차 검증됐다.</b> {@code AAPL}에서 FMP와 같은 값이 왔다 — 기준일 {@code 2026-08-10} ·
 * 지급 {@code 2026-08-13} · 0.27. 두 출처가 같은 것을 말한다는 증거다.
 */
class PolygonDividendClientTest extends WireMockTest {

    private static final String PATH = "/v3/reference/dividends";
    private static final String API_KEY = "test-key-402";

    /** 실측 그대로 — {@code SCHD} 2026-09-09, 최신순 넷. 전부 지난 배당이다(분기배당). */
    private static final String SCHD = """
            {"results":[
              {"cash_amount":0.2525,"currency":"USD","declaration_date":"2026-06-17",
               "dividend_type":"CD","ex_dividend_date":"2026-06-24","frequency":4,
               "pay_date":"2026-06-29","record_date":"2026-06-24","ticker":"SCHD"},
              {"cash_amount":0.2569,"currency":"USD","declaration_date":"2026-03-18",
               "dividend_type":"CD","ex_dividend_date":"2026-03-25","frequency":4,
               "pay_date":"2026-03-30","record_date":"2026-03-25","ticker":"SCHD"}],
             "status":"OK","next_url":"https://api.polygon.io/v3/reference/dividends?cursor=x"}""";

    private PolygonDividendClient client(Instant now) {
        return new PolygonDividendClient(RestClient.builder(), server.baseUrl(), API_KEY,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void stub(int status, String body) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("FMP가 막은 심볼의 배당을 준다 — 신고받은 그 자리다")
    void readsADividendForASymbolFmpBlocks() {
        stub(200, SCHD);

        StockOutlook.Dividend dividend = client(Instant.parse("2026-09-09T12:00:00Z")).dividend("SCHD");

        assertThat(dividend)
                .as("둘 다 지났으므로 가장 최근에 끝난 건이고 이름표가 「지난」을 든다")
                .isEqualTo(new StockOutlook.Dividend(LocalDate.of(2026, 6, 24),
                        LocalDate.of(2026, 6, 29), new BigDecimal("0.2525"), true));
    }

    @Test
    @DisplayName("앞으로 올 배당이 있으면 그것을 든다 — 지난 것은 마지막 수단이다")
    void prefersTheUpcomingDividend() {
        stub(200, SCHD.replace("\"pay_date\":\"2026-06-29\",\"record_date\":\"2026-06-24\"",
                "\"pay_date\":\"2026-09-29\",\"record_date\":\"2026-09-24\""));

        StockOutlook.Dividend dividend = client(Instant.parse("2026-09-09T12:00:00Z")).dividend("SCHD");

        assertThat(dividend.past()).isFalse();
        assertThat(dividend.recordDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    @DisplayName("배당락일이 아니라 기준일을 읽는다 — 두 날짜는 실측으로 갈린다")
    void readsTheRecordDateNotTheExDate() {
        // ⚠️ 락일을 기준일 대신 쓸 수 없다. FMP 실측 최근 12행에서 AAPL은 8개, NVDA는 10개만
        //    두 날짜가 같았다 — 여섯 번에 한 번쯤 틀린 날이 화면에 나간다.
        //    그래서 여기서는 일부러 둘을 어긋나게 둔다
        stub(200, """
                {"results":[{"cash_amount":0.27,"currency":"USD","dividend_type":"CD",
                  "ex_dividend_date":"2026-09-24","frequency":4,
                  "pay_date":"2026-10-01","record_date":"2026-09-25","ticker":"X"}],"status":"OK"}""");

        assertThat(client(Instant.parse("2026-09-09T12:00:00Z")).dividend("X").recordDate())
                .as("락일 09-24가 아니라 기준일 09-25여야 한다")
                .isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    @DisplayName("정렬을 응답에 맡기지 않는다 — sort·order를 명시해 최신부터 받는다")
    void asksForTheNewestFirst() {
        stub(200, SCHD);

        client(Instant.parse("2026-09-09T12:00:00Z")).dividend("SCHD");

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("ticker", equalTo("SCHD"))
                .withQueryParam("sort", equalTo("ex_dividend_date"))
                .withQueryParam("order", equalTo("desc"))
                .withQueryParam("limit", equalTo("12")));
    }

    @Test
    @DisplayName("배당을 안 주는 종목은 빈손이다 — 실패가 아니라 값이다")
    void emptyResultsIsAValue() {
        stub(200, """
                {"results":[],"status":"OK"}""");

        // ⚠️ null이 아니라 빈 값 객체다 — null이면 스프링 캐시가 거절해 예외가 튄다
        assertThat(client(Instant.parse("2026-09-09T12:00:00Z")).dividend("BRK.B"))
                .isEqualTo(StockOutlook.Dividend.none());
    }

    @Test
    @DisplayName("조회가 실패하면 던진다 — 삼키면 브레이커가 정상 반환을 보고 성공을 센다")
    void throwsOnFailure() {
        stub(500, "{}");

        assertThatThrownBy(() -> client(Instant.parse("2026-09-09T12:00:00Z")).dividend("SCHD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    @DisplayName("키가 없으면 부르지 않는다")
    void skipsWithoutAKey() {
        PolygonDividendClient keyless = new PolygonDividendClient(RestClient.builder(),
                server.baseUrl(), "", Clock.systemUTC());

        assertThatThrownBy(() -> keyless.dividend("SCHD")).hasMessageContaining("키");

        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("오늘을 미국 달력으로 자른다 — 기준일 당일 KST 아침에 그 줄이 사라지면 안 된다")
    void cutsTodayByTheMarketCalendar() {
        // 2026-09-25T02:00Z는 뉴욕에서 09-24 22시이고 서울에서 09-25 11시다
        stub(200, """
                {"results":[{"cash_amount":0.27,"currency":"USD","dividend_type":"CD",
                  "ex_dividend_date":"2026-09-24","frequency":4,
                  "pay_date":"2026-10-01","record_date":"2026-09-24","ticker":"X"}],"status":"OK"}""");

        StockOutlook.Dividend dividend = client(Instant.parse("2026-09-25T02:00:00Z")).dividend("X");

        assertThat(dividend.past()).as("현지로 아직 오늘이면 앞날이다").isFalse();
        assertThat(dividend.recordDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }
}
