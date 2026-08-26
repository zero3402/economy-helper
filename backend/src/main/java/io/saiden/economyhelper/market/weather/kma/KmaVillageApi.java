package io.saiden.economyhelper.market.weather.kma;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.support.FailureReason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 기상청 <b>단기예보</b>({@code getVilageFcst}) — 오늘부터 사흘 뒤까지, 시간 단위.
 *
 * <p><b>이 클래스는 HTTP와 발표시각만 안다.</b> 평평한 행을 하루로 접는 일은
 * {@link VillageBlock}이 한다 — {@code OpenMeteoForecastClient}가 {@code DailyBlock}·
 * {@code HourlyBlock}에 파싱을 맡기고 97행에 머무는 것과 같은 자리다.
 *
 * <p><b>실측(2026-08-26 05시 발표, {@code nx=62 ny=123})으로 본 날짜별 시각 수</b> —
 * 이것이 {@link KmaWeatherClient}가 맡는 범위를 정한 근거다.
 *
 * <pre>
 * +0  06~23시 매시 18칸   ← 발표시각 이후만. 지나간 시간은 안 준다
 * +1  00~23시 매시 24칸
 * +2  00~23시 매시 24칸
 * +3  00·03·06…21시 8칸   ← 3시간 간격이지만 오전·오후가 다 있다
 * +4  00시 한 칸           ← 오후가 없다. 담으면 「오후」 줄이 사라진 하루가 된다
 * </pre>
 *
 * <p>⚠️ <b>오늘은 지나간 시간이 없다.</b> Open-Meteo는 {@code start_date=오늘}에 자정부터 다
 * 주는데 기상청은 발표시각 이후만 준다. 그래서 오후에 물으면 <b>오늘은 「오후」 줄만</b>
 * 나온다 — 「반나절마다 반드시 한 줄」이 오늘에만 느슨해지는 셈인데, 이미 지나간 반나절을
 * 적을 근거가 없으므로 지어내지 않는다. 오전 6시 알람은 06~11시가 있어 영향이 없다.
 */
@Component
class KmaVillageApi {

    private static final Logger log = LoggerFactory.getLogger(KmaVillageApi.class);

    private static final String PATH = "/1360000/VilageFcstInfoService_2.0/getVilageFcst";

    /** 실측 907행. 넉넉히 잡아 한 번에 받는다 — 페이지를 넘기면 호출이 배로 든다. */
    private static final int PAGE_SIZE = 1000;

