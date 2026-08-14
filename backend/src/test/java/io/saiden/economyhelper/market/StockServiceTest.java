package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.data.MarketIndexApi;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import io.saiden.economyhelper.market.fmp.FmpApi;
import io.saiden.economyhelper.market.fmp.FmpApi.FmpQuote;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 이 클래스의 핵심 주장을 고정한다: <b>동명 후보를 시가총액으로 가른다.</b>
 *
 * <p>아래 값들은 2026-08-11 실측이다. {@code 삼성}은 26건이 걸리는데 시총 1위가 삼성전자이고,
 * 우선주·자회사가 정확히 밀린다. {@code CryptoService}가 24시간 거래대금으로 하는 일과 같다.
 */
class StockServiceTest {

    private static final String TODAY = "20260811";

    /** 실측 시가총액 (원). 삼성전자 1,400조 vs 삼성전자우 144조. */
    private static final List<StockPrice> SAMSUNG = List.of(
            price("005930", "삼성전자", "KOSPI", "239500", "1400183726616000"),
            price("005935", "삼성전자우", "KOSPI", "180200", "144587290780600"),
            price("028260", "삼성물산", "KOSPI", "180000", "33000000000000"));

    private static final List<StockPrice> KAKAO = List.of(
            price("035720", "카카오", "KOSPI", "40700", "18000000000000"),
            price("323410", "카카오뱅크", "KOSPI", "22000", "10000000000000"));

    private static StockPrice price(String code, String name, String market, String close, String cap) {
        return new StockPrice(TODAY, code, name, market, close, cap);
    }

    @Test
    @DisplayName("자회사가 모회사를 이기지 않는다")
    void parentBeatsSubsidiary() {
        StockService service = new StockService(new RecordingApi(Map.of("카카오", KAKAO)), indexApi(null), noFmp(), noResolver());

        assertThat(service.quote("카카오").orElseThrow().name()).isEqualTo("카카오");
    }

