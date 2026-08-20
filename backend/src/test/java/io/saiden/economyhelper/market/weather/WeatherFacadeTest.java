package io.saiden.economyhelper.market.weather;

import static org.assertj.core.api.Assertions.assertThat;
import io.saiden.economyhelper.support.TestWeather;

import io.saiden.economyhelper.market.weather.Weather.Daily;
import io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace;
import io.saiden.economyhelper.market.weather.openmeteo.GeocodingApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>해석기 → 지오코더 이음새.</b> 이 파일이 없어서 실물 버그를 놓쳤다.
 *
 * <p>{@code /weather 미금}이 {@code Seongnam, 대한민국}을 답한 일이 있다. 조각은 전부 맞았다 —
 * 해석기는 {@code 성남시}를 냈고(실측), 지오코더는 {@code 성남시}에 인구 914,832의 경기도
 * 성남시를 줬고(실측), 골든 파일은 {@code 성남시, 대한민국}을 지키며 초록이었다.
 * <b>틀린 것은 이 둘 사이였다</b>: 옛 프롬프트가 캐시에 남긴 {@code 성남}이 옛 선택 규칙이
 * 캐시에 남긴 {@code Seongnam}으로 이어졌다.
 *
 * <p>그래서 여기서 보는 것은 <b>단계별 정답이 아니라 이어 붙인 결과</b>다.
 * {@code ARCHITECTURE.md} 7이 "단위 테스트가 통과해도 이어 붙이면 틀릴 수 있다"고 적어 둔
 * 그 자리이고, 이 파일이 그 말을 테스트로 옮긴 것이다.
 */
class WeatherFacadeTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), SEOUL);

    @Test
    @DisplayName("'미금'을 물으면 '성남시, 대한민국'이 나온다 — 해석기와 지오코더를 이어 붙인 결과다")
    void resolvesAStationStemToItsCity() {
        // 실측(2026-08-20): 프롬프트가 '미금' → 성남시를 내고, 지오코딩이 '성남시'에
        // 인구 914,832의 경기도 성남시(37.43861, 127.13778)를 준다
        Geocoder geocoder = new Geocoder();
        geocoder.answer("성남시", "KR", place("성남시", "대한민국", 37.43861, 127.13778));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "성남시", "KR", null, null, null, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("미금");

        assertThat(found.reason()).isEqualTo(WeatherFacade.Lookup.Reason.FOUND);
        assertThat(found.places()).hasSize(1);
        assertThat(found.places().get(0).place().displayName())
                .as("사용자가 친 '미금'이 아니라 실제로 조회한 지점이 적혀야 검산이 된다")
                .isEqualTo("성남시, 대한민국");
        assertThat(geocoder.asked)
                .as("LLM이 다듬은 행정명으로 묻는다 — 짧은 이름은 엉뚱한 마을이 걸린다")
                .containsExactly("성남시|KR");
    }

    @Test
    @DisplayName("지오코딩이 로마자 이름을 줘도 화면에는 물어본 한국어 지명이 나간다")
    void neverShowsARomanisedNameOnScreen() {
        // 이것이 그 버그의 모양이다. 실측: name=성남&countryCode=KR의 후보[0]이
        // 'Seongnam'(전라북도 남원시 보절면)이었다. 표기를 읽을 때 만들므로 여기서 막힌다
        Geocoder geocoder = new Geocoder();
        geocoder.answer("제주시", "KR", place("Jejudo", "대한민국", 33.4022, 126.5464));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "제주시", "KR", null, null, null, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("제주");

        assertThat(found.places().get(0).place().displayName()).isEqualTo("제주시, 대한민국");
    }

    @Test
    @DisplayName("적었는데 못 찾은 것과 아예 안 적은 것을 가른다 — 이미 적은 사용자에게 적으라고 하면 안 된다")
    void tellsApartAMissingPlaceFromAnUnfoundOne() {
        WeatherFacade unfound = facade(resolver(new ResolvedPlace(
                "없는지명", "KR", null, null, null, null, null)), new Geocoder());
        assertThat(unfound.search("없는지명").reason())
                .isEqualTo(WeatherFacade.Lookup.Reason.NOT_FOUND);

        WeatherFacade noPlace = facade(resolver(new ResolvedPlace(
                null, null, null, null, null, null, 7)), new Geocoder());
        assertThat(noPlace.search("일주일치 날씨").reason())
                .isEqualTo(WeatherFacade.Lookup.Reason.NO_PLACE);
    }

    @Test
    @DisplayName("LLM이 죽어도 원문으로 다시 찾는다 — '파리'는 그걸로 걸린다")
    void fallsBackToTheRawQueryWhenTheLlmIsDead() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("파리", null, place("파리", "프랑스", 48.8566, 2.3522));
        WeatherFacade facade = facade(resolver(null), geocoder);

        WeatherFacade.Lookup found = facade.search("파리");

        assertThat(found.reason()).isEqualTo(WeatherFacade.Lookup.Reason.FOUND);
        assertThat(found.places().get(0).place().displayName()).isEqualTo("파리, 프랑스");
    }

    @Test
    @DisplayName("LLM이 준 지명을 못 찾으면 원문으로 한 번 더 묻는다 — 두 번 묻는 것이 계약이다")
    void triesTheRawQueryWhenTheResolvedNameMisses() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("Tokyo City", "JP", null);               // LLM이 준 것 — 없다
        geocoder.answer("Tokyo", null, place("도쿄", "일본", 35.6895, 139.6917));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "Tokyo City", "JP", null, null, null, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("Tokyo");

        assertThat(found.places().get(0).place().displayName()).isEqualTo("도쿄, 일본");
        assertThat(geocoder.asked).containsExactly("Tokyo City|JP", "Tokyo|null");
    }

    // --- 이음새를 재현하는 데 필요한 만큼만의 가짜들 ---

    private static WeatherFacade facade(WeatherResolver resolver, Geocoder geocoder) {
        return new WeatherFacade(resolver, geocoder,
                new WeatherService(List.of(new Forecaster()), CLOCK, TestWeather.noHourly()));
    }

    private static GeoLocation place(String name, String country, double lat, double lon) {
        return new GeoLocation(name, country, lat, lon, SEOUL);
    }

    /** {@code null}을 주면 해석 실패다 — 호출자가 원문 지오코딩으로 내려간다. */
    private static WeatherResolver resolver(ResolvedPlace answer) {
        return new WeatherResolver(new GeminiStub(), new ObjectMapper()) {
            @Override
            public Optional<ResolvedPlace> resolve(String normalizedQuery) {
                return Optional.ofNullable(answer);
            }
        };
    }

    /** 무엇을 물었는지 기록한다 — "두 번 묻는다"가 이 클래스의 계약이라 그걸 봐야 한다. */
    private static final class Geocoder extends GeocodingApi {

        private final List<String> asked = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final List<GeoLocation> answers = new ArrayList<>();

        private Geocoder() {
            super(RestClient.builder(), "http://localhost");
        }

        void answer(String query, String countryCode, GeoLocation answer) {
            keys.add(query + "|" + countryCode);
            answers.add(answer);
        }

        @Override
        public Optional<GeoLocation> find(String query, String countryCode) {
            String key = query + "|" + countryCode;
            asked.add(key);
            int at = keys.indexOf(key);
            return at < 0 ? Optional.empty() : Optional.ofNullable(answers.get(at));
        }
    }

    /** 지점이 무엇이든 하루치를 준다 — 이 파일이 보는 것은 예보 내용이 아니라 지점이다. */
    private static final class Forecaster implements WeatherClient {

        @Override
        public WeatherSource source() {
            return WeatherSource.OPEN_METEO;
        }

        @Override
        public Weather forecast(GeoLocation place, WeatherPeriod period) {
            return new Weather(place, List.of(Daily.withChance(period.from(), SkyCondition.CLEAR,
                    new BigDecimal("21"), new BigDecimal("29"), 10)), source());
        }

        @Override
        public boolean supports(WeatherPeriod period, LocalDate today) {
            return true;
        }
    }

    private static final class GeminiStub extends io.saiden.economyhelper.llm.GeminiApi {

        private GeminiStub() {
            super(RestClient.builder(), "http://localhost", "", "stub");
        }
    }
}
