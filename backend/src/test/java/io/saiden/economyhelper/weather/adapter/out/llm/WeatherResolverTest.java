package io.saiden.economyhelper.weather.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.testsupport.TestGemini;
import io.saiden.economyhelper.weather.domain.ResolvedPlace;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>LLM은 해석만 하고 확정하지 않는다</b>({@code docs/design.md} 4.6) — 그 계약을 이 해석기가
 * 지키는지 본다. 실재와 좌표는 지오코딩이 확정하므로 여기서 볼 것은 <b>무엇을 통과시키고
 * 무엇을 버리는가</b>다.
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
        // LLM이 문자열 "null"을 주는 일이 실제로 있다. countryCode=null이 쿼리에 실리면 지오코딩이
        // 빈손을 주고, 그 뒤에야 원문으로 폴백한다 — 조회가 조용히 두 배가 된다
        assertThat(resolve("{\"query\":\"파리\",\"country\":\"null\"}").orElseThrow().countryCode())
                .isNull();
        assertThat(resolve("{\"query\":\"파리\",\"country\":\"  \"}").orElseThrow().countryCode())
                .isNull();
    }

    @Test
    @DisplayName("LLM이 죽으면 빈손이다 — 호출자가 원문으로 지오코딩을 시도한다")
    void returnsEmptyWhenTheModelFails() {
        WeatherResolver resolver =
                new WeatherResolver(TestGemini.failing(), new ObjectMapper());

        assertThat(resolver.resolve("서현")).isEmpty();
    }

    @Test
    @DisplayName("빈 검색어는 LLM을 부르지 않는다 — 물어볼 것이 없다")
    void neverCallsTheModelForABlankQuery() {
        TestGemini.Failing api = TestGemini.failing();

        assertThat(new WeatherResolver(api, new ObjectMapper()).resolve("  ")).isEmpty();
        assertThat(api.called()).isFalse();
    }

    @Test
    @DisplayName("LLM에는 사용자가 친 띄어쓰기 그대로 간다 — 다듬은 캐시 키(일주일치미금)를 보여 주면 낱말 경계가 사라진다")
    void showsTheModelTheQueryAsTyped() {
        // 제보(2026-09-29): /weather 일주일치 미금이 이틀치로 나왔다. 이틀이 나오는 길은 days=2
        // (프롬프트의 「주말 → 2」)뿐인데, 모델이 본 것은 공백·기호를 지운 '일주일치미금'이었다.
        // 8/16 서울 → '816서울'처럼 날짜 기호도 같이 사라진다
        TestGemini.Recording api = TestGemini.recording("{\"query\":\"성남시\",\"days\":7}");

        new WeatherResolver(api, new ObjectMapper()).resolve("  일주일치 미금 ");

        assertThat(api.prompt()).contains("입력: 일주일치 미금\n").doesNotContain("일주일치미금");
    }

    @Test
    @DisplayName("주말은 일수가 아니라 요일이다 — weekend를 읽어 넘긴다")
    void readsTheWeekendFlag() {
        Optional<ResolvedPlace> resolved = resolve("{\"query\":\"서울특별시\",\"weekend\":true}");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().weekend()).isTrue();
        assertThat(resolve("{\"weekend\":true}")).as("주말만 적어도 읽은 것이 있다").isPresent();
    }

    @Test
    @DisplayName("캐시 키는 날짜 기호를 지킨다 — 1/11과 11/1이 한 칸을 쓰면 먼저 물은 날짜가 7일 동안 답한다")
    void cacheKeyKeepsDateSeparators() {
        assertThat(WeatherResolver.cacheKey("1/11 서울")).isNotEqualTo(WeatherResolver.cacheKey("11/1 서울"));
        assertThat(WeatherResolver.cacheKey("  일주일치   미금 ")).isEqualTo("일주일치 미금");
        assertThat(WeatherResolver.cacheKey("ＰＡＲＩＳ")).as("전각·대소문자는 접는다").isEqualTo("paris");
    }

    @Test
    @DisplayName("요일을 읽어 넘긴다 — 날짜 계산은 코드가 한다")
    void readsTheWeekday() {
        ResolvedPlace resolved = resolve("{\"query\":\"부산광역시\",\"weekday\":1,\"weekOffset\":1}").orElseThrow();

        assertThat(resolved.weekday()).isEqualTo(1);
        assertThat(resolved.weekOffset()).isEqualTo(1);
        assertThat(resolve("{\"weekday\":7}")).as("요일만 적어도 읽은 것이 있다").isPresent();
    }

    private static Optional<ResolvedPlace> resolve(String json) {
        return new WeatherResolver(TestGemini.answering(json), new ObjectMapper()).resolve("서현");
    }

}
