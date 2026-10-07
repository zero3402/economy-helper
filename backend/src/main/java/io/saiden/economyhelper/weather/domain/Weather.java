package io.saiden.economyhelper.weather.domain;

import io.saiden.economyhelper.weather.application.port.out.WeatherClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 한 지점의 날씨 — <b>언제나 일일 단위다.</b>
 *
 * <p><b>현재 기온을 담지 않는다.</b> 현재값과 일일값을 섞으면 「지금 21°C인데 최고가 29°C」처럼
 * 두 시간축이 한 화면에 선다. 하루 <i>안</i>의 강수 시각({@code Daily.halves})은 그 하루에 속한
 * 이야기라 담는다.
 *
 * <p>알람은 지역 넷에 하루씩, 검색은 지역 하나에 여러 날이다. 그래서 <b>지역 하나가 이 레코드
 * 하나</b>이고, 날짜는 그 안의 목록이다.
 *
 * @param place  조회한 지점. 이름·나라가 화면 제목이 된다
 * @param days   날짜순 하루치 목록. 비어 있을 수 없다 — 값이 없으면 조회 자체가 실패다
 * @param source 어디서 가져왔는지. 화면 맨 아래에 이름이 그대로 적힌다
 * @param precipitationSource <b>강수 줄만 다른 곳에서 왔을 때</b> 그곳. 같은 곳이거나 보충이
 *                            없었으면 {@code null}이고 화면은 {@code source} 하나만 적는다.
 *                            {@code WeatherService}가 시간별을 보충으로 받은 날에만 채운다 —
 *                            <b>출처를 숨기지 않는다</b>는 규칙이 그 자리에도 걸린다
 */
