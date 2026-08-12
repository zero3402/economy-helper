package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.market.StockService.StockMatch;
import io.saiden.economyhelper.market.data.StockPriceApi;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
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
    @DisplayName("동명 후보를 시가총액으로 가른다 — 우선주·자회사가 1위가 되면 안 된다")
    void marketCapResolvesAmbiguity() {
        StockService service = new StockService(new RecordingApi(Map.of("삼성", SAMSUNG)), noResolver());

        StockMatch match = service.quote("삼성").orElseThrow();

        assertThat(match.quote().name()).isEqualTo("삼성전자");
        assertThat(match.quote().code()).isEqualTo("005930");
    }

    @Test
    @DisplayName("함께 걸린 후보를 알려준다 — 되묻지 않고 한 번에 끝낸다")
    void reportsAlternatives() {
        StockService service = new StockService(new RecordingApi(Map.of("삼성", SAMSUNG)), noResolver());

        StockMatch match = service.quote("삼성").orElseThrow();

        assertThat(match.alternatives()).containsExactly("삼성전자우", "삼성물산");
    }

    @Test
    @DisplayName("자회사가 모회사를 이기지 않는다")
    void parentBeatsSubsidiary() {
        StockService service = new StockService(new RecordingApi(Map.of("카카오", KAKAO)), noResolver());

        assertThat(service.quote("카카오").orElseThrow().quote().name()).isEqualTo("카카오");
    }

    @Test
    @DisplayName("LLM이 준 종목코드로 먼저 조회한다 — 이름 검색을 태우지 않는다")
    void usesCodeFromLlmFirst() {
        RecordingApi api = new RecordingApi(Map.of("005930", SAMSUNG.subList(0, 1)));
        StockService service = new StockService(api, resolver(new ResolvedStock("005930", "삼성전자")));

        assertThat(service.quote("삼전").orElseThrow().quote().name()).isEqualTo("삼성전자");
        assertThat(api.byCode).contains("005930");
        assertThat(api.byName).as("코드가 걸리면 이름 검색은 하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("통칭과 상장명이 달라도 찾는다 — 네이버는 상장명이 NAVER(로마자)다")
    void resolvesCommonNameToListedName() {
        List<StockPrice> naver = List.of(price("035420", "NAVER", "KOSPI", "200000", "32000000000000"));
        RecordingApi api = new RecordingApi(Map.of("035420", naver));
        StockService service = new StockService(api, resolver(new ResolvedStock("035420", "NAVER")));

        assertThat(service.quote("네이버").orElseThrow().quote().name()).isEqualTo("NAVER");
    }

    @Test
    @DisplayName("LLM이 없는 종목코드를 지어내면 이름으로 되돌아간다 — 환각을 그대로 믿지 않는다")
    void fallsBackToNameWhenLlmCodeIsBogus() {
        RecordingApi api = new RecordingApi(Map.of("삼성전자", SAMSUNG.subList(0, 1)));
        StockService service = new StockService(api, resolver(new ResolvedStock("999999", "삼성전자")));

        assertThat(service.quote("삼전").orElseThrow().quote().name()).isEqualTo("삼성전자");
        assertThat(api.byCode).as("지어낸 코드로 한 번은 조회해 본다").contains("999999");
        assertThat(api.byName).as("비었으므로 이름으로 되돌아간다").contains("삼성전자");
    }

    @Test
    @DisplayName("LLM이 죽어도 원문으로 찾는다 — Gemini 장애가 /stock 전면 중단이 되면 안 된다")
    void fallsBackToRawQueryWhenLlmFails() {
        RecordingApi api = new RecordingApi(Map.of("삼성전자", SAMSUNG.subList(0, 2)));
        StockService service = new StockService(api, noResolver());

        assertThat(service.quote("삼성전자").orElseThrow().quote().name()).isEqualTo("삼성전자");
        assertThat(api.byName).contains("삼성전자");
    }

    @Test
    @DisplayName("여러 날짜가 섞여 와도 가장 최근 기준일만 비교한다")
    void comparesOnlyLatestBasisDate() {
        // 어제 삼성전자우(시총 큼)와 오늘 삼성전자가 섞이면 날짜를 안 맞출 때 우선주가 이긴다
        List<StockPrice> mixed = List.of(
                new StockPrice("20260810", "005935", "삼성전자우", "KOSPI", "180200", "9999999999999999"),
                new StockPrice("20260811", "005930", "삼성전자", "KOSPI", "239500", "1400183726616000"));
        StockService service = new StockService(new RecordingApi(Map.of("삼성", mixed)), noResolver());

        StockMatch match = service.quote("삼성").orElseThrow();

        assertThat(match.quote().name()).isEqualTo("삼성전자");
        assertThat(match.quote().basisDate()).hasToString("2026-08-11");
    }

    @Test
    @DisplayName("걸리는 종목이 없으면 빈 결과 — 아무거나 돌려주면 오해한다")
    void returnsEmptyWhenNothingMatches() {
        StockService service = new StockService(new RecordingApi(Map.of()), noResolver());

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
        StockService service = new StockService(exploding, noResolver());

        assertThat(service.quote("삼성전자")).isEmpty();
        assertThat(service.quotesOf(List.of("005930"))).isEmpty();
    }

    @Test
    @DisplayName("설정된 종목코드로도 조회한다 — 아침 브리핑이 이 경로를 쓴다")
    void quotesConfiguredCodes() {
        RecordingApi api = new RecordingApi(Map.of(
                "005930", SAMSUNG.subList(0, 1),
                "035720", KAKAO.subList(0, 1)));

        assertThat(new StockService(api, noResolver()).quotesOf(List.of("005930", "035720")))
                .extracting(StockQuote::name)
                .containsExactly("삼성전자", "카카오");
    }

    @Test
    @DisplayName("설정에 없는 종목코드는 조용히 건너뛴다 — 오타 하나가 발송 전체를 막으면 안 된다")
    void skipsUnknownConfiguredCodes() {
        RecordingApi api = new RecordingApi(Map.of("005930", SAMSUNG.subList(0, 1)));

        assertThat(new StockService(api, noResolver()).quotesOf(List.of("005930", "999999")))
                .extracting(StockQuote::name)
                .containsExactly("삼성전자");
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
