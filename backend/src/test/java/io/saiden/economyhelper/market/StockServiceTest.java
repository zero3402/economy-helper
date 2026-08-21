package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.data.DataGoStockClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이 클래스가 책임지는 것은 <b>어디로 보내고, 죽으면 어디로 넘기느냐</b> 둘뿐이다.
 *
 * <p>값을 어떻게 읽는지는 출처가 안다({@code KisStockApiTest}·{@code DataGoStockClientTest}·
 * {@code FmpApiTest}). 동명 후보를 시가총액으로 가르는 규칙도 공공데이터포털 쪽으로 옮겨 갔다 —
 * 그 응답 모양을 아는 곳이 거기뿐이어야 하기 때문이다.
 *
 * <p>가장 중요한 주장은 <b>1순위가 성공하면 2순위를 부르지 않는다</b>는 것이다. 그게 무너지면
 * 이중화가 아니라 "매번 둘 다 부르기"가 되고, FMP 하루 250회가 헛되이 탄다.
 */
class StockServiceTest {

    private static final Index KOSPI = new Index("코스피", "0001");
    private static final Instant AT = Instant.parse("2026-08-18T08:00:00Z");

    private static StockQuote krStock(String name, String price, StockSource source) {
        return new StockQuote(name, new BigDecimal(price), null, StockQuote.Money.KRW,
                StockQuote.Market.DOMESTIC, source, AT, source == StockSource.KIS);
    }

    private static StockQuote usQuote(String name, StockSource source) {
        return new StockQuote(name, new BigDecimal("306.19"), null, StockQuote.Money.USD,
                StockQuote.Market.US, source, AT, true);
    }

    // --- 이중화 ---

