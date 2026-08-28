package io.saiden.economyhelper.market.weather;

import java.util.Objects;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 한 지점의 날씨 — <b>언제나 일일 단위다.</b>
 *
 * <p>⚠️ <b>다만 하루 <i>안</i>의 강수 시각은 담는다</b>({@code Daily.halves}).
 * 일일 단위라는 것은 <b>현재값과 일일값을 한 화면에 섞지 않는다</b>는 뜻이고(현재 기온은
 * 여전히 안 담는다), 「그날 언제 비가 오는가」는 그 하루에 속한 이야기다.
 *
 * <p><b>현재 기온을 담지 않는다.</b> 알람이든 검색이든 답은 그날 하루치다. 현재값과 일일값을
 * 섞으면 "지금 21°C인데 최고가 29°C"처럼 두 시간축이 한 화면에 서고, 오전 6시 알람에서 그
 * 시각 기온은 하루를 계획하는 데 쓸모도 없다.
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
        // ⚠️ 주석에만 있던 불변식을 생성자가 강제한다. from()/to()가 days.get(0)을 무방비로
        //    인덱싱하므로 빈 목록이 들어오면 <b>렌더 시점에</b> 터지고, 웹훅에서는 그게 침묵이
        //    된다 — 생산자에서 멀리 떨어진 자리에서 터지는 것이 이 계열의 가장 나쁜 점이다.
        //    지금은 생산자 둘이 각자 막고 있을 뿐이라 세 번째 출처가 붙는 날 새어 나온다.
        //    DigestSlot이 접두사를 생성자에서 요구하게 만든 것과 같은 판단이다
        if (days.isEmpty()) {
            throw new IllegalArgumentException(
                    "날씨에 하루도 담기지 않았습니다 — 값이 없으면 조회 자체가 실패여야 합니다");
        }
    }

    /** 목록의 첫날. 기준 줄에 쓴다. */
    public LocalDate from() {
        return days.get(0).date();
    }

    /** 목록의 마지막 날. {@link #from()}과 같으면 기준 줄에 날짜를 하나만 적는다. */
    public LocalDate to() {
        return days.get(days.size() - 1).date();
    }

    /**
     * 하루치.
     *
     * <p><b>강수는 두 칸이고 보통 한쪽만 찬다.</b> Open-Meteo 예보만 확률을 주고
     * (재분석은 지나간 날이라 확률이라는 개념이 없다) 나머지는 강수량이다.
     * 한 칸으로 합쳐 담으면 화면에서 <b>강수량을 확률이라 부르게 된다</b> — 값을 다른 것인 척
     * 하지 않는다는 규칙이 이 저장소 전체에 걸려 있어서, 애초에 섞이지 않게 칸을 나눠 둔다.
     *
     * @param sky        하늘 상태. 해석 못 한 값이면 {@link SkyCondition#UNKNOWN}이고 그 줄이 빠진다
     * @param low        최저 기온(°C)
     * @param high       최고 기온(°C)
     * <p><b>{@code halves}는 그 하루 <i>안</i>의 시각이다.</b> 일 단위 요약이 「비옴」까지만
     * 말하는 것을 메꾼다 — 실측(2026-08-20 성남시)으로 일 단위는 최대 강수확률 80%였는데
     * 시간별로는 13~19시에 몰려 있고 오전은 말라 있었다. <b>비어 있을 수 있다</b>(마른 날,
     * 또는 시간별 값을 못 받은 경우) — 그때는 화면에 그 줄이 없다.
     *
     * @param precipitationChance 강수확률(%). 예보가 아니거나 출처가 주지 않으면 {@code null}
     * @param precipitationAmount 강수량(mm). 확률을 아는 출처에서는 {@code null}
     * @param halves              그 하루의 반나절 둘 — 오전·오후 순. 시간별을 못 받았으면 빈 목록
     */
    public record Daily(LocalDate date, SkyCondition sky, BigDecimal low, BigDecimal high,
                        Integer precipitationChance, BigDecimal precipitationAmount,
                        List<HalfDay> halves) {

        public Daily {
            // null을 안쪽에서 흡수한다 — 호출자 스물 남짓이 전부 빈 목록을 손으로 넘기게 하면
            // 한 곳만 빠뜨려도 렌더에서 NPE가 난다(Weather가 days를 그렇게 막아 둔 것과 같다)
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
         * <p>⚠️ <b>확률을 함께 갈아야 화면이 거짓말을 안 한다.</b> 예전에는 토막만 얹고
         * {@code precipitationChance}는 일별 출처의 것을 그대로 뒀는데, 그러면 한 블록 안에
         * <b>서로 다른 예보의 두 숫자</b>가 선다 — AccuWeather가 낮 80%라고 하는 날 Open-Meteo
         * 시간별 봉우리가 40%면 「강수확률 80%」만 찍히고 <b>시각 줄은 통째로 없다</b>(문턱을
         * 넘는 시간이 하나도 없으므로). 실제로 그 모양이 골든에 정상인 것처럼 박혀 있었다.
         *
         * <p>토막의 봉우리가 곧 그날 시간별 봉우리다 — {@code HalfDays}의 문턱이
         * 봉우리 이하이므로 <b>봉우리 시각은 반드시 어느 토막엔가 들어 있다.</b> 그래서 이
         * 대입은 「확률이 50% 이상인 날에는 반드시 시각 줄이 있다」를 <b>산술적으로</b> 보장한다.
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
         * <p>⚠️ <b>「어긋난다」는 강수 여부가 갈리는 것이지 종류가 다른 것이 아니다.</b> 한동안
         * 반나절이 있는 <b>모든</b> 예보 날에 요약을 갈아 끼웠는데, 실측(네 역 × 닷새)으로 재 보니
         * <b>스무 날 중 16일이 바뀌고 그중 실제 모순은 4일뿐</b>이었다. 나머지 12일은 두 출처가
         * <b>둘 다 비라고 말하는데</b> 그냥 1순위를 버린 것이고, 그중 {@code 소나기 → 이슬비}는
         * <b>경고를 깎는다</b> — AccuWeather는 동 단위 지점 예보라 1순위인데 그 판단을 우리가
         * 낮춰 적을 근거가 없다. 확률은 이미 시간별로 갈리므로, 하늘까지 언제나 갈리면
         * 1순위가 <b>기온 공급원</b>으로만 남는다.
         *
         * <p>⚠️ <b>한동안 낮추는 쪽만 했다.</b> 「흐림에 한때 비는 모순이 아니라 『대체로 흐리고
         * 한때 비』로 읽힌다」는 이유였는데, 그 해석이 <b>모든 역방향에 통하지 않는다</b> —
         * 「맑음 + 종일 비」는 어떻게 읽어도 모순이다. 한쪽만 고치는 규칙은 예외를 정당화해야
         * 하고, 그 정당화가 한 입력에서 무너졌다. 그래서 양방향 한 규칙으로 바꿨다.
         * 대가는 1순위(AccuWeather)의 하늘이 반나절에 밀리는 것인데, <b>확률은 이미 그렇게
         * 하고 있었다</b>({@link #withHalves}) — 같은 블록의 강수 값이 한 예보에서 나온다.
         *
         * <p>⚠️ <b>지나간 날은 건드리지 않는다.</b> {@code peak}가 {@code null}인 날이 그것인데
         * (재분석은 확률을 안 준다) 거기 담긴 것은 예보가 아니라 <b>실측</b>이다. 손댔더니
         * 시간별이 전부 {@code 0.1mm} 미만인 날에 <b>「맑음 / 강수량 0.1mm」</b>가 나왔다 —
         * 비가 왔다고 적으면서 맑았다고 말하는 셈이고, 「지난 날은 실제로 온 양으로 적는다」를
         * 어긴다. <b>확률을 다시 세는 날에만 하늘도 함께 맞춘다.</b>
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
         * 마른 날에도 시간별이 말한 숫자가 나온다. 예전에는 여기서 {@code null}이 돌아와
         * 일별 출처의 확률이 남았고, 그것이 「61%인데 시각 줄이 없는」 화면이었다.
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