public record Weather(GeoLocation place, List<Daily> days, WeatherSource source,
                      WeatherSource precipitationSource) {

    /**
     * 보충이 없는 평상시 — <b>출처가 하나뿐인 조회</b>가 이것으로 만든다.
     *
     * <p>{@link WeatherClient} 구현들이 전부 이쪽이다. 강수 줄까지 제 응답에서 나오므로
     * 밝힐 두 번째 출처가 없다.
     */
    public Weather(GeoLocation place, List<Daily> days, WeatherSource source) {
        this(place, days, source, null);
    }

    public Weather {
        days = List.copyOf(days);
        // ⚠️ from()/to()가 days.getFirst()를 부르므로 빈 목록은 렌더 시점에 NoSuchElementException으로
        //    터지고, 웹훅에서는 그게 침묵이 된다 — 생산자에서 멀리 떨어진 자리에서 터지지 않게
        //    여기서 막는다
        if (days.isEmpty()) {
            throw new IllegalArgumentException(
                    "날씨에 하루도 담기지 않았습니다 — 값이 없으면 조회 자체가 실패여야 합니다");
        }
    }

    /** 목록의 첫날. 기준 줄에 쓴다. */
    public LocalDate from() {
        return days.getFirst().date();
    }

    /** 목록의 마지막 날. {@link #from()}과 같으면 기준 줄에 날짜를 하나만 적는다. */
    public LocalDate to() {
        return days.getLast().date();
    }

    /**
     * 하루치.
     *
     * <p><b>강수는 두 칸이고 보통 한쪽만 찬다.</b> Open-Meteo 예보만 확률을 주고
     * (재분석은 지나간 날이라 확률이라는 개념이 없다) 나머지는 강수량이다.
     * 한 칸으로 합쳐 담으면 화면에서 <b>강수량을 확률이라 부르게 된다.</b>
     *
     * <p><b>{@code halves}는 그 하루 <i>안</i>의 시각이다</b>(이유는 ADR-0011).
     * <b>비어 있을 수 있다</b> — 시간별 값을 못 받으면 화면에 그 줄이 없다.
     *
     * @param sky        하늘 상태. 해석 못 한 값이면 {@link SkyCondition#UNKNOWN}이고 그 줄이 빠진다
     * @param low        최저 기온(°C)
     * @param high       최고 기온(°C)
     * @param precipitationChance 강수확률(%). 예보가 아니거나 출처가 주지 않으면 {@code null}
     * @param precipitationAmount 강수량(mm). 확률을 아는 출처에서는 {@code null}
     * @param halves              그 하루의 반나절 둘 — 오전·오후 순. 시간별을 못 받았으면 빈 목록
     */
    public record Daily(LocalDate date, SkyCondition sky, BigDecimal low, BigDecimal high,
                        Integer precipitationChance, BigDecimal precipitationAmount,
                        List<HalfDay> halves) {

        public Daily {
            // null을 안쪽에서 흡수한다 — 호출자 한 곳만 빠뜨려도 렌더에서 NPE가 난다
            halves = halves == null ? List.of() : List.copyOf(halves);
        }

        /** 확률을 아는 출처(Open-Meteo 예보)가 쓰는 생성자. */
        public static Daily withChance(LocalDate date, SkyCondition sky,
                                       BigDecimal low, BigDecimal high, Integer precipitationChance) {
            return new Daily(date, sky, low, high, precipitationChance, null, List.of());
        }

        /** 강수량만 아는 출처(재분석, 그리고 확률이 빠진 예보 응답)가 쓰는 생성자. */
        public static Daily withAmount(LocalDate date, SkyCondition sky,
                                       BigDecimal low, BigDecimal high, BigDecimal precipitationAmount) {
            return new Daily(date, sky, low, high, null, precipitationAmount, List.of());
        }

        /**
         * 같은 하루에 강수 시각을 얹은 사본 — <b>확률도 그 시간별로 다시 센다.</b>
         *
         * <p>시간별 값은 일별과 <b>다른 호출</b>에서 올 수 있다(1순위 AccuWeather는 낮/밤뿐이라
         * 시간 단위를 Open-Meteo에 따로 묻는다). 그래서 일별을 만든 뒤에 얹는 자리가 필요하다.
         *
         * <p>⚠️ <b>확률을 함께 갈아야 화면이 거짓말을 안 한다.</b> 일별 출처의 확률을 두면 한 블록
         * 안에 <b>서로 다른 예보의 두 숫자</b>가 선다 — AccuWeather 낮 80%에 Open-Meteo 봉우리
         * 40%면 「강수확률 80%」만 찍히고 시각 줄은 통째로 없다. 보장되는 것은 <b>확률과 시각이
         * 같은 시간별에서 나온다</b>는 것뿐이다 — 봉우리 시각이 코드·양 규칙에 걸려 빠지면
         * 확률은 높은데 시각 줄이 없을 수 있다(실측 2026-08-25 미금역: 확률 100%인 10~12시가 WMO
         * 코드 {@code 1}·{@code 0.0mm}라 비로 안 쳐 오전 시각 줄이 없다).
         *
         * <p>Open-Meteo가 일별까지 맡은 날은 {@code precipitation_probability_max}가 이미 그
         * 봉우리라 값이 안 바뀐다 — 규칙이 한 곳에 있으니 두 경로가 갈릴 수 없다.
         *
         * <p><b>강수량은 건드리지 않는다.</b> 지나간 날의 토막은 확률이 없어({@code chance}가
         * {@code null}) 봉우리도 없고, 그때는 원래 값이 그대로 남는다 — 「확률과 강수량은
         * 두 칸이고 보통 한쪽만 찬다」는 이 레코드의 규칙 그대로다.
         */
        public Daily withHalves(List<HalfDay> halves) {
            Integer peak = peakChanceOf(halves);
            return new Daily(date, skyAgreeingWith(halves, peak), low, high,
                    peak != null ? peak : precipitationChance, precipitationAmount, halves);
        }

        /**
         * 하루 요약의 하늘 — <b>반나절이 말한 것과 같은 무게여야 한다.</b>
         *
         * <p>요약과 반나절이 서로 다른 예보에서 오면 한 블록이 제 말을 뒤집는다. 실측으로 양쪽을
         * 다 봤다: <b>「소나기 / 강수확률 61% / ☁️ 오전 흐림 / ⛅ 오후 구름 조금」</b>
         * (2026-08-26 미금역 — 요약은 AccuWeather 낮 칸, 반나절은 Open-Meteo 봉우리 18%)와
         * 그 반대인 <b>「맑음 / 강수확률 100% / ☔ 오전 종일 비 / ☔ 오후 종일 비」</b>다.
         * 그래서 <b>요약과 반나절이 어긋나면 요약을 반나절 쪽으로 맞춘다</b> — 낮추기도 하고
         * 올리기도 한다.
         *
         * <p>⚠️ <b>「어긋난다」는 강수 여부가 갈리는 것이지 종류가 다른 것이 아니다.</b> 모든 날에
         * 갈아 끼우면 실측(네 역 × 닷새) <b>스무 날 중 16일이 바뀌고 실제 모순은 4일뿐</b>이다 —
         * 나머지는 둘 다 비라고 말하는데 1순위를 버리는 것이고, {@code 소나기 → 이슬비}는 경고를
         * 깎는다. 하늘까지 언제나 갈리면 1순위가 기온 공급원으로만 남는다. 낮추기만 하는 규칙도
         * 「맑음 + 종일 비」를 못 고쳐 양방향 한 규칙으로 둔다.
         *
         * <p>⚠️ <b>지나간 날은 건드리지 않는다</b>({@code peak}가 {@code null}). 거기 담긴 것은
         * <b>실측</b>이다 — 손대면 시간별이 전부 {@code 0.1mm} 미만인 날에 「맑음 / 강수량 0.1mm」가
         * 나온다. <b>확률을 다시 세는 날에만 하늘도 함께 맞춘다.</b>
         *
         * <p><b>모르는 것으로 아는 것을 덮지 않는다.</b> {@link SkyCondition#UNKNOWN}은 후보에서
         * 빠지고, 반나절이 전부 모르면 요약을 그대로 둔다.
         */
        private SkyCondition skyAgreeingWith(List<HalfDay> halves, Integer peak) {
            if (halves == null || halves.isEmpty() || peak == null) {
                return sky;
            }
            // ⚠️ **어긋날 때만 손댄다.** 「한쪽은 비라 하고 다른 쪽은 아니라 한다」가 어긋남이고,
            //    둘 다 비라거나 둘 다 아니면 1순위(AccuWeather)의 말을 그대로 둔다.
            //    판정을 wet()이 아니라 kind()로 하는 이유: 마른 반나절도 강수 어휘를 가질 수
            //    있고(HalfDays.skyOf가 「가장 흔한 코드」다) 그때 화면은 「☔ 오전 이슬비」라고
            //    **말하고 있다** — 요약이 「소나기」인 것과 어긋나지 않는다
            boolean partsSayRain = halves.stream().map(HalfDay::kind)
                    .anyMatch(SkyCondition::precipitating);
            if (sky.precipitating() == partsSayRain) {
                return sky;
            }
            return halves.stream().map(HalfDay::kind)
                    .filter(SkyCondition::known)
                    .max(SkyCondition::compareTo)
                    .orElse(sky);
        }

        /**
         * 반나절들의 최대 확률. 확률을 아는 반나절이 하나도 없으면 {@code null}.
         *
         * <p><b>마른 반나절도 제 봉우리를 든다</b>({@code HalfDay.dry}) — 그래서 양쪽이 다
         * 마른 날에도 시간별이 말한 숫자가 나온다(안 그러면 일별 출처의 61%가 남는다 —
         * 실측 2026-08-26 미금역).
         */
        private static Integer peakChanceOf(List<HalfDay> halves) {
            if (halves == null) {
                return null;
            }
            return halves.stream().map(HalfDay::chance)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo).orElse(null);
        }
    }
}