    @Test
    @DisplayName("국내는 한국투자증권이 1순위다 — 실시간을 주는 유일한 출처다")
    void prefersKisForDomestic() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of("005930", krStock("삼성전자", "268500", StockSource.KIS)));
        FakeDomestic dataGo = domestic(StockSource.DATA_GO, Map.of("005930", krStock("삼성전자", "274500", StockSource.DATA_GO)));

        // 목록을 일부러 거꾸로 넘긴다 — 순서는 주입 순서가 아니라 서비스가 정한다
        StockQuote quote = service(List.of(dataGo, kis), List.of(), noResolver())
                .quote("005930").orElseThrow();

        assertThat(quote.source()).isEqualTo(StockSource.KIS);
        assertThat(quote.realtime()).isTrue();
        assertThat(dataGo.asked).as("1순위가 성공했으므로 2순위는 호출조차 되지 않는다").isEmpty();
    }

    @Test
    @DisplayName("1순위가 죽으면 2순위가 답한다 — 값의 성격은 전일 종가로 내려앉는다")
    void fallsBackToDataGo() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of());   // 걸리는 것이 없으면 던진다
        FakeDomestic dataGo = domestic(StockSource.DATA_GO, Map.of("005930", krStock("삼성전자", "274500", StockSource.DATA_GO)));

        StockQuote quote = service(List.of(kis, dataGo), List.of(), noResolver())
                .quote("005930").orElseThrow();

        assertThat(quote.source()).isEqualTo(StockSource.DATA_GO);
        assertThat(quote.realtime())
                .as("폴백이 일어난 사실이 화면의 기준 줄에 '(종가)'로 드러난다")
                .isFalse();
        assertThat(kis.asked).as("1순위를 건너뛰지는 않는다").containsExactly("005930");
    }

    @Test
    @DisplayName("미국도 한국투자증권이 1순위이고 FMP가 받친다")
    void prefersKisForUs() {
        FakeUs kis = us(StockSource.KIS, Map.of());
        FakeUs fmp = us(StockSource.FMP, Map.of("AAPL", usQuote("애플", StockSource.FMP)));

        StockQuote quote = service(List.of(), List.of(fmp, kis),
                resolver(new ResolvedStock("US", "STOCK", "AAPL", "애플")))
                .quote("애플").orElseThrow();

        assertThat(kis.asked).as("1순위를 먼저 부른다").containsExactly("AAPL");
        assertThat(quote.source()).isEqualTo(StockSource.FMP);
    }

    @Test
    @DisplayName("모든 출처가 죽어도 예외를 밖으로 내보내지 않는다 — 웹훅은 어떤 경우에도 200이어야 한다")
    void degradesWhenEverySourceFails() {
        StockService service = service(List.of(domestic(StockSource.KIS, Map.of()),
                domestic(StockSource.DATA_GO, Map.of())), List.of(), noResolver());

        assertThat(service.quote("005930")).isEmpty();
        assertThat(service.quotesOf(List.of("005930"))).isEmpty();
        assertThat(service.indicesOf(List.of(KOSPI))).isEmpty();
    }

    // --- 라우팅 ---

    @Test
    @DisplayName("6자리 종목코드는 LLM을 건너뛴다 — 해석할 것이 없는데 Gemini를 태울 이유가 없다")
    void skipsLlmForPlainStockCode() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of("005930", krStock("삼성전자", "268500", StockSource.KIS)));
        StockService service = service(List.of(kis), List.of(), explodingResolver());

        // 군더더기가 붙은 형태도 같은 길로 간다 — QueryNormalizer가 '주가'를 떼어 준다
        for (String query : List.of("005930", "005930 주가", " 005930 ")) {
            assertThat(service.quote(query)).as("입력 '%s'", query)
                    .get().extracting(StockQuote::name).isEqualTo("삼성전자");
        }
    }

    @Test
    @DisplayName("없는 종목코드는 이름 검색으로 넘기지 않는다 — 6자리 숫자는 이름일 수 없다")
    void returnsEmptyForUnknownStockCode() {
        RecordingNames names = new RecordingNames(Map.of());
        StockService service = service(List.of(domestic(StockSource.KIS, Map.of())),
                List.of(), explodingResolver(), names);

        assertThat(service.quote("999999")).isEmpty();
        assertThat(names.asked).isEmpty();
    }

    @Test
    @DisplayName("LLM이 준 종목코드로 먼저 조회한다 — 이름 검색을 태우지 않는다")
    void usesCodeFromLlmFirst() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of("005930", krStock("삼성전자", "268500", StockSource.KIS)));
        RecordingNames names = new RecordingNames(Map.of());

        StockService service = service(List.of(kis), List.of(),
                resolver(new ResolvedStock("KR", "STOCK", "005930", "삼성전자")), names);

        assertThat(service.quote("삼전").orElseThrow().name()).isEqualTo("삼성전자");
        assertThat(kis.asked).containsExactly("005930");
        assertThat(names.asked).as("코드가 걸리면 이름 검색은 하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("LLM이 없는 종목코드를 지어내면 이름으로 되돌아간다 — 환각을 그대로 믿지 않는다")
    void fallsBackToNameWhenLlmCodeIsBogus() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of());
        RecordingNames names = new RecordingNames(Map.of("삼성전자", krStock("삼성전자", "268500", StockSource.DATA_GO)));

        StockService service = service(List.of(kis), List.of(),
                resolver(new ResolvedStock("KR", "STOCK", "999999", "삼성전자")), names);

        assertThat(service.quote("삼전").orElseThrow().name()).isEqualTo("삼성전자");
        assertThat(kis.asked).as("지어낸 코드로 한 번은 조회해 본다").containsExactly("999999");
        assertThat(names.asked).as("비었으므로 이름으로 되돌아간다").contains("삼성전자");
    }

    @Test
    @DisplayName("LLM이 죽어도 원문으로 찾는다 — Gemini 장애가 /stock 전면 중단이 되면 안 된다")
    void fallsBackToRawQueryWhenLlmFails() {
        RecordingNames names = new RecordingNames(Map.of("삼성전자", krStock("삼성전자", "268500", StockSource.DATA_GO)));
        StockService service = service(List.of(), List.of(), noResolver(), names);

        assertThat(service.quote("삼성전자").orElseThrow().name()).isEqualTo("삼성전자");
        assertThat(names.asked).contains("삼성전자");
    }

    @Test
    @DisplayName("걸리는 종목이 없으면 빈 결과 — 아무거나 돌려주면 오해한다")
    void returnsEmptyWhenNothingMatches() {
        StockService service = service(List.of(), List.of(), noResolver());

        assertThat(service.quote("없는종목zzz")).isEmpty();
        assertThat(service.quote("")).isEmpty();
        assertThat(service.quote(null)).isEmpty();
    }

    @Test
    @DisplayName("지수는 종목이 아니라 지수 조회로 간다 — 종목코드도 통화 단위도 없다")
    void routesIndexToIndexLookup() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of(),
                Map.of("코스피", new StockQuote("코스피", new BigDecimal("6869.83"), null,
                        StockQuote.Money.NONE, StockQuote.Market.DOMESTIC, StockSource.KIS, AT, true)));
        RecordingNames names = new RecordingNames(Map.of());

        StockService service = service(List.of(kis), List.of(),
                resolver(new ResolvedStock("KR", "INDEX", null, "코스피")), names);

        StockQuote match = service.quote("코스피").orElseThrow();

        assertThat(match.name()).isEqualTo("코스피");
        assertThat(match.currency())
                .as("통화가 없는 것이 곧 지수라는 뜻이다")
                .isEqualTo(StockQuote.Money.NONE);
        assertThat(kis.askedIndex)
                .as("업종코드는 비워 보낸다 — LLM에게 지수코드를 지어내게 두지 않는다")
                .containsExactly(new Index("코스피", null));
        assertThat(names.asked).as("지수는 종목 검색을 태우지 않는다").isEmpty();
    }

    @Test
    @DisplayName("지수를 못 찾으면 빈 결과 — 종목으로 되돌아가지 않는다")
    void returnsEmptyWhenIndexNotFound() {
        RecordingNames names = new RecordingNames(Map.of());
        StockService service = service(List.of(domestic(StockSource.KIS, Map.of())), List.of(),
                resolver(new ResolvedStock("KR", "INDEX", null, "없는지수")), names);

        assertThat(service.quote("없는지수")).isEmpty();
        assertThat(names.asked).isEmpty();
    }

    @Test
    @DisplayName("미국은 국내 출처를 태우지 않는다 — 공공데이터포털에 애플이 있을 리 없다")
    void neverAsksDomesticSourcesForUs() {
        FakeDomestic kis = domestic(StockSource.KIS, Map.of());
        RecordingNames names = new RecordingNames(Map.of());
        FakeUs fmp = us(StockSource.FMP, Map.of("AAPL", usQuote("애플", StockSource.FMP)));

        StockService service = service(List.of(kis), List.of(fmp),
                resolver(new ResolvedStock("US", "STOCK", "AAPL", "애플")), names);

        assertThat(service.quote("애플").orElseThrow().name()).isEqualTo("애플");
        assertThat(kis.asked).isEmpty();
        assertThat(names.asked).isEmpty();
    }

    @Test
    @DisplayName("미국인데 티커가 없으면 포기한다 — 이름으로 되짚을 경로가 없다")
    void givesUpWhenUsTickerMissing() {
        FakeUs fmp = us(StockSource.FMP, Map.of());
        StockService service = service(List.of(), List.of(fmp),
                resolver(new ResolvedStock("US", "STOCK", null, "무언가")));

        assertThat(service.quote("무언가")).isEmpty();
        assertThat(fmp.asked).as("물어볼 심볼이 없는데 호출을 태울 이유가 없다").isEmpty();
    }

    @Test
    @DisplayName("검색은 LLM이 준 한국어 이름을 실어 보낸다 — 미국만 영문이면 화면 표기가 갈린다")
    void carriesTheKoreanNameIntoTheUsLookup() {
        FakeUs fmp = us(StockSource.FMP, Map.of("AAPL", usQuote("애플", StockSource.FMP)));
        service(List.of(), List.of(fmp), resolver(new ResolvedStock("US", "STOCK", "AAPL", "애플")))
                .quote("애플");

        assertThat(fmp.askedSymbols).containsExactly(new UsSymbol("AAPL", "애플"));
    }

    // --- 브리핑 (설정에 박힌 목록) ---

    @Test
    @DisplayName("설정된 종목·지수·미국 심볼은 설정 순서를 지킨다")
    void keepsConfiguredOrder() {
        FakeDomestic kis = domestic(StockSource.KIS,
                Map.of("005930", krStock("삼성전자", "268500", StockSource.KIS),
                        "000660", krStock("SK하이닉스", "1120000", StockSource.KIS)),
                Map.of("코스피", krStock("코스피", "6869", StockSource.KIS),
                        "코스닥", krStock("코스닥", "857", StockSource.KIS)));
        FakeUs kisUs = us(StockSource.KIS,
                Map.of("^IXIC", usQuote("나스닥", StockSource.KIS),
                        "AAPL", usQuote("애플", StockSource.KIS)));
        StockService service = service(List.of(kis), List.of(kisUs), noResolver());

        assertThat(service.quotesOf(List.of("005930", "000660")))
                .extracting(StockQuote::name).containsExactly("삼성전자", "SK하이닉스");
        assertThat(service.indicesOf(List.of(KOSPI, new Index("코스닥", "1001"))))
                .extracting(StockQuote::name).containsExactly("코스피", "코스닥");
        assertThat(service.usQuotesOf(List.of(
                new UsSymbol("^IXIC", "나스닥"), new UsSymbol("AAPL", "애플"))))
                .extracting(StockQuote::name).containsExactly("나스닥", "애플");
    }

    @Test
    @DisplayName("하나가 죽어도 나머지는 나온다 — 오타 하나가 발송 전체를 막으면 안 된다")
    void survivesPartialFailure() {
        FakeDomestic kis = domestic(StockSource.KIS,
                Map.of("005930", krStock("삼성전자", "268500", StockSource.KIS)),
                Map.of("코스피", krStock("코스피", "6869", StockSource.KIS)));
        FakeUs kisUs = us(StockSource.KIS, Map.of("AAPL", usQuote("애플", StockSource.KIS)));
        StockService service = service(List.of(kis), List.of(kisUs), noResolver());

        assertThat(service.quotesOf(List.of("005930", "999999")))
                .extracting(StockQuote::name).containsExactly("삼성전자");
        assertThat(service.indicesOf(List.of(KOSPI, new Index("없는지수", "9999"))))
                .extracting(StockQuote::name).containsExactly("코스피");
        assertThat(service.usQuotesOf(List.of(
                new UsSymbol("AAPL", "애플"), new UsSymbol("BAD", "없는것"))))
                .extracting(StockQuote::name).containsExactly("애플");
    }

    // --- 스텁 ---

    private static StockService service(List<DomesticStockClient> domestic, List<UsStockClient> us,
                                        StockResolver resolver) {
        return service(domestic, us, resolver, new RecordingNames(Map.of()));
    }

    private static StockService service(List<DomesticStockClient> domestic, List<UsStockClient> us,
                                        StockResolver resolver, DataGoStockClient names) {
        // 전망은 여기서 보지 않는다 — KisDomesticOutlookClientTest가 본다
        return new StockService(domestic, us, names, resolver, code -> java.util.Optional.empty());
    }

    private static FakeDomestic domestic(StockSource source, Map<String, StockQuote> stocks) {
        return domestic(source, stocks, Map.of());
    }

    private static FakeDomestic domestic(StockSource source, Map<String, StockQuote> stocks,
                                         Map<String, StockQuote> indices) {
        return new FakeDomestic(source, stocks, indices);
    }

    private static FakeUs us(StockSource source, Map<String, StockQuote> quotes) {
        return new FakeUs(source, quotes);
    }

    /** 모르는 것은 <b>던진다</b> — SPI 계약이 그렇고, 그래야 이중화가 폴백한다. */
    private static final class FakeDomestic implements DomesticStockClient {
        private final StockSource source;
        private final Map<String, StockQuote> stocks;
        private final Map<String, StockQuote> indices;
        private final List<String> asked = new ArrayList<>();
        private final List<Index> askedIndex = new ArrayList<>();

        private FakeDomestic(StockSource source, Map<String, StockQuote> stocks,
                             Map<String, StockQuote> indices) {
            this.source = source;
            this.stocks = stocks;
            this.indices = indices;
        }

        @Override
        public StockSource source() {
            return source;
        }

        @Override
        public StockQuote stock(String code) {
            asked.add(code);
            return require(stocks.get(code), code);
        }

        @Override
        public StockQuote index(Index index) {
            askedIndex.add(index);
            return require(indices.get(index.name()), index.name());
        }

        private static StockQuote require(StockQuote quote, String what) {
            if (quote == null) {
                throw new IllegalStateException("'" + what + "' 시세가 없습니다");
            }
            return quote;
        }
    }

    private static final class FakeUs implements UsStockClient {
        private final StockSource source;
        private final Map<String, StockQuote> quotes;
        private final List<String> asked = new ArrayList<>();
        private final List<UsSymbol> askedSymbols = new ArrayList<>();

        private FakeUs(StockSource source, Map<String, StockQuote> quotes) {
            this.source = source;
            this.quotes = quotes;
        }

        @Override
        public StockSource source() {
            return source;
        }

        @Override
        public StockQuote quote(UsSymbol symbol) {
            asked.add(symbol.symbol());
            askedSymbols.add(symbol);
            StockQuote found = quotes.get(symbol.symbol());
            if (found == null) {
                throw new IllegalStateException("'" + symbol.symbol() + "' 심볼이 없습니다");
            }
            return found;
        }
    }

    /**
     * 이름 검색은 이중화되지 않으므로 SPI가 아니다 — 없으면 빈손이지 예외가 아니다
     * (그 구분은 {@code DataGoStockClientTest}가 본다).
     */
    private static final class RecordingNames extends DataGoStockClient {
        private final Map<String, StockQuote> answers;
        private final List<String> asked = new ArrayList<>();

        private RecordingNames(Map<String, StockQuote> answers) {
            super(null, null);
            this.answers = answers;
        }

        @Override
        public Optional<StockQuote> byName(String name) {
            asked.add(name);
            return Optional.ofNullable(answers.get(name));
        }
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
}
