package io.saiden.economyhelper.weather.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 사용자가 물은 <b>날짜 범위</b>.
 *
 * <p>{@code 내일 서현 날씨}·{@code 일주일치}·{@code 2025년 8월 19일}이 전부 여기로 모인다.
 * LLM({@code WeatherResolver})은 <b>해석만</b> 하고, 실제 날짜 계산은 이 클래스가 한다.
 *
 * <p><b>왜 LLM에게 날짜를 계산시키지 않는가.</b> {@code 내일}을 {@code 2026-08-18}로 굳혀
 * 받으면 그 값이 {@code weather-resolve} 캐시에 7일간 남아 <b>내일이 영영 8월 18일이 된다.</b>
 * 상대 표현은 {@code offsetDays}로 받아 <b>부를 때마다 그날 기준으로</b> 편다. 절대 날짜는
 * 애초에 낡지 않으므로 그대로 쓴다.
 *
 * <p><b>기준이 되는 "오늘"은 그 지역의 오늘이다.</b> 부에노스아이레스를 KST 달력으로 자르면
 * 남의 하루가 둘로 쪼개진다. 그래서 호출자가
 * {@link GeoLocation#zone()}으로 계산한 오늘을 넘긴다.
 */
public record WeatherPeriod(LocalDate from, LocalDate to) {

    /**
     * 예보로 볼 수 있는 마지막 날 — <b>오늘 포함 16일</b>이다.
     *
     * <p>2026-08-17 실측에서 Open-Meteo가 {@code forecast_days=16}에 16건을 전부 채워 줬다
     * (강수확률까지 null이 없었다). 상한을 5일짜리 AccuWeather에 맞출 이유는 없다.
     */
    public static final int MAX_FORECAST_DAYS = 16;

    public WeatherPeriod {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("끝이 시작보다 앞섭니다: " + from + " ~ " + to);
        }
    }

    /**
     * 해석 결과를 실제 날짜로 편다.
     *
     * <p><b>꼬리는 자르되 머리는 자르지 않는다.</b> {@code 오늘부터 20일치}처럼 끝이 상한을
     * 넘으면 볼 수 있는 데까지만 보여 준다 — 화면에 날짜가 그대로 적히므로 어디까지인지
     * 사용자가 본다. 반대로 <b>시작</b>이 상한 너머면 보여 줄 것이 하나도 없으므로 자르지 않고
     * {@link #beyondForecast(LocalDate)}로 드러낸다. 그때 아무 날이나 채워 답하면 거짓말이 된다.
     *
     * @param today      그 지역의 오늘
     * @param date       절대 날짜. {@code null}이면 {@code offsetDays}를 쓴다
     * @param offsetDays 오늘로부터 며칠 뒤. {@code null}이면 0
     * @param days       며칠치. {@code null}이거나 1 미만이면 1 — <b>기본은 하루치다</b>
     */
    public static WeatherPeriod of(LocalDate today, LocalDate date,
                                   Integer offsetDays, Integer days) {
        LocalDate start = date != null ? date : today.plusDays(offsetDays == null ? 0 : offsetDays);
        int length = days == null || days < 1 ? 1 : days;

        LocalDate end = start.plusDays(length - 1L);
        LocalDate limit = today.plusDays(MAX_FORECAST_DAYS - 1L);
        // 시작이 이미 상한 너머면 자르지 않는다 — 자르면 to < from이 되어 생성자가 막고,
        // 무엇보다 "볼 수 없다"는 사실이 사라진다
        if (!start.isAfter(limit) && end.isAfter(limit)) {
            end = limit;
        }
        return new WeatherPeriod(start, end);
    }

    /**
     * 「주말」 — <b>다가오는</b> 토·일. 오늘이 토요일이면 오늘부터, 일요일이면 오늘 하루다
     * (지나간 토요일은 주말 예보가 아니다). 「다음 주말」은 {@code offsetDays=7}로 와서 일주일 뒤에서 센다 —
     * 그 날이 일요일이면 그 주말의 토요일부터다(일요일에 물은 「다음 주말」이 일요일 하루로 줄지 않게).
     * 꼬리가 예보 상한을 넘으면 {@link #of}처럼 볼 수 있는 데까지만 둔다.
     *
     * @param offsetDays 오늘로부터 며칠 뒤에서 세기 시작하나. {@code null}이면 0
     */
    public static WeatherPeriod weekend(LocalDate today, Integer offsetDays) {
        int offset = offsetDays == null ? 0 : offsetDays;
        LocalDate base = today.plusDays(offset);
        LocalDate saturday = base.getDayOfWeek() == DayOfWeek.SUNDAY
                ? (offset == 0 ? base : base.minusDays(1))
                : base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        LocalDate sunday = saturday.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate limit = today.plusDays(MAX_FORECAST_DAYS - 1L);
        if (!saturday.isAfter(limit) && sunday.isAfter(limit)) {
            sunday = limit;
        }
        return new WeatherPeriod(saturday, sunday);
    }

    /**
     * 요일 하루 — {@code 일요일}은 <b>다가오는</b> 일요일이다(오늘이 그 요일이면 오늘).
     * {@code weekOffset}이 있으면 그만큼 뒤 주(월~일)의 그 요일이다 — 화요일의 「다음 주 금요일」은
     * 이번 주 금요일이 아니라 다음 주 금요일이다.
     *
     * @param weekday    {@code 1}=월 … {@code 7}=일
     * @param weekOffset {@code null}·0이면 다가오는 그 요일
     */
    public static WeatherPeriod weekday(LocalDate today, int weekday, Integer weekOffset) {
        DayOfWeek target = DayOfWeek.of(weekday);
        LocalDate date = weekOffset == null || weekOffset <= 0
                ? today.with(TemporalAdjusters.nextOrSame(target))
                : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks(weekOffset).with(TemporalAdjusters.nextOrSame(target));
        return new WeatherPeriod(date, date);
    }

    /**
     * 연도 없이 적은 날({@code 8월 16일})을 <b>가장 가까운 그 날</b>로 편다.
     *
     * <p><b>적은 것은 쓰고, 안 적은 것만 채운다.</b> 연도를 적었으면 그대로, 월까지만 적었으면
     * 연도를 고르고, 일자만 적었으면 월과 연도를 함께 고른다.
     *
     * <p><b>왜 LLM에게 맡기지 않는가.</b> 맡겼더니 지어냈다 — {@code 8월 16일 날씨}를
     * 물었는데 2024년 8월 16일이 나왔다. 모델은 오늘이 몇 년 몇 월인지 모르므로 물어보면 안
     * 되는 것이었고, 상대 표현을 {@code offsetDays}로 받는 것과 같은 이유다.
     *
     * <p><b>작년·올해·내년 중 오늘에서 가장 가까운 것을 고른다.</b> 8월 17일에 {@code 8월 16일}을
     * 물으면 어제(하루 전)이지 내년(364일 뒤)이 아니다. 반대로 8월에 {@code 12월 25일}을 물으면
     * 올해 크리스마스(130일 뒤)이지 작년(235일 전)이 아니다 — 그건 예보 범위를 넘으므로
     * "16일까지만 볼 수 있다"고 답하게 되는데, 그게 작년 값을 슬쩍 내미는 것보다 옳다.
     *
     * @return 못 읽었으면 {@code null}
     */
    public static LocalDate nearestOccurrence(LocalDate today, Integer month, Integer day) {
        if (day == null) {
            return null;
        }
        LocalDate nearest = null;
        long best = Long.MAX_VALUE;
        for (YearMonth candidateMonth : candidateMonths(today, month)) {
            // 4월 31일·평년 2월 29일처럼 그 달에 없는 날은 건너뛴다.
            // 여기서 던지면 날짜 하나 때문에 검색 전체가 죽는다
            if (!candidateMonth.isValidDay(day)) {
                continue;
            }
            LocalDate candidate = candidateMonth.atDay(day);
            long distance = Math.abs(ChronoUnit.DAYS.between(today, candidate));
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /**
     * 어느 달들을 후보로 볼 것인가 — <b>적지 않은 것만 채운다.</b>
     *
     * <ul>
     *   <li>월을 적었으면: 그 달의 <b>작년·올해·내년</b> — 연도만 고르면 된다
     *   <li>월도 안 적었으면: <b>지난달·이번달·다음달</b> — 월과 연도를 함께 고른다
     * </ul>
     *
     * <p>{@link YearMonth}로 세는 덕분에 <b>연 경계가 저절로 처리된다.</b> 1월 2일에
     * {@code 31일}을 물으면 후보가 작년 12월·올해 1월·올해 2월이 되고, 이틀 전인
     * 작년 12월 31일이 29일 뒤인 1월 31일보다 가깝다.
     */
    private static List<YearMonth> candidateMonths(LocalDate today, Integer month) {
        if (month != null) {
            // ⚠️ LLM이 0월·13월을 줄 수 있다 — YearMonth.of가 던지면 「잠시 후 다시」가 나간다.
            //    후보가 없으면 날짜를 못 편 것이고, 호출자가 「날짜를 못 읽었다」로 답한다
            if (month < 1 || month > 12) {
                return List.of();
            }
            return List.of(YearMonth.of(today.getYear() - 1, month),
                    YearMonth.of(today.getYear(), month),
                    YearMonth.of(today.getYear() + 1, month));
        }
        YearMonth thisMonth = YearMonth.from(today);
        return List.of(thisMonth.minusMonths(1), thisMonth, thisMonth.plusMonths(1));
    }

    /**
     * 시작일이 예보 범위를 벗어났는가 — 그렇다면 보여 줄 것이 하나도 없다.
     *
     * <p>호출자는 빈손으로 두지 않고 <b>며칠까지 되는지를 문구에 실어</b> 답한다.
     * {@code noResults}가 뉴스 창 길이를 문구에 싣는 것과 같은 이유다.
     */
    public boolean beyondForecast(LocalDate today) {
        return from.isAfter(today.plusDays(MAX_FORECAST_DAYS - 1L));
    }

    /**
     * 지나간 날인가 — <b>재분석(Archive)으로 가야 하는지</b>를 가른다.
     *
     * <p>마지막 날까지 전부 어제 이전일 때만 참이다. 오늘을 걸치면 예보 쪽이 그 범위를
     * {@code past_days}로 함께 준다 — 두 API를 섞어 이어 붙이면 한 화면에 격자가 다른 값이
     * 나란히 서게 되므로 그렇게 하지 않는다.
     */
    public boolean past(LocalDate today) {
        return to.isBefore(today);
    }
}
