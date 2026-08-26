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
        // ⚠️ 오늘을 안 묻는 조회(`내일 서현`)에는 일 극값 보충을 부르지 않는다 —
        //    그 값은 오늘 줄에만 쓰이므로 헛호출이 된다
        VillageBlock.Extremes extremes = from.isAfter(today)
                ? latest.extremes()
                : extremesFor(grid, today, latest);
        return latest.toDays(today, extremes);
    }

    /**
     * 오늘의 <b>일 최저·최고</b> — 최신 발표에 없으면 <b>02시 발표</b>에서 받아 온다.
     *
     * <p>실측(2026-08-26, {@code nx=62 ny=123}): 오늘 것이 이른 발표에만 남는다.
     *
     * <pre>
     * 02시 발표   TMN 24.0   TMX 32.0
     * 05시 발표   없음        32.0
     * 11시 발표   없음        32.0
     * 14시 발표   없음        없음
     * </pre>
     *
     * <p>그 02시 발표는 <b>오후에도 그대로 조회된다</b>(14:29 확인). 그래서 근사하지 않고
     * 진짜 값을 가져온다 — 예전에는 최고는 온종일의 {@code TMX}, 최저는 <b>남은 시간</b>의
     * 최저가 되어 한 줄에 두 시간축이 섰다.
     *
     * <p>⚠️ <b>보충이라 실패를 삼킨다.</b> 실패하면 {@code VillageBlock}이 시간별로 낸 값이
     * 남는데(둘 다 시간별이라 앞뒤는 맞는다) 그건 「남은 하루」의 범위다 — 답이 죽는 것보다 낫다.
     *
     * <p><b>호출은 필요할 때만 늘어난다.</b> 최신 발표가 오늘 것을 이미 들고 있으면(02시 판 자체가
     * 최신인 새벽, 또는 전날 23시 판) 추가 호출이 <b>0</b>이다.
     */
    private VillageBlock.Extremes extremesFor(KmaGrid grid, LocalDate today, VillageBlock latest) {
        VillageBlock.Extremes known = latest.extremes();
        if (known.has(today)) {
            return known;
        }
        try {
            return known.filledFrom(
                    fetch(grid, today.atTime(BASE_HOURS[0], 0), "단기예보(일 최저·최고)")
                            .extremes());
        } catch (RuntimeException e) {
            log.warn("[weather] 기상청 {} 일 최저·최고를 못 받아 남은 시간으로 냅니다: {}",
                    today, FailureReason.of(e));
            return known;
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
