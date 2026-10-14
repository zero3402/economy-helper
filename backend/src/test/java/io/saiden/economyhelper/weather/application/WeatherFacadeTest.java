package io.saiden.economyhelper.weather.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.infrastructure.llm.GeminiApi;
import io.saiden.economyhelper.testsupport.TestGemini;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.testsupport.TestWeather;
import io.saiden.economyhelper.weather.adapter.out.llm.WeatherResolver;
import io.saiden.economyhelper.weather.adapter.out.openmeteo.GeocodingApi;
import io.saiden.economyhelper.weather.application.port.out.WeatherClient;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.ResolvedPlace;
import io.saiden.economyhelper.weather.domain.SkyCondition;
import io.saiden.economyhelper.weather.domain.Weather.Daily;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import io.saiden.economyhelper.weather.domain.WeatherSource;
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
 * <b>해석기 → 지오코더 이음새.</b> 여기서 보는 것은 <b>단계별 정답이 아니라 이어 붙인 결과</b>다
 * ({@code docs/design.md} 6 「단위 테스트가 통과해도 이어 붙이면 틀릴 수 있다」).
 *
 * <p>{@code /weather 미금}이 {@code Seongnam, 대한민국}을 답한 일이 있다 — 조각은 전부 맞았고
 * 틀린 것은 이음새(캐시에 남은 옛 값)였다. 경위는 {@code docs/design.md} 4.2 「긴 TTL 캐시가 고침보다 오래 산다」에 있다.
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
                "성남시", "KR", null, null, null, null, null, null, null, null)), geocoder);

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
                "제주시", "KR", null, null, null, null, null, null, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("제주");

        assertThat(found.places().get(0).place().displayName()).isEqualTo("제주시, 대한민국");
    }

    @Test
    @DisplayName("적었는데 못 찾은 것과 아예 안 적은 것을 가른다 — 이미 적은 사용자에게 적으라고 하면 안 된다")
    void tellsApartAMissingPlaceFromAnUnfoundOne() {
        WeatherFacade unfound = facade(resolver(new ResolvedPlace(
                "없는지명", "KR", null, null, null, null, null, null, null, null)), new Geocoder());
        assertThat(unfound.search("없는지명").reason())
                .isEqualTo(WeatherFacade.Lookup.Reason.NOT_FOUND);

        WeatherFacade noPlace = facade(resolver(new ResolvedPlace(
                null, null, null, null, null, null, 7, null, null, null)), new Geocoder());
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
    @DisplayName("LLM이 던져도 원문으로 다시 찾는다 — 죽은 해석기가 곧 「잠시 후 다시」는 아니다")
    void fallsBackToTheRawQueryWhenTheLlmThrows() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("파리", null, place("파리", "프랑스", 48.8566, 2.3522));
        WeatherFacade facade = facade(deadResolver(), geocoder);

        assertThat(facade.search("파리").reason()).isEqualTo(WeatherFacade.Lookup.Reason.FOUND);
    }

    @Test
    @DisplayName("LLM이 죽고 원문으로도 못 찾으면 「찾지 못했다」가 아니라 「잠시 후 다시」다 — 실물 감사 2026-10-14 「서현역 내일」")
    void signalsAnOutageWhenTheLlmIsDeadAndTheRawQueryFindsNothing() {
        // 진짜 해석기에 죽은 Gemini를 단다 — 해석기가 실패를 빈손으로 삼키던 것이 이 버그의 뿌리다
        WeatherFacade facade = facade(new WeatherResolver(TestGemini.failing(), new ObjectMapper()), new Geocoder());

        assertThat(facade.search("서현역 내일").reason()).isEqualTo(WeatherFacade.Lookup.Reason.UNAVAILABLE);
        assertThat(facade.search("미금").reason()).isEqualTo(WeatherFacade.Lookup.Reason.UNAVAILABLE);
    }

    @Test
    @DisplayName("LLM이 죽었는데 원문에 날짜·기간이 있으면 원문 지오코딩이 찾아도 「잠시 후 다시」다 — 「미금 내일」에 오늘 예보를 답하지 않는다")
    void doesNotAnswerTodayForADatedQueryWhenTheLlmIsDead() {
        // 원문 지오코딩이 무엇을 찾든(지오코더가 군더더기를 무시해 걸릴 수 있다) 해석기 없이는 기간을 못 편다 —
        // 그대로 답하면 묻지 않은 날(오늘)의 값이 나간다. 틀린 값은 빈칸보다 나쁘다
        List<String> dated = List.of("미금 내일", "내일 미금", "미금 모레", "미금 주말", "미금 일요일",
                "미금 다음 주", "미금 다음주 화요일", "미금 8/16", "미금 8월 16일", "일주일치 미금",
                "미금 3일치", "미금 사흘", "미금 tomorrow", "Paris weekend");
        for (String query : dated) {
            Geocoder geocoder = new Geocoder();
            geocoder.answer(query, null, place("성남시", "대한민국", 37.43861, 127.13778));
            WeatherFacade facade = facade(deadResolver(), geocoder);

            assertThat(facade.search(query).reason()).as(query)
                    .isEqualTo(WeatherFacade.Lookup.Reason.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("LLM이 죽어도 날짜 말이 없는 원문은 그대로 답한다 — 「오늘」·「날씨」는 해석 없이도 오늘이다")
    void stillAnswersAnUndatedQueryWhenTheLlmIsDead() {
        // 지명 안의 글자(「제주」·「광주」의 주, 「월곶」의 월)를 기간으로 읽지 않는다
        for (String query : List.of("파리", "파리 오늘", "파리 날씨", "제주", "광주", "전주", "월곶", "Paris")) {
            Geocoder geocoder = new Geocoder();
            geocoder.answer(query, null, place("파리", "프랑스", 48.8566, 2.3522));
            WeatherFacade facade = facade(deadResolver(), geocoder);

            assertThat(facade.search(query).reason()).as(query)
                    .isEqualTo(WeatherFacade.Lookup.Reason.FOUND);
        }
    }

    @Test
    @DisplayName("LLM이 준 지명을 못 찾으면 원문으로 한 번 더 묻는다 — 두 번 묻는 것이 계약이다")
    void triesTheRawQueryWhenTheResolvedNameMisses() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("Tokyo City", "JP", null);               // LLM이 준 것 — 없다
        geocoder.answer("Tokyo", null, place("도쿄", "일본", 35.6895, 139.6917));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "Tokyo City", "JP", null, null, null, null, null, null, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("Tokyo");

        assertThat(found.places().get(0).place().displayName()).isEqualTo("도쿄, 일본");
        assertThat(geocoder.asked).containsExactly("Tokyo City|JP", "Tokyo|null");
    }

    @Test
    @DisplayName("주말을 물으면 다가오는 토요일부터다 — 오늘·내일을 「주말」이라 적으면 틀린 값이다")
    void weekendStartsOnTheComingSaturday() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("서울특별시", "KR", place("서울특별시", "대한민국", 37.56667, 126.97806));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "서울특별시", "KR", null, null, null, null, null, true, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("주말 서울");

        // CLOCK은 2026-08-20(목) — 다가오는 주말은 22·23일이다
        assertThat(found.places().get(0).days().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    @DisplayName("날짜를 적었으면 주말 플래그보다 날짜다 — 적은 날을 버리고 다른 날을 답하지 않는다")
    void anExplicitDateBeatsTheWeekendFlag() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("서울특별시", "KR", place("서울특별시", "대한민국", 37.56667, 126.97806));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "서울특별시", "KR", null, 8, 29, null, null, true, null, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("8월 29일 주말 서울");

        assertThat(found.places().get(0).days().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("「일요일 미금」은 다가오는 일요일 하루다 — 오늘이나 토·일 이틀이 아니다")
    void aWeekdayMeansThatDay() {
        // 제보(2026-09-29): /weather 일요일 미금이 원하는 날을 안 보여 줬다. 요일 개념이 없어 LLM이
        // 주말(토·일)로 읽거나 아무 기간도 안 내 오늘이 나갔다
        Geocoder geocoder = new Geocoder();
        geocoder.answer("성남시", "KR", place("성남시", "대한민국", 37.43861, 127.13778));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "성남시", "KR", null, null, null, null, null, true, 7, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("일요일 미금");

        // CLOCK은 2026-08-20(목) — 다가오는 일요일은 23일이다. 요일이 주말 플래그보다 이긴다
        assertThat(found.places().get(0).days()).extracting(Daily::date).containsExactly(LocalDate.of(2026, 8, 23));
    }

    @Test
    @DisplayName("「다음 주 주말」을 weekOffset으로 줘도 다음 주말이다 — 이번 주말이 나가지 않는다")
    void readsNextWeekendFromWeekOffsetToo() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("서울특별시", "KR", place("서울특별시", "대한민국", 37.56667, 126.97806));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "서울특별시", "KR", null, null, null, null, null, true, null, 1)), geocoder);

        WeatherFacade.Lookup found = facade.search("다음 주 주말 서울");

        // CLOCK은 2026-08-20(목) — 이번 주말은 22·23일, 다음 주말은 29·30일이다
        assertThat(found.places().get(0).days().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("「다음 주 금요일」을 offsetDays 7로 줘도 다음 주 금요일이다 — 이번 주 금요일이 나가지 않는다")
    void readsNextWeeksWeekdayFromAWholeWeekOffset() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("성남시", "KR", place("성남시", "대한민국", 37.43861, 127.13778));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "성남시", "KR", null, null, null, 7, null, null, 5, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("다음주 금요일 성남");

        // CLOCK은 2026-08-20(목) — 이번 주 금요일은 21일, 다음 주 금요일은 28일이다
        assertThat(found.places().get(0).days()).extracting(Daily::date).containsExactly(LocalDate.of(2026, 8, 28));
    }

    @Test
    @DisplayName("LLM이 13월을 주면 「날짜를 못 읽었다」다 — 「잠시 후 다시」가 아니다")
    void treatsAnImpossibleMonthAsAnUnreadableDate() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("서울특별시", "KR", place("서울특별시", "대한민국", 37.56667, 126.97806));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "서울특별시", "KR", null, 13, 1, null, null, null, null, null)), geocoder);

        assertThat(facade.search("13월 1일 서울").reason()).isEqualTo(WeatherFacade.Lookup.Reason.UNREADABLE_DATE);
    }

    @Test
    @DisplayName("연도까지 적었는데 못 펴는 날짜(2025-02-30·2025-8-19)는 「날짜를 못 읽었다」다 — 오늘 날씨로 답하지 않는다")
    void treatsAnUnparsableFullDateAsAnUnreadableDate() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("서울특별시", "KR", place("서울특별시", "대한민국", 37.56667, 126.97806));

        for (String date : List.of("2025-02-30", "2025-8-19")) {
            WeatherFacade facade = facade(resolver(new ResolvedPlace(
                    "서울특별시", "KR", date, null, null, null, null, null, null, null)), geocoder);

            assertThat(facade.search(date + " 서울").reason()).as(date)
                    .isEqualTo(WeatherFacade.Lookup.Reason.UNREADABLE_DATE);
        }
    }

    @Test
    @DisplayName("「지난주 금요일」(weekOffset -1)은 지난주 금요일이다 — 다가오는 금요일이 나가지 않는다")
    void readsLastWeeksWeekdayFromANegativeWeekOffset() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("성남시", "KR", place("성남시", "대한민국", 37.43861, 127.13778));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "성남시", "KR", null, null, null, null, null, null, 5, -1)), geocoder);

        WeatherFacade.Lookup found = facade.search("지난주 금요일 성남");

        // CLOCK은 2026-08-20(목) — 이번 주 금요일은 21일, 지난주 금요일은 14일이다
        assertThat(found.places().get(0).days()).extracting(Daily::date).containsExactly(LocalDate.of(2026, 8, 14));
    }

    @Test
    @DisplayName("「지난주 금요일」을 offsetDays -7로 줘도 지난주 금요일이다 — 주말 갈래와 같은 규칙이다")
    void readsLastWeeksWeekdayFromANegativeWholeWeekOffset() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("성남시", "KR", place("성남시", "대한민국", 37.43861, 127.13778));
        WeatherFacade facade = facade(resolver(new ResolvedPlace(
                "성남시", "KR", null, null, null, -7, null, null, 5, null)), geocoder);

        WeatherFacade.Lookup found = facade.search("지난주 금요일 성남");

        assertThat(found.places().get(0).days()).extracting(Daily::date).containsExactly(LocalDate.of(2026, 8, 14));
    }

    @Test
    @DisplayName("자정 직전의 「오늘」은 끝까지 그 오늘이다 — 조회 중에 날이 바뀌어도 아카이브(지난 날)로 가지 않는다")
    void keepsOneTodayAcrossMidnight() {
        Geocoder geocoder = new Geocoder();
        geocoder.answer("서울특별시", "KR", place("서울특별시", "대한민국", 37.56667, 126.97806));
        // 처음 읽으면 KST 08-20 23:59:59.999, 그다음부터는 08-21 00:00
        Clock ticking = new TickingClock(Instant.parse("2026-08-20T14:59:59.999Z"),
                Instant.parse("2026-08-20T15:00:00Z"));
        WeatherFacade facade = new WeatherFacade(resolver(new ResolvedPlace(
                "서울특별시", "KR", null, null, null, null, null, null, null, null)), geocoder,
                new WeatherService(List.of(new UpcomingOnly()), ticking, TestWeather.noHourly()));

        WeatherFacade.Lookup found = facade.search("서울");

        assertThat(found.reason()).isEqualTo(WeatherFacade.Lookup.Reason.FOUND);
        assertThat(found.places().get(0).days()).extracting(Daily::date).containsExactly(LocalDate.of(2026, 8, 20));
    }

    // --- 이음새를 재현하는 데 필요한 만큼만의 가짜들 ---

    private static WeatherFacade facade(WeatherResolver resolver, Geocoder geocoder) {
        return new WeatherFacade(resolver, geocoder,
                new WeatherService(List.of(new Forecaster()), CLOCK, TestWeather.noHourly()));
    }

    /** 첫 읽기에는 {@code first}, 그다음부터는 {@code then}을 준다 — 조회 도중 자정이 지나는 모양이다. */
    private static final class TickingClock extends Clock {

        private final Instant first;
        private final Instant then;
        private boolean read;

        private TickingClock(Instant first, Instant then) {
            this.first = first;
            this.then = then;
        }

        @Override
        public Instant instant() {
            Instant now = read ? then : first;
            read = true;
            return now;
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /** 지난 날은 맡지 않는 예보 출처 — 기상청·AccuWeather·Open-Meteo 예보의 성질이다. */
    private static final class UpcomingOnly implements WeatherClient {

        @Override
        public WeatherSource source() {
            return WeatherSource.OPEN_METEO;
        }

        @Override
        public Weather forecast(GeoLocation place, WeatherPeriod period) {
            return new Forecaster().forecast(place, period);
        }

        @Override
        public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
            return !period.past(today);
        }
    }

    private static GeoLocation place(String name, String country, double lat, double lon) {
        return new GeoLocation(name, country, lat, lon, SEOUL);
    }

    /** LLM이 죽은 해석기 — 빈손이 아니라 예외로 알린다(LlmJson). */
    private static WeatherResolver deadResolver() {
        return new WeatherResolver(new GeminiStub(), new ObjectMapper()) {
            @Override
            public Optional<ResolvedPlace> resolve(String normalizedQuery) {
                throw new IllegalStateException("LLM 해석 실패: HTTP 503");
            }
        };
    }

    /** {@code null}을 주면 LLM이 읽지 못한 것이다 — 호출자가 원문 지오코딩으로 내려간다. */
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
            super(RestClient.builder(), TestProperties.offline());
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
        public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
            return true;
        }
    }

    private static final class GeminiStub extends io.saiden.economyhelper.infrastructure.llm.GeminiApi {

        private GeminiStub() {
            super(RestClient.builder(), TestProperties.offline());
        }
    }
}
