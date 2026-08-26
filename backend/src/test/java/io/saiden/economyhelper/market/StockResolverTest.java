package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.StockResolver.ResolvedStock;
import io.saiden.economyhelper.support.TestGemini;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>LLM은 해석만 하고 확정하지 않는다</b>({@code ARCHITECTURE.md} 4-5) — 이 해석기가 그 계약을
 * 지키는지 본다. 종목의 실재는 시세 API가 확정하므로 여기서 볼 것은 <b>무엇을 통과시키고 무엇을
 * 버리는가</b>다.
 *
 * <p><b>이 파일이 없었다.</b> {@code WeatherResolverTest}의 javadoc이 그 사실을 적어 두고 있었고,
 * 그 사이 프롬프트가 <b>미국 거래소를 NASDAQ·NYSE로 좁혀</b> NYSE Arca 상장 ETF
 * ({@code JEPI}·{@code SCHD}·{@code SOXL})를 전부 {@code null}로 만들었다 —
 * {@code StockServiceTest}는 해석기를 가짜로 바꿔 치기 때문에 그것을 볼 수 없었다.
 */
class StockResolverTest {

    @Test
    @DisplayName("미국 ETF 티커를 통과시킨다 — 프롬프트가 거래소를 좁히던 자리다")
    void keepsAUsEtfTicker() {
        Optional<ResolvedStock> resolved =
                resolve("{\"market\":\"US\",\"kind\":\"STOCK\",\"code\":\"SCHD\",\"name\":\"SCHD\"}");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().isUs()).isTrue();
        assertThat(resolved.get().isIndex()).isFalse();
        assertThat(resolved.get().code()).isEqualTo("SCHD");
    }

    @Test
    @DisplayName("프롬프트가 미국을 두 거래소로 좁히지 않는다 — Arca ETF가 그 규칙에 걸렸다")
    void neverNarrowsTheUsMarketToTwoExchanges() {
        // ⚠️ 프롬프트 문구를 검사하는 유일한 자리다. 「NASDAQ·NYSE만」이라고 적혀 있던 동안
        //    규칙을 지키는 모델은 SCHD·JEPI·SOXL에 전부 null을 냈고, 그 빈손이 어디서 나는지
        //    코드만 봐서는 알 수 없었다(거래소 탐색이 NAS·NYS뿐인 것과 원인이 겹쳐 보였다)
        TestGemini.Recording api =
                TestGemini.recording("{\"market\":null,\"kind\":null,\"code\":null,\"name\":null}");
        new StockResolver(api, new ObjectMapper()).resolve("schd");

        assertThat(api.prompt()).doesNotContain("미국 거래소(NASDAQ·NYSE)만");
        assertThat(api.prompt())
                .as("거래소를 가리지 않는다는 것과 티커를 대문자로 낸다는 것이 함께 적혀야 한다")
                .contains("거래소를 가리지 않습니다")
                .contains("대문자로");
    }

    @Test
    @DisplayName("전부 null인 파싱은 버린다 — '성공'으로 캐시되면 7일 동안 빈손이 굳는다")
    void discardsAParseThatCarriesNothing() {
        assertThat(resolve("{\"market\":null,\"kind\":null,\"code\":null,\"name\":null}")).isEmpty();
    }

    @Test
    @DisplayName("문자열 \"null\"은 값이 아니다 — 그대로 실리면 없는 코드로 조회를 태운다")
    void treatsTheLiteralNullAsBlank() {
        ResolvedStock resolved =
                resolve("{\"market\":\"KR\",\"kind\":\"STOCK\",\"code\":\"null\",\"name\":\"삼성전자\"}")
                        .orElseThrow();

        assertThat(resolved.hasCode()).isFalse();
        assertThat(resolved.hasName()).isTrue();
    }

    @Test
    @DisplayName("market이 비면 국내로 본다 — 미국은 명시해야 한다")
    void defaultsToDomesticWhenTheMarketIsBlank() {
        assertThat(resolve("{\"kind\":\"STOCK\",\"code\":\"005930\",\"name\":\"삼성전자\"}")
                .orElseThrow().isUs()).isFalse();
    }

    @Test
    @DisplayName("미국 지수는 kind로 갈린다 — 조회 경로가 통째로 다르다")
    void marksUsIndices() {
        ResolvedStock resolved =
                resolve("{\"market\":\"US\",\"kind\":\"INDEX\",\"code\":\"^IXIC\",\"name\":\"나스닥\"}")
                        .orElseThrow();

        assertThat(resolved.isIndex()).isTrue();
        assertThat(resolved.isUs()).isTrue();
    }

    @Test
    @DisplayName("LLM이 죽으면 빈손이다 — 호출자가 원문으로 이름·티커를 시도한다")
    void returnsEmptyWhenTheModelFails() {
        assertThat(new StockResolver(TestGemini.failing(), new ObjectMapper()).resolve("삼성전자"))
                .isEmpty();
    }

    @Test
    @DisplayName("빈 검색어는 LLM을 부르지 않는다 — 물어볼 것이 없다")
    void neverCallsTheModelForABlankQuery() {
        TestGemini.Failing api = TestGemini.failing();

        assertThat(new StockResolver(api, new ObjectMapper()).resolve("  ")).isEmpty();
        assertThat(api.called()).isFalse();
    }

    private static Optional<ResolvedStock> resolve(String json) {
        return new StockResolver(TestGemini.answering(json), new ObjectMapper()).resolve("무언가");
    }

}