    /** 발표시각(1일 8회). 실측 문서값이다. */
    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};

    /**
     * 발표 후 자료가 실리기까지의 여유. 기상청 안내가 「발표시각 + 10분」이라
     * 그보다 이르게 물으면 한 판 전 것도 못 받는다.
     */
    private static final int PUBLISH_DELAY_MINUTES = 10;

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final Clock clock;

    KmaVillageApi(RestClient.Builder builder,
                  @Value("${economy-helper.weather.kma.base-url}") String baseUrl,
                  @Value("${economy-helper.weather.kma.api-key:}") String apiKey,
                  Clock clock) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.clock = clock;
    }

    /**
     * 격자 하나의 단기예보 — <b>오전·오후가 다 있는 날만</b> 담는다(오늘은 예외).
     *
     * @param from 요청 범위의 시작일. <b>오늘을 안 묻는 조회에는 일 극값 보충을 안 부른다</b>
     * @throws IllegalStateException 조회 실패나 정상이 아닌 응답
     */
    List<VillageDay> days(KmaGrid grid, LocalDate from) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), KmaRequest.SEOUL);
        LocalDate today = now.toLocalDate();
        VillageBlock latest = fetch(grid, latestPublication(now), "단기예보");

        // ⚠️ 오늘을 안 묻는 조회(`내일 서현`)에는 보충을 부르지 않는다 — 이른 발표가 메우는
        //    것은 오늘의 오전과 오늘의 일 극값뿐이라 그 조회에는 헛호출이 된다
        VillageBlock earlier = from.isAfter(today) ? null : earlierFor(grid, today, latest);
        VillageBlock.Extremes extremes = latest.extremes();
        if (earlier != null) {
            extremes = extremes.filledFrom(earlier.extremes());
        }
        return latest.toDays(today, extremes, earlier);
    }

    /**
     * <b>이른 발표</b>(02시) — 최신 발표가 오늘에 대해 못 주는 것을 메운다.
     *
     * <p>메우는 것이 둘이다. <b>오전 시간별</b>과 <b>일 최저·최고</b>이고, 둘이 같은 시각대에
     * 함께 사라진다. 기상청은 행을 <b>발표시각 + 1시간부터만</b> 주고 일 극값도 이른 판에만
     * 남긴다 — 실측(2026-08-26, {@code nx=62 ny=123}):
     *
     * <pre>
     *          오늘 시각        오전 슬롯   TMN    TMX
     * 02시 발표  03~23시 21칸     9칸        24.0   32.0
     * 05시 발표  06~23시 18칸     6칸        없음   32.0
     * 11시 발표  12~23시 12칸     0칸        없음   32.0
     * 14시 발표  15~23시  9칸     0칸        없음   없음
     * </pre>
     *
     * <p>그 02시 판은 <b>오후에도 그대로 조회된다</b>(14:29·15:27 확인, 944행).
     *
     * <p>⚠️ <b>트리거를 일 극값에서 떼어냈다.</b> 예전에는 {@code !extremes.has(today)}일 때만
     * 받았는데, 그것이 11시·14시 창을 덮는 것은 <b>우연</b>이었다 — 기상청이 14시 판에
     * {@code TMN}을 싣기 시작하면 보충이 멈추고 <b>오전이 조용히 다시 사라진다.</b>
     * 지금은 「반나절 둘이 다 있나」와 「일 극값이 있나」를 함께 묻고 한 호출로 둘을 메운다.
     *
     * <p>⚠️ <b>보충이라 실패를 삼킨다.</b> 실패하면 오늘이 반나절 하나로 남고
     * {@code VillageBlock.toDays}가 그 날을 버려 <b>폴백이 일어난다</b> — AccuWeather와
     * Open-Meteo 시간별이 두 줄을 온전히 주므로 반쪽을 내보내는 것보다 낫다.
     *
     * <p><b>호출은 필요할 때만 늘어난다.</b> 최신 발표가 오늘을 온전히 들고 있으면(어제 23시·
     * 02·05·08시 판) 추가 호출이 <b>0</b>이다.
     *
     * @return 이른 발표. 받을 필요가 없거나 못 받았으면 {@code null}
     */
    private VillageBlock earlierFor(KmaGrid grid, LocalDate today, VillageBlock latest) {
        if (latest.hasBothHalves(today) && latest.extremes().has(today)) {
            return null;
        }
        try {
            return fetch(grid, today.atTime(BASE_HOURS[0], 0), "단기예보(이른 발표)");
        } catch (RuntimeException e) {
            log.warn("[weather] 기상청 {} 이른 발표를 못 받았습니다 — 오늘이 빠져 폴백합니다: {}",
                    today, FailureReason.of(e));
            return null;
        }
    }

    private VillageBlock fetch(KmaGrid grid, LocalDateTime published, String what) {
        Response response = KmaRequest.fetch(restClient,
                KmaRequest.uri(baseUrl, PATH, apiKey, Map.of(
                        "numOfRows", String.valueOf(PAGE_SIZE),
                        "pageNo", "1",
                        "base_date", published.format(VillageBlock.BASE_DATE),
                        "base_time", published.format(VillageBlock.BASE_TIME),
                        "nx", String.valueOf(grid.nx()),
                        "ny", String.valueOf(grid.ny()))),
                Response.class, what);
        return KmaRequest.opened(response == null ? null : response.response(), what).body();
    }

    /**
     * <b>가장 최근 발표.</b> 앞선 판을 고르면 지금 시각이 이미 지난 예보를 받고, 뒷선 판을
     * 고르면 아직 안 실린 것을 받아 빈손이 된다.
     */
    private static LocalDateTime latestPublication(LocalDateTime now) {
        LocalDateTime usable = now.minusMinutes(PUBLISH_DELAY_MINUTES);
        for (int i = BASE_HOURS.length - 1; i >= 0; i--) {
            if (usable.getHour() >= BASE_HOURS[i]) {
                return usable.toLocalDate().atTime(BASE_HOURS[i], 0);
            }
        }
        // 02시 발표 전이다 — 어제 23시 판이 가장 최근이다
        return usable.toLocalDate().minusDays(1).atTime(BASE_HOURS[BASE_HOURS.length - 1], 0);
    }

    /** 하루치 — {@code Weather.Daily}로 옮기기 직전의 모양. */
    record VillageDay(LocalDate date, SkyCondition sky, BigDecimal low, BigDecimal high,
                      List<HalfDay> halves) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(Envelope response) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(KmaRequest.Header header, VillageBlock body)
            implements KmaRequest.Envelope {
    }
}