    @Test
    @DisplayName("LLM이 준 종목코드로 먼저 조회한다 — 이름 검색을 태우지 않는다")
    void usesCodeFromLlmFirst() {
        RecordingApi api = new RecordingApi(Map.of("005930", SAMSUNG.subList(0, 1)));
        StockService service = new StockService(api, indexApi(null), noFmp(), resolver(new ResolvedStock("KR", "STOCK", "005930", "삼성전자")));

        assertThat(service.quote("삼전").orElseThrow().name()).isEqualTo("삼성전자");
        assertThat(api.byCode).contains("005930");
        assertThat(api.byName).as("코드가 걸리면 이름 검색은 하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("6자리 종목코드는 LLM을 건너뛴다 — 해석할 것이 없는데 Gemini를 태울 이유가 없다")
    void skipsLlmForPlainStockCode() {
        RecordingApi api = new RecordingApi(Map.of("005930", SAMSUNG.subList(0, 1)));
        StockService service = new StockService(api, indexApi(null), noFmp(), explodingResolver());

        // 군더더기가 붙은 형태도 같은 길로 간다 — QueryNormalizer가 '주가'를 떼어 준다
        for (String query : List.of("005930", "005930 주가", " 005930 ")) {
            assertThat(service.quote(query)).as("입력 '%s'", query)
                    .get().extracting(StockQuote::name).isEqualTo("삼성전자");
        }
        assertThat(api.byName).as("코드가 걸리면 이름 검색도 하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("없는 종목코드는 이름 검색으로 넘기지 않는다 — 6자리 숫자는 이름일 수 없다")
    void returnsEmptyForUnknownStockCode() {
        RecordingApi api = new RecordingApi(Map.of());

        assertThat(new StockService(api, indexApi(null), noFmp(), explodingResolver()).quote("999999"))
                .isEmpty();
        assertThat(api.byName).isEmpty();
    }

    @Test
    @DisplayName("통칭과 상장명이 달라도 찾는다 — 네이버는 상장명이 NAVER(로마자)다")
    void resolvesCommonNameToListedName() {
        List<StockPrice> naver = List.of(price("035420", "NAVER", "KOSPI", "200000", "32000000000000"));
        RecordingApi api = new RecordingApi(Map.of("035420", naver));
        StockService service = new StockService(api, indexApi(null), noFmp(), resolver(new ResolvedStock("KR", "STOCK", "035420", "NAVER")));

        assertThat(service.quote("네이버").orElseThrow().name()).isEqualTo("NAVER");
    }

    @Test
    @DisplayName("LLM이 없는 종목코드를 지어내면 이름으로 되돌아간다 — 환각을 그대로 믿지 않는다")
    void fallsBackToNameWhenLlmCodeIsBogus() {
        RecordingApi api = new RecordingApi(Map.of("삼성전자", SAMSUNG.subList(0, 1)));
        StockService service = new StockService(api, indexApi(null), noFmp(), resolver(new ResolvedStock("KR", "STOCK", "999999", "삼성전자")));

        assertThat(service.quote("삼전").orElseThrow().name()).isEqualTo("삼성전자");
        assertThat(api.byCode).as("지어낸 코드로 한 번은 조회해 본다").contains("999999");
        assertThat(api.byName).as("비었으므로 이름으로 되돌아간다").contains("삼성전자");
    }

    @Test
    @DisplayName("LLM이 죽어도 원문으로 찾는다 — Gemini 장애가 /stock 전면 중단이 되면 안 된다")
    void fallsBackToRawQueryWhenLlmFails() {
        RecordingApi api = new RecordingApi(Map.of("삼성전자", SAMSUNG.subList(0, 2)));
        StockService service = new StockService(api, indexApi(null), noFmp(), noResolver());

        assertThat(service.quote("삼성전자").orElseThrow().name()).isEqualTo("삼성전자");
        assertThat(api.byName).contains("삼성전자");
    }

    @Test
    @DisplayName("여러 날짜가 섞여 와도 가장 최근 기준일만 비교한다")
    void comparesOnlyLatestBasisDate() {
        // 어제 삼성전자우(시총 큼)와 오늘 삼성전자가 섞이면 날짜를 안 맞출 때 우선주가 이긴다
        List<StockPrice> mixed = List.of(
                new StockPrice("20260810", "005935", "삼성전자우", "KOSPI", "180200", "9999999999999999"),
                new StockPrice("20260811", "005930", "삼성전자", "KOSPI", "239500", "1400183726616000"));
        StockService service = new StockService(new RecordingApi(Map.of("삼성", mixed)), indexApi(null), noFmp(), noResolver());

        StockQuote match = service.quote("삼성").orElseThrow();

        assertThat(match.name()).isEqualTo("삼성전자");
        assertThat(match.at()).isEqualTo(java.time.LocalDate.of(2026, 8, 11)
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant());
    }

    @Test
    @DisplayName("걸리는 종목이 없으면 빈 결과 — 아무거나 돌려주면 오해한다")
    void returnsEmptyWhenNothingMatches() {
        StockService service = new StockService(new RecordingApi(Map.of()), indexApi(null), noFmp(), noResolver());

        assertThat(service.quote("없는종목zzz")).isEmpty();
        assertThat(service.quote("")).isEmpty();
        assertThat(service.quote(null)).isEmpty();
    }

    @Test
    @DisplayName("API가 죽어도 예외를 밖으로 내보내지 않는다 — 웹훅은 어떤 경우에도 200이어야 한다")
    void degradesWhenApiFails() {
        StockPriceApi exploding = new RecordingApi(Map.of()) {
            @Override
            public List<StockPrice> searchByName(String name) {
                throw new IllegalStateException("서킷브레이커 열림");
            }

            @Override
            public List<StockPrice> searchByCode(String code) {
                throw new IllegalStateException("서킷브레이커 열림");
            }
        };
        StockService service = new StockService(exploding, indexApi(null), noFmp(), noResolver());

        assertThat(service.quote("삼성전자")).isEmpty();
        assertThat(service.quotesOf(List.of("005930"))).isEmpty();
    }

    @Test
    @DisplayName("설정된 종목코드로도 조회한다 — 아침 브리핑이 이 경로를 쓴다")
    void quotesConfiguredCodes() {
        RecordingApi api = new RecordingApi(Map.of(
                "005930", SAMSUNG.subList(0, 1),
                "035720", KAKAO.subList(0, 1)));

        assertThat(new StockService(api, indexApi(null), noFmp(), noResolver()).quotesOf(List.of("005930", "035720")))
                .extracting(StockQuote::name)
                .containsExactly("삼성전자", "카카오");
    }

    @Test
    @DisplayName("설정에 없는 종목코드는 조용히 건너뛴다 — 오타 하나가 발송 전체를 막으면 안 된다")
    void skipsUnknownConfiguredCodes() {
        RecordingApi api = new RecordingApi(Map.of("005930", SAMSUNG.subList(0, 1)));

        assertThat(new StockService(api, indexApi(null), noFmp(), noResolver()).quotesOf(List.of("005930", "999999")))
                .extracting(StockQuote::name)
                .containsExactly("삼성전자");
    }

    @Test
    @DisplayName("미국 종목은 FMP로 간다 — 공공데이터포털을 태우지 않는다")
    void routesUsStockToFmp() {
        RecordingApi api = new RecordingApi(Map.of());
        StockService service = new StockService(api, indexApi(null),
                fmp(new FmpQuote("AAPL", "Apple Inc.", new java.math.BigDecimal("302.25"),
                        "NASDAQ", new java.math.BigDecimal("4439253351000"), 1786564801L)),
                resolver(new ResolvedStock("US", "STOCK", "AAPL", "Apple Inc.")));

        StockQuote match = service.quote("애플").orElseThrow();

        assertThat(match.code()).isEqualTo("AAPL");
        assertThat(match.currency()).isEqualTo(StockQuote.Money.USD);
        assertThat(match.realtime()).as("미국은 현재가다").isTrue();
        assertThat(api.byName).as("미국인데 국내 검색을 태우면 안 된다").isEmpty();
        assertThat(api.byCode).isEmpty();
    }

    @Test
    @DisplayName("^로 시작하면 지수로 본다 — FMP는 종목과 지수를 구분해 주지 않는다")
    void treatsCaretSymbolAsIndex() {
        StockService service = new StockService(new RecordingApi(Map.of()), indexApi(null),
                fmp(new FmpQuote("^IXIC", "NASDAQ Composite", new java.math.BigDecimal("26588.49"),
                        "", null, 1786564801L)),
                resolver(new ResolvedStock("US", "INDEX", "^IXIC", "나스닥")));

        StockQuote quote = service.quote("나스닥").orElseThrow();

        assertThat(quote.index()).isTrue();
        assertThat(quote.currency())
                .as("지수는 통화가 없다 — 원화 환산 대상이 아니다")
                .isEqualTo(StockQuote.Money.NONE);
    }

    @Test
    @DisplayName("LLM이 지어낸 미국 티커는 FMP가 빈 결과를 줘서 걸러진다")
    void dropsHallucinatedUsSymbol() {
        StockService service = new StockService(new RecordingApi(Map.of()), indexApi(null),
                fmp(null), resolver(new ResolvedStock("US", "STOCK", "ZZZZ", "없는회사")));

        assertThat(service.quote("없는회사")).isEmpty();
    }

    @Test
    @DisplayName("미국인데 티커가 없으면 포기한다 — 이름으로 되짚을 경로가 없다")
    void givesUpWhenUsTickerMissing() {
        StockService service = new StockService(new RecordingApi(Map.of()), indexApi(null),
                noFmp(), resolver(new ResolvedStock("US", "STOCK", null, "무언가")));

        assertThat(service.quote("무언가")).isEmpty();
    }

    @Test
    @DisplayName("브리핑용 미국 심볼은 하나가 죽어도 나머지가 나온다")
    void usQuotesOfSurvivesPartialFailure() {
        FmpApi api = new FmpApi(RestClient.builder(), "https://example.invalid", "k", null) {
            @Override
            public FmpQuote quote(String symbol) {
                if ("BAD".equals(symbol)) {
                    throw new IllegalStateException("서킷브레이커 열림");
                }
                return new FmpQuote(symbol, symbol, new java.math.BigDecimal("1"), "NASDAQ", null, 1L);
            }
        };
        StockService service = new StockService(new RecordingApi(Map.of()), indexApi(null),
                api, noResolver());

        assertThat(service.usQuotesOf(List.of("AAPL", "BAD", "NVDA")))
                .extracting(StockQuote::code)
                .containsExactly("AAPL", "NVDA");
    }

    /** 정해진 한 건을 주는 FMP 스텁. {@code null}이면 없는 심볼이다. */
    private static FmpApi fmp(FmpQuote answer) {
        return new FmpApi(RestClient.builder(), "https://example.invalid", "k", null) {
            @Override
            public FmpQuote quote(String symbol) {
                return answer;
            }
        };
    }

    /** FMP 스텁 — 국내 경로만 보는 테스트에서는 미국 조회가 일어나면 안 된다. */
    private static FmpApi noFmp() {
        return new FmpApi(RestClient.builder(), "https://example.invalid", "k", null) {
            @Override
            public FmpQuote quote(String symbol) {
                throw new AssertionError("국내 경로인데 FMP를 불렀습니다: " + symbol);
            }
        };
    }

    /** 지수 API 스텁. 정해진 지수 하나를 주거나 못 찾는다. */
    private static MarketIndexApi indexApi(MarketIndex answer) {
        return new MarketIndexApi(RestClient.builder(), "https://example.invalid", "k",
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC)) {
            @Override
            public MarketIndex searchByName(String name) {
                return answer;
            }
        };
    }

    /** 이름별로 답을 달리하는 지수 API 스텁. {@code null}이면 못 찾은 것, 예외면 조회 실패다. */
    private static MarketIndexApi indexApiOf(Map<String, MarketIndex> answers) {
        return new MarketIndexApi(RestClient.builder(), "https://example.invalid", "k",
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC)) {
            @Override
            public MarketIndex searchByName(String name) {
                if (!answers.containsKey(name)) {
                    throw new IllegalStateException("지수 조회 실패 (basDt=20260811)");
                }
                return answers.get(name);
            }
        };
    }

    @Test
    @DisplayName("브리핑용 지수는 설정 순서를 지킨다")
    void indicesOfKeepsConfiguredOrder() {
        MarketIndexApi api = indexApiOf(Map.of(
                "코스피", new MarketIndex("20260811", "코스피", "KOSPI시리즈", "6345.53"),
                "코스닥", new MarketIndex("20260811", "코스닥", "KOSDAQ시리즈", "857.84")));

        assertThat(new StockService(new RecordingApi(Map.of()), api, noFmp(), noResolver())
                .indicesOf(List.of("코스피", "코스닥")))
                .extracting(StockQuote::name)
                .containsExactly("코스피", "코스닥");
    }

    @Test
    @DisplayName("지수 하나가 실패해도 나머지는 나온다 — 코스닥 때문에 코스피까지 빠지면 안 된다")
    void indicesOfSurvivesPartialFailure() {
        Map<String, MarketIndex> answers = new java.util.HashMap<>();
        answers.put("코스피", new MarketIndex("20260811", "코스피", "KOSPI시리즈", "6345.53"));
        answers.put("코스닥", null);   // 못 찾은 경우
        MarketIndexApi api = indexApiOf(answers);

        assertThat(new StockService(new RecordingApi(Map.of()), api, noFmp(), noResolver())
                .indicesOf(List.of("코스피", "코스닥", "없는지수")))   // 없는지수는 예외를 던진다
                .extracting(StockQuote::name)
                .containsExactly("코스피");
    }

    @Test
    @DisplayName("지수는 종목이 아니라 지수 API로 간다 — 종목코드도 통화 단위도 없다")
    void routesIndexToIndexApi() {
        RecordingApi api = new RecordingApi(Map.of());
        StockService service = new StockService(api,
                indexApi(new MarketIndex("20260811", "코스피", "KOSPI시리즈", "6345.53")),
                noFmp(), resolver(new ResolvedStock("KR", "INDEX", null, "코스피")));

        StockQuote match = service.quote("코스피").orElseThrow();

        assertThat(match.name()).isEqualTo("코스피");
        assertThat(match.index()).isTrue();
        assertThat(match.price()).isEqualByComparingTo("6345.53");
        assertThat(api.byName).as("지수는 종목 검색을 태우지 않는다").isEmpty();
        assertThat(api.byCode).isEmpty();
    }

    @Test
    @DisplayName("지수를 못 찾으면 빈 결과 — 종목으로 되돌아가지 않는다")
    void returnsEmptyWhenIndexNotFound() {
        StockService service = new StockService(new RecordingApi(Map.of()), indexApi(null),
                noFmp(), resolver(new ResolvedStock("KR", "INDEX", null, "없는지수")));

        assertThat(service.quote("없는지수")).isEmpty();
    }

    /** LLM 대신 정해진 답을 준다. 프롬프트 품질은 실물 스모크에서 본다. */
    private static StockResolver resolver(ResolvedStock answer) {
        return new StockResolver(null, null) {
            @Override
            public Optional<ResolvedStock> resolve(String normalizedQuery) {
                return Optional.ofNullable(answer);
            }
        };
    }

    /** LLM이 죽었거나 종목을 특정하지 못한 상태. */
    private static StockResolver noResolver() {
        return resolver(null);
    }

    /** 불리면 안 되는 상태 — 해석할 것이 없는 입력에 Gemini가 나가면 여기서 드러난다. */
    private static StockResolver explodingResolver() {
        return new StockResolver(null, null) {
            @Override
            public Optional<ResolvedStock> resolve(String normalizedQuery) {
                throw new AssertionError("해석이 필요 없는 입력에 LLM을 불렀습니다: " + normalizedQuery);
            }
        };
    }

    /** HTTP는 {@code StockPriceApiTest}가 따로 본다. 여기서는 해석 규칙만 본다. */
    private static class RecordingApi extends StockPriceApi {
        private final Map<String, List<StockPrice>> byQuery;
        private final List<String> byName = new ArrayList<>();
        private final List<String> byCode = new ArrayList<>();

        RecordingApi(Map<String, List<StockPrice>> byQuery) {
            super(RestClient.builder(), "https://example.invalid", "key",
                    Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));
            this.byQuery = byQuery;
        }

        @Override
        public List<StockPrice> searchByName(String name) {
            byName.add(name);
            return byQuery.getOrDefault(name, List.of());
        }

        @Override
        public List<StockPrice> searchByCode(String code) {
            byCode.add(code);
            return byQuery.getOrDefault(code, List.of());
        }
    }
}
