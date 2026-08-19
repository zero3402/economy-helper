package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace;
import io.saiden.economyhelper.llm.GeminiApi;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>LLM은 해석만 하고 확정하지 않는다</b>({@code ARCHITECTURE.md} 4-5) — 그 계약을 이 해석기가
 * 지키는지 본다. 실재와 좌표는 지오코딩이 확정하므로 여기서 볼 것은 <b>무엇을 통과시키고
 * 무엇을 버리는가</b>다.
 *
 * <p><b>이 파일이 없었다.</b> 형제 둘({@code StockResolver}·{@code CryptoResolver})도 직접
 * 테스트가 없어 서비스 테스트의 가짜 객체로만 스쳤는데, 그 사이 세 해석기의 판단이 서로
 * 어긋나 있었다 — 이쪽만 {@code parsed == null}만 보고 내용을 안 봤다.
 */
class WeatherResolverTest {

    @Test
    @DisplayName("전부 null인 파싱은 버린다 — '성공'으로 캐시되면 안내 문구가 어긋난다")
    void discardsAParseThatCarriesNothing() {
        // WeatherFacade는 "지역을 안 적었다"와 "적었는데 못 찾았다"를 resolved.isPresent()로
        // 가른다. 아무것도 안 든 결과가 present면, 지역을 적은 사용자에게
        // "어느 지역인지 적어 주세요"가 나가고 그게 7일 캐시된다
        assertThat(resolve("""
                {"query":null,"country":null,"date":null,"month":null,"day":null,
                 "offsetDays":null,"days":null}""")).isEmpty();
    }

    @Test
    @DisplayName("지명만 있어도 통과시킨다 — 기간을 안 적은 물음이 대부분이다")
    void keepsAPlaceWithoutAPeriod() {
        Optional<ResolvedPlace> resolved = resolve("{\"query\":\"성남시\",\"country\":\"KR\"}");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().hasPlace()).isTrue();
        assertThat(resolved.get().countryCode()).isEqualTo("KR");
    }

    @Test
    @DisplayName("기간만 있어도 통과시킨다 — 지역은 원문으로 다시 찾는다")
    void keepsAPeriodWithoutAPlace() {
        // '일주일치 날씨'처럼 지역이 없는 물음도 해석은 성공한 것이다. 지역을 못 읽었다는
        // 사실 자체가 호출자에게 필요한 정보다(NO_PLACE 안내가 그것으로 갈린다)
        Optional<ResolvedPlace> resolved = resolve("{\"query\":null,\"days\":7}");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().hasPlace()).isFalse();
    }

    @Test
    @DisplayName("나라 코드의 \\\"null\\\" 문자열을 걸러낸다 — 그대로 나가면 헛호출을 한 번 태운다")
    void normalizesTheLiteralNullCountry() {
        // LLM이 문자열 "null"을 주는 일이 실제로 있어 hasPlace()·absoluteDate()가 이미
        // 막고 있었는데 나라만 빠져 있었다. countryCode=null이 쿼리에 실리면 지오코딩이
        // 빈손을 주고, 그 뒤에야 원문으로 폴백한다 — 조회가 조용히 두 배가 된다
        assertThat(resolve("{\"query\":\"파리\",\"country\":\"null\"}").orElseThrow().countryCode())
                .isNull();
        assertThat(resolve("{\"query\":\"파리\",\"country\":\"  \"}").orElseThrow().countryCode())
                .isNull();
    }

    @Test
    @DisplayName("LLM이 죽으면 빈손이다 — 호출자가 원문으로 지오코딩을 시도한다")
    void returnsEmptyWhenTheModelFails() {
        WeatherResolver resolver = new WeatherResolver(new FailingApi(), new ObjectMapper());

        assertThat(resolver.resolve("서현")).isEmpty();
    }

    @Test
    @DisplayName("빈 검색어는 LLM을 부르지 않는다 — 물어볼 것이 없다")
    void neverCallsTheModelForABlankQuery() {
        FailingApi api = new FailingApi();

        assertThat(new WeatherResolver(api, new ObjectMapper()).resolve("  ")).isEmpty();
        assertThat(api.called).isFalse();
    }

    private static Optional<ResolvedPlace> resolve(String json) {
        return new WeatherResolver(new FixedApi(json), new ObjectMapper()).resolve("서현");
    }

    private static final class FixedApi extends GeminiApi {
        private final String response;

        private FixedApi(String response) {
            super(RestClient.builder(), "https://example.invalid", "key", "model");
            this.response = response;
        }

        @Override
        public String generate(String prompt) {
            return response;
        }
    }

    private static final class FailingApi extends GeminiApi {
        private boolean called;

        private FailingApi() {
            super(RestClient.builder(), "https://example.invalid", "key", "model");
        }

        @Override
        public String generate(String prompt) {
            called = true;
            throw new IllegalStateException("Gemini 호출 실패");
        }
    }
}
