package io.saiden.economyhelper.market.weather.kma;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.HalfDays;
import io.saiden.economyhelper.market.weather.SkyCondition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 기상청 단기예보의 {@code body} — <b>평평한 행 목록을 하루로 접는다.</b>
 *
 * <p>{@code openmeteo.DailyBlock}·{@code HourlyBlock}과 같은 자리다. <b>{@code @Component}는
 * HTTP를 하고 레코드가 접는다</b> — 그래서 {@code OpenMeteoForecastClient}의 {@code forecast}가
 * 한 문장이다. 여기 있던 파싱이 클라이언트 안에 있던 동안 {@code KmaVillageApi}는 352행이었고
 * 형제는 97행이었다.
 *
 * <p>응답이 <b>평평하다.</b> 한 행이 「어느 날 어느 시각의 어느 항목이 얼마」이고
 * (실측 907행 = 12항목 × 75시각 + 최저 3 + 최고 4) 축을 우리가 세운다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record VillageBlock(Items items) {

    private static final Logger log = LoggerFactory.getLogger(VillageBlock.class);

    static final DateTimeFormatter BASE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter BASE_TIME = DateTimeFormatter.ofPattern("HHmm");

    /** {@code "1.0mm 미만"}·{@code "30.0~50.0mm"}에서 첫 숫자를 꺼낸다. */
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** 일 최저·최고는 시각이 아니라 하루에 붙는다 — 축에서 빼서 따로 모은다. */
    private static final String DAILY_LOW = "TMN";
    private static final String DAILY_HIGH = "TMX";

    private List<Item> rows() {
        if (items == null || items.item() == null) {
            return List.of();
        }
        return items.item();
    }

    /**
     * 하루치 목록 — <b>오전·오후가 다 있는 날만.</b>
     *
     * @param today 오늘은 예외다. 기상청은 발표시각 이후만 주므로 지나간 반나절이 없다
     * @throws IllegalStateException 행이 하나도 없을 때
     */
    List<KmaVillageApi.VillageDay> toDays(LocalDate today, Extremes published) {
        List<Item> rows = rows();
        if (rows.isEmpty()) {
            throw new IllegalStateException("기상청 단기예보 응답에 시각이 없습니다");
        }
        Axis axis = axisOf(rows).with(published);
        Columns columns = axis.columns();
        Map<LocalDate, List<HalfDay>> halvesByDay = HalfDays.byDay(
                columns.times(), columns.chances(), columns.amounts(), columns.skies());

        List<KmaVillageApi.VillageDay> days = new ArrayList<>();
        halvesByDay.forEach((date, halves) -> {
            // ⚠️ 반나절 하나뿐인 날은 버린다 — 실측 +4일이 00시 한 칸으로 와서, 담으면
            //    「오후」가 사라진 하루가 화면에 선다. 오늘은 지나간 시간이 없어 예외다
            if (halves.size() < 2 && !date.equals(today)) {
                log.debug("[weather] 기상청 단기예보 {}는 반나절이 하나뿐이라 뺍니다", date);
                return;
            }
            BigDecimal low = axis.lowOf(date);
            BigDecimal high = axis.highOf(date);
            if (low == null || high == null) {
                // 기온 없는 날은 값이 아니다 — AccuWeatherClient가 세운 규칙과 같다
                log.warn("[weather] 기상청 단기예보 {}에 기온이 없어 뺍니다", date);
                return;
            }
            days.add(new KmaVillageApi.VillageDay(date,
                    HalfDays.commonestSky(axis.skiesOf(date)), low, high, halves));
        });
        return List.copyOf(days);
    }

    /** 평평한 행을 <b>시각 축</b>으로 세운다. 일 최저·최고만 축 밖으로 뺀다. */
    private static Axis axisOf(List<Item> rows) {
        Map<LocalDateTime, Map<String, String>> byMoment = new TreeMap<>();
        Map<LocalDate, BigDecimal> lows = new TreeMap<>();
        Map<LocalDate, BigDecimal> highs = new TreeMap<>();
        for (Item row : rows) {
            LocalDate date = row.date();
            if (date == null) {
                continue;
            }
            if (DAILY_LOW.equals(row.category())) {
                putNumber(lows, date, row.fcstValue());
                continue;
            }
            if (DAILY_HIGH.equals(row.category())) {
                putNumber(highs, date, row.fcstValue());
                continue;
            }
            LocalDateTime at = row.at();
            if (at != null) {
                byMoment.computeIfAbsent(at, moment -> new HashMap<>())
                        .put(row.category(), row.fcstValue());
            }
        }
        return new Axis(byMoment, lows, highs);
    }

    private static void putNumber(Map<LocalDate, BigDecimal> into, LocalDate date, String value) {
        BigDecimal number = KmaRequest.numberOf(value);
        if (number != null) {
            into.put(date, number);
        }
    }

    /**
     * {@code PCP}(1시간 강수량)는 <b>숫자가 아니라 문자열</b>이다. 실측(2026-08-26)에서
     * {@code '0'}·{@code '1'}·{@code '2'}·{@code '강수없음'}이 왔고, 문서에는
     * {@code "1.0mm 미만"}·{@code "30.0~50.0mm"}·{@code "50.0mm 이상"} 꼴도 있다.
     *
     * <p>⚠️ <b>{@code 강수없음}은 {@code null}이 아니라 {@code 0}이다.</b> 그것은 출처가
     * 「안 온다」고 <b>말한</b> 것이라 {@code HalfDays}의 거부권이 서야 한다. 거기서
     * 「{@code null}은 {@code 0}이 아니다」라고 못 박은 것은 그 반대 방향 — <b>안 온 값</b>을
     * 0으로 세지 말라는 뜻이다. 이 자리에서 {@code null}을 주면 실측 88~100%짜리 맑은 시간이
     * 다시 젖은 것으로 잡힌다.
     *
     * <p>⚠️ 범위 표기에서는 <b>첫 숫자</b>를 쓴다 — {@code "30.0~50.0mm"}는 30.0이다.
     * {@code "1.0mm 미만"}만 상한을 집게 되어 조금 부풀지만, 실측 응답이 맨숫자를 주므로
     * 이 길은 방어에 가깝다.
     *
     * @return 못 읽으면 {@code null} — 「모른다」다
     */
    private static BigDecimal amountOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.contains("강수없음") || text.contains("적설없음")) {
            return BigDecimal.ZERO;
        }
        Matcher first = FIRST_NUMBER.matcher(text);
        return first.find() ? new BigDecimal(first.group()) : null;
    }

    /**
     * 이 발표에 실린 <b>일 최저·최고</b>({@code TMN}·{@code TMX}).
     *
     * <p>따로 꺼내는 이유는 <b>오늘 것이 이른 발표에만 남기 때문</b>이다 — 실측(2026-08-26):
     * 02시 발표에는 오늘 {@code TMN=24.0}·{@code TMX=32.0}이 있고, 05·11시에는 {@code TMN}이,
     * 14시에는 <b>둘 다</b> 없다. 그 02시 발표는 <b>오후에도 그대로 조회된다</b>(14:29 확인).
     */
    Extremes extremes() {
        Map<LocalDate, BigDecimal> lows = new TreeMap<>();
        Map<LocalDate, BigDecimal> highs = new TreeMap<>();
        for (Item row : rows()) {
            LocalDate date = row.date();
            if (date == null) {
                continue;
            }
            if (DAILY_LOW.equals(row.category())) {
                putNumber(lows, date, row.fcstValue());
            } else if (DAILY_HIGH.equals(row.category())) {
                putNumber(highs, date, row.fcstValue());
            }
        }
        return new Extremes(lows, highs);
    }

    /** 날짜별 일 최저·최고. <b>둘 다 있어야</b> 그 날을 안다고 본다. */
    record Extremes(Map<LocalDate, BigDecimal> lows, Map<LocalDate, BigDecimal> highs) {

        static Extremes none() {
            return new Extremes(Map.of(), Map.of());
        }

        boolean has(LocalDate date) {
            return lows.get(date) != null && highs.get(date) != null;
        }

        /**
         * <b>이쪽이 이긴다</b> — 아는 날은 그대로 두고 <b>모르는 것만</b> {@code other}에서 채운다.
         *
         * <p>그래서 오늘의 최고는 최신 발표 것(더 신선하다)이고 최저만 02시 발표에서 온다.
         * <b>발표가 갈려도 둘 다 「일」 극값이라 시간축은 안 섞인다</b> — 섞이는 것은
         * 일 극값과 <b>남은 시간</b>의 극값을 한 줄에 둘 때다.
         */
        Extremes filledFrom(Extremes other) {
            Map<LocalDate, BigDecimal> mergedLows = new TreeMap<>(other.lows);
            Map<LocalDate, BigDecimal> mergedHighs = new TreeMap<>(other.highs);
            mergedLows.putAll(lows);
            mergedHighs.putAll(highs);
            return new Extremes(mergedLows, mergedHighs);
        }
    }

    /** 시각을 축으로 세운 것 + 하루에 붙는 값 둘. */
    private record Axis(Map<LocalDateTime, Map<String, String>> byMoment,
                        Map<LocalDate, BigDecimal> lows,
                        Map<LocalDate, BigDecimal> highs) {

        /** {@code HalfDays.byDay}가 요구하는 병렬 배열 넷으로 편다. */
        Columns columns() {
            List<LocalDateTime> times = new ArrayList<>(byMoment.size());
            List<Integer> chances = new ArrayList<>(byMoment.size());
            List<BigDecimal> amounts = new ArrayList<>(byMoment.size());
            List<SkyCondition> skies = new ArrayList<>(byMoment.size());
            byMoment.forEach((at, row) -> {
                times.add(at);
                chances.add(KmaRequest.integerOf(row.get("POP")));
                amounts.add(amountOf(row.get("PCP")));
                skies.add(KmaSky.of(row.get("SKY"), row.get("PTY")));
            });
            return new Columns(times, chances, amounts, skies);
        }

        List<SkyCondition> skiesOf(LocalDate date) {
            List<SkyCondition> skies = new ArrayList<>();
            byMoment.forEach((at, row) -> {
                if (at.toLocalDate().equals(date)) {
                    skies.add(KmaSky.of(row.get("SKY"), row.get("PTY")));
                }
            });
            return skies;
        }

        /** 바깥에서 받은 일 극값을 얹은 사본 — <b>그쪽이 이긴다.</b> */
        Axis with(Extremes published) {
            return new Axis(byMoment,
                    published.filledFrom(new Extremes(lows, highs)).lows(),
                    published.filledFrom(new Extremes(lows, highs)).highs());
        }

        /**
         * 최저·최고 — <b>둘을 같은 창에서 낸다.</b>
         *
         * <p>{@code KmaVillageApi}가 오늘 것을 <b>02시 발표에서 따로 받아</b> 얹으므로 평상시엔
         * 온종일 값이다({@link #with}). 그 보충이 실패한 날만 시간별로 낸다.
         *
         * <p>⚠️ <b>그때는 하나만 갈아 끼우지 않고 둘 다 시간별로 낸다.</b> 섞으면 앞뒤가 안
         * 맞는다 — 최고는 온종일의 {@code TMX}, 최저는 <b>남은 시간</b>의 최저가 되어 한 줄에
         * <b>두 시간축</b>이 선다(저녁에 물으면 「이미 지난 낮 최고 / 남은 밤 최저」다).
         * (Codex 적대적 리뷰가 짚었다.)
         */
        BigDecimal lowOf(LocalDate date) {
            return complete(date) ? lows.get(date) : hourlyBound(date, true);
        }

        BigDecimal highOf(LocalDate date) {
            return complete(date) ? highs.get(date) : hourlyBound(date, false);
        }

        private boolean complete(LocalDate date) {
            return lows.get(date) != null && highs.get(date) != null;
        }

        private BigDecimal hourlyBound(LocalDate date, boolean lowest) {
            BigDecimal bound = null;
            for (Map.Entry<LocalDateTime, Map<String, String>> entry : byMoment.entrySet()) {
                if (!entry.getKey().toLocalDate().equals(date)) {
                    continue;
                }
                BigDecimal temperature = KmaRequest.numberOf(entry.getValue().get("TMP"));
                if (temperature == null) {
                    continue;
                }
                bound = bound == null ? temperature
                        : (lowest ? bound.min(temperature) : bound.max(temperature));
            }
            return bound;
        }
    }

    /** {@code HalfDays.byDay}의 병렬 배열 계약. */
    private record Columns(List<LocalDateTime> times, List<Integer> chances,
                           List<BigDecimal> amounts, List<SkyCondition> skies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Items(List<Item> item) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String category, String fcstDate, String fcstTime, String fcstValue) {

        LocalDate date() {
            try {
                return fcstDate == null ? null : LocalDate.parse(fcstDate, BASE_DATE);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** @return 못 읽으면 {@code null} — 그 행만 빠진다 */
        LocalDateTime at() {
            LocalDate date = date();
            if (date == null || fcstTime == null || fcstTime.length() != 4) {
                return null;
            }
            try {
                return date.atTime(LocalTime.parse(fcstTime, BASE_TIME));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
