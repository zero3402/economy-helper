package io.saiden.economyhelper.weather.adapter.out.kma;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Kma;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.weather.domain.HalfDay;
import io.saiden.economyhelper.weather.domain.SkyCondition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 기상청 <b>단기예보</b>({@code getVilageFcst}) — 오늘부터 사흘 뒤까지, 시간 단위.
 *
 * <p><b>이 클래스는 HTTP와 발표시각만 안다.</b> 평평한 행을 하루로 접는 일은
 * {@link VillageBlock}이 한다.
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
 * <p>⚠️ <b>오늘은 지나간 시간이 없다</b> — 기상청은 발표시각 이후만 준다. 그래서 오늘의 오전은
 * 이른 발표에서 메운다({@link #earlierFor}). 오전 8시 알람은 첫 틱(08:00)에 05시 판이 06~11시를 준다(08시 판은 08:10 뒤에 나온다).
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

    KmaVillageApi(RestClient.Builder builder, EconomyHelperProperties properties, Clock clock) {
        Kma kma = properties.weather().kma();
        this.restClient = builder.build();
        this.baseUrl = kma.baseUrl();
        this.apiKey = kma.apiKey();
        this.clock = clock;
    }

    /**
     * 격자 하나의 단기예보 — <b>오전·오후가 다 있는 날만</b> 담는다(오늘도 예외가 아니다).
     *
     * <p><b>최신 발표에 자료가 없으면 한 판 앞을 한 번만 더 묻는다.</b> 앞 판은 시각의 상위집합이고
     * (발표시각 + 1시간부터 주므로 이른 판이 더 많이 든다) 같은 시각은 최신이 이기게 합치므로
     * ({@link VillageBlock.Axis#under}) 틀린 값이 섞이지 않는다 — 기상청이 그 격자에 실제로 낸
     * 가장 최근 예보다. 실측(2026-10-14 10:23)에서 08시 발표가 네 격자 중 셋에 {@code NO_DATA}를
     * 줬고, 그날 08:00 알람은 한 곳만 나갔다. 두 판 연속 없으면 던진다 — 폴백은 {@code WeatherService}가.
     * 자정을 넘으면 어제 23시 판이고, 그때 넷째 날은 00시 한 칸뿐이라 {@code requireEveryDay}가
     * 던져 폴백한다(ADR-0010 7번 그대로). 알람은 하루라 무관하다.
     *
     * @param from 요청 범위의 시작일. <b>오늘을 안 묻는 조회에는 일 극값 보충을 안 부른다</b>
     * @throws IllegalStateException 조회 실패나 정상이 아닌 응답
     */
    List<VillageDay> days(KmaGrid grid, LocalDate from) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), KmaRequest.SEOUL);
        LocalDate today = now.toLocalDate();
        // 발표마다 축을 한 번만 세운다 — 이른 발표가 필요한지 묻는 것과 접는 것이 같이 쓴다
        LocalDateTime published = latestPublication(now);
        VillageBlock.Axis latest;
        try {
            latest = fetch(grid, published, "단기예보").axis();
        } catch (KmaRequest.NoData e) {
            LocalDateTime previous = previousPublication(published);
            log.warn("[weather] 기상청 {},{} {} 발표에 자료가 없어 한 판 앞 {} 발표를 묻습니다",
                    grid.nx(), grid.ny(), published, previous);
            // 여기서도 NoData면 그대로 새어 나간다 — 두 판 연속이면 이 출처의 실패다
            latest = fetch(grid, previous, "단기예보(한 판 앞)").axis();
        }

        // ⚠️ 오늘을 안 묻는 조회(`내일 서현`)에는 보충을 부르지 않는다 — 이른 발표가 메우는
        //    것은 오늘의 오전과 오늘의 일 극값뿐이라 그 조회에는 헛호출이 된다
        VillageBlock.Axis earlier = from.isAfter(today) ? null : earlierFor(grid, today, latest);
        return latest.under(earlier).toDays();
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
     * <p>⚠️ <b>「반나절 둘이 다 있나」와 「일 극값이 있나」를 함께 묻는다</b> — 극값만 보면 그것이
     * 11시·14시 창을 덮는 것이 우연이다({@code VillageBlock.Axis.hasBothHalves}).
     *
     * <p>⚠️ <b>보충이라 실패를 삼킨다.</b> 그 뒤는 최신 발표가 정한다.
     * <b>05·08시 판</b>은 오전 슬롯이 남아 있어 오늘이 그대로 나가고, 일 최저·최고만 남은 시각의
     * 값으로 낸다(오전 8시 알람이 이 경우다). <b>11시 판부터</b>는 오늘이 반나절 하나로 남아
     * {@code VillageBlock.Axis.toDays}가 그 날을 버려 <b>폴백이 일어난다</b> — AccuWeather와
     * Open-Meteo 시간별이 두 줄을 온전히 주므로 반쪽을 내보내는 것보다 낫다.
     *
     * <p><b>호출은 필요할 때만 늘어난다.</b> 최신 발표가 오늘을 온전히 들고 있으면(어제 23시·
     * 02·05·08시 판) 추가 호출이 <b>0</b>이다.
     *
     * @return 이른 발표. 받을 필요가 없거나 못 받았으면 {@code null}
     */
    private VillageBlock.Axis earlierFor(KmaGrid grid, LocalDate today, VillageBlock.Axis latest) {
        if (latest.hasBothHalves(today) && latest.extremes().has(today)) {
            return null;
        }
        try {
            return fetch(grid, today.atTime(BASE_HOURS[0], 0), "단기예보(이른 발표)").axis();
        } catch (RuntimeException e) {
            log.warn("[weather] 기상청 {} 이른 발표를 못 받았습니다 — 오전이 없으면 오늘이 빠져 폴백합니다: {}",
                    today, FailureReason.of(e));
            return null;
        }
    }

    private VillageBlock fetch(KmaGrid grid, LocalDateTime published, String kind) {
        // 격자를 이름에 싣는다 — 알람은 네 격자를 겹쳐 묻는데, 격자 없는 실패 로그로는 어느 곳인지 못 가른다
        String what = kind + " " + grid.nx() + "," + grid.ny();
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
        return publicationAtOrBefore(now.minusMinutes(PUBLISH_DELAY_MINUTES));
    }

    /** 한 판 앞 — 08시면 05시, 02시면 어제 23시. */
    private static LocalDateTime previousPublication(LocalDateTime published) {
        return publicationAtOrBefore(published.minusMinutes(1));
    }

    private static LocalDateTime publicationAtOrBefore(LocalDateTime usable) {
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

    /** {@code KmaRequest.opened}가 여는 봉투 — 머리가 정상일 때만 {@code body}를 읽는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(KmaRequest.Header header, VillageBlock body) {
    }
}
