package io.saiden.economyhelper.market.weather;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 한 지점의 날씨 — <b>언제나 일일 단위다.</b>
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
 */
public record Weather(GeoLocation place, List<Daily> days, WeatherSource source) {

    public Weather {
        days = List.copyOf(days);
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
     * @param rainChance 강수확률(%). 예보가 아니거나 출처가 주지 않으면 {@code null}
     * @param rainAmount 강수량(mm). 확률을 아는 출처에서는 {@code null}
     */
    public record Daily(LocalDate date, SkyCondition sky, BigDecimal low, BigDecimal high,
                        Integer rainChance, BigDecimal rainAmount) {

        /** 확률을 아는 출처(Open-Meteo 예보)가 쓰는 생성자. */
        public static Daily withChance(LocalDate date, SkyCondition sky,
                                       BigDecimal low, BigDecimal high, Integer rainChance) {
            return new Daily(date, sky, low, high, rainChance, null);
        }

        /** 강수량만 아는 출처(재분석, 그리고 확률이 빠진 예보 응답)가 쓰는 생성자. */
        public static Daily withAmount(LocalDate date, SkyCondition sky,
                                       BigDecimal low, BigDecimal high, BigDecimal rainAmount) {
            return new Daily(date, sky, low, high, null, rainAmount);
        }
    }
}
