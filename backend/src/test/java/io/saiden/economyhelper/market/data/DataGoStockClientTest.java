package io.saiden.economyhelper.market.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockSource;
import io.saiden.economyhelper.market.data.MarketIndexApi.MarketIndex;
import io.saiden.economyhelper.market.data.StockPriceApi.StockPrice;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 이 클래스의 핵심 주장을 고정한다: <b>동명 후보를 시가총액으로 가른다.</b>
 *
 * <p>아래 값들은 2026-08-11 실측이다. {@code 삼성}은 26건이 걸리는데 시총 1위가 삼성전자이고,
 * 우선주·자회사가 정확히 밀린다. {@code CryptoService}가 24시간 거래대금으로 하는 일과 같다.
 *
 * <p>HTTP는 {@code StockPriceApiTest}·{@code MarketIndexApiTest}가 따로 본다. 여기서는
 * <b>해석 규칙</b>과 <b>SPI 계약</b>(못 주면 던진다)만 본다.
 */
class DataGoStockClientTest {

    private static final String TODAY = "20260811";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 실측 시가총액 (원). 삼성전자 1,400조 vs 삼성전자우 144조. */
    private static final List<StockPrice> SAMSUNG = List.of(
            price("삼성전자", "239500", "1400183726616000"),
            price("삼성전자우", "180200", "144587290780600"),
            price("삼성물산", "180000", "33000000000000"));

    private static final List<StockPrice> KAKAO = List.of(
            price("카카오", "40700", "18000000000000"),
            price("카카오뱅크", "22000", "10000000000000"));

    private static StockPrice price(String name, String close, String cap) {
        return new StockPrice(TODAY, name, close, null, cap);
    }

    @Test
    @DisplayName("자회사가 모회사를 이기지 않는다 — 시가총액으로 가른다")
    void parentBeatsSubsidiary() {
        assertThat(client(Map.of("카카오", KAKAO), null).byName("카카오"))
                .get().extracting(StockQuote::name).isEqualTo("카카오");
    }

    @Test
    @DisplayName("여러 날짜가 섞여 와도 가장 최근 기준일만 비교한다")
    void comparesOnlyLatestBasisDate() {
        // 어제 삼성전자우(시총 큼)와 오늘 삼성전자가 섞이면 날짜를 안 맞출 때 우선주가 이긴다
        List<StockPrice> mixed = List.of(
                new StockPrice("20260810", "삼성전자우", "180200", null, "9999999999999999"),
                new StockPrice("20260811", "삼성전자", "239500", null, "1400183726616000"));

        StockQuote quote = client(Map.of("삼성", mixed), null).byName("삼성").orElseThrow();

        assertThat(quote.name()).isEqualTo("삼성전자");
        assertThat(quote.at()).isEqualTo(LocalDate.of(2026, 8, 11).atStartOfDay(SEOUL).toInstant());
    }

    @Test
    @DisplayName("전일 종가임을 값에 새긴다 — 화면의 기준 줄이 '(종가)'로 갈리는 근거다")
    void marksQuotesAsClosingPrices() {
        StockQuote quote = client(Map.of("005930", SAMSUNG.subList(0, 1)), null).stock("005930");

        assertThat(quote.source()).isEqualTo(StockSource.DATA_GO);
        assertThat(quote.realtime()).isFalse();
        assertThat(quote.price()).isEqualByComparingTo("239500");
        assertThat(quote.market()).isEqualTo(StockQuote.Market.DOMESTIC);
    }

    @Test
    @DisplayName("등락률이 없으면 null이다 — 0으로 채우면 화면이 '보합'이라고 거짓말한다")
    void neverInventsFlatChange() {
        assertThat(client(Map.of("005930", SAMSUNG.subList(0, 1)), null).stock("005930").changePercent())
                .isNull();
    }

    @Test
    @DisplayName("SPI 조회는 못 주면 던진다 — 빈 값을 돌려주면 이중화가 폴백하지 않는다")
    void throwsSoThatFailoverCanHappen() {
        DataGoStockClient client = client(Map.of(), null);

        assertThatThrownBy(() -> client.stock("999999")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.index(new Index("없는지수", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이름 검색은 빈손을 돌려준다 — 그건 장애가 아니라 '그런 종목이 없다'이다")
    void nameSearchReturnsEmptyInsteadOfThrowing() {
        assertThat(client(Map.of(), null).byName("없는종목")).isEmpty();
    }

    @Test
    @DisplayName("지수는 이름으로 찾는다 — 이 API에는 업종코드 조회가 없다")
    void findsIndexByNameNotCode() {
        RecordingIndexApi indices = new RecordingIndexApi(
                new MarketIndex("20260811", "코스피", "6345.53", "0.42"));

        StockQuote quote = client(Map.of(), indices).index(new Index("코스피", "0001"));

        assertThat(indices.asked)
                .as("업종코드 0001은 1순위(KIS)가 쓰는 키다 — 여기서는 쓸 곳이 없다")
                .containsExactly("코스피");
        assertThat(quote.name()).isEqualTo("코스피");
        assertThat(quote.price()).isEqualByComparingTo("6345.53");
        assertThat(quote.currency()).isEqualTo(StockQuote.Money.NONE);
    }

    private static DataGoStockClient client(Map<String, List<StockPrice>> byQuery,
                                            MarketIndexApi indices) {
        return new DataGoStockClient(new RecordingPriceApi(byQuery),
                indices == null ? new RecordingIndexApi(null) : indices);
    }

    private static Clock fixed() {
        return Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
    }

    private static final class RecordingPriceApi extends StockPriceApi {
        private final Map<String, List<StockPrice>> byQuery;

        private RecordingPriceApi(Map<String, List<StockPrice>> byQuery) {
            super(RestClient.builder(), "https://example.invalid", "key", fixed());
            this.byQuery = byQuery;
        }

        @Override
        public List<StockPrice> searchByName(String name) {
            return byQuery.getOrDefault(name, List.of());
        }

        @Override
        public List<StockPrice> searchByCode(String code) {
            return byQuery.getOrDefault(code, List.of());
        }
    }

    private static final class RecordingIndexApi extends MarketIndexApi {
        private final MarketIndex answer;
        private final List<String> asked = new java.util.ArrayList<>();

        private RecordingIndexApi(MarketIndex answer) {
            super(RestClient.builder(), "https://example.invalid", "key", fixed());
            this.answer = answer;
        }

        @Override
        public MarketIndex searchByName(String name) {
            asked.add(name);
            return answer;
        }
    }
}
