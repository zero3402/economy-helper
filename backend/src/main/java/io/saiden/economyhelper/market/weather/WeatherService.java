package io.saiden.economyhelper.market.weather;

import io.saiden.economyhelper.support.Failover;
import io.saiden.economyhelper.market.weather.openmeteo.OpenMeteoHourlyClient;
import io.saiden.economyhelper.support.FailureReason;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 날씨 조회의 단일 진입점 — <b>이중화가 여기서 성립한다.</b>
 *
 * <p>{@code FxService}와 같은 구조다: 출처 순서를 코드가 들고 있고, 1순위가 던지면 다음으로
 * 넘어가며, 전부 실패해야 빈손이다. <b>이중화는 장애 대비다</b> — 두 출처가 같은 값을 주게
 * 맞추는 것이 아니라 하나가 죽어도 답이 나가게 하는 것이다.
 *
 * <p><b>못 하는 출처는 부르지 않는다</b>({@link WeatherClient#supports}). AccuWeather 무료 등급은
 * 5일까지이고 지난 날짜도 못 주는데, 그걸 알면서 부르면 서킷브레이커에 애먼 실패가 쌓이고
 * 사용자는 그만큼 더 기다린다. 하루 50회짜리 한도까지 헛되이 축낸다.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /**
     * 시도 순서. 앞이 1순위다. <b>{@link WeatherSource}의 선언 순서와 같아야 한다</b> —
     * 화면의 출처 줄이 그 선언 순으로 정렬된다.
     *
     * <p><b>AccuWeather가 먼저인 이유는 지점 예보의 정확도다.</b> 대신 키가 필요하고 무료
     * 등급이 하루 50회라, 한도를 넘기거나 죽으면 키가 없어 한도에 걸리지 않는 Open-Meteo가
     * 받는다 — 받쳐 주는 쪽이 제약이 적어야 이중화가 성립한다.
     *
     * <p><b>5일을 넘는 기간은 Open-Meteo만 할 수 있다.</b> AccuWeather 무료 등급이 5일까지라
     * {@code supports}에서 빠지고, '일주일치 파리' 같은 요청은 그대로 2순위가 답한다.
     *
     * <p>재분석이 목록 맨 뒤인 것은 우선순위가 낮아서가 아니라 <b>맡는 기간이 다르기</b>
     * 때문이다 — 지난 날짜에서는 앞의 둘이 {@code supports}에서 빠져 이쪽만 남는다.
     */
    private static final List<WeatherSource> ORDER = List.of(
            WeatherSource.ACCU_WEATHER, WeatherSource.OPEN_METEO, WeatherSource.OPEN_METEO_ARCHIVE);

    private final List<WeatherClient> clients;
    private final Clock clock;

    /**
     * 강수 시각 보충. <b>{@code WeatherClient}가 아니다</b> — 그 계약은 실패를 던지라고 요구하는데
     * 보충은 삼켜야 하고, 폴백 순서에 서지도 않는다. 인터페이스를 만들지 않는 이유이기도 하다
     * (구현이 하나뿐인 인터페이스는 두지 않는다는 규칙).
     */
    private final OpenMeteoHourlyClient hourly;

    public WeatherService(List<WeatherClient> clients, Clock clock,
                          OpenMeteoHourlyClient hourly) {
        // 주입 순서를 믿지 않는다 — 위에 적은 순서가 곧 이 서비스의 계약이다
        this.clients = Failover.order(clients, ORDER, WeatherClient::source);
        this.clock = clock;
        this.hourly = hourly;
    }

    /**
     * @return 처음 성공한 출처의 날씨. 전부 실패하거나 맡을 출처가 없으면 {@link Optional#empty()}
     */
    public Optional<Weather> forecast(GeoLocation place, WeatherPeriod period) {
        LocalDate today = today(place);
        // 못 하는 출처는 부르지 않는다 — 먼저 걸러 두면 "시도했는가"를 목록이 말해 준다.
        // 예전에는 루프를 관통하는 가변 플래그(tried)가 그 일을 했다
        List<WeatherClient> eligible = clients.stream()
                .filter(client -> client.supports(period, today))
                .toList();

        if (eligible.isEmpty()) {
            // 지난 날짜인데 재분석까지 못 쓰는 상황 등. 실패와 구분해 남긴다
            log.warn("[weather] {} ~ {} 범위를 맡을 출처가 없습니다", period.from(), period.to());
            return Optional.empty();
        }

        Optional<Weather> found = Failover.first(eligible, client -> client.forecast(place, period),
                // 다음 출처가 있으면 조용히 넘어간다. 이게 이중화가 하는 일이다
                (client, e) -> log.warn("[weather] {} 조회 실패 — 다음 출처로 넘어갑니다: {}",
                        client.source().displayName(), FailureReason.of(e)));
        if (found.isEmpty()) {
            log.error("[weather] 모든 출처에서 날씨를 가져오지 못했습니다");
            return found;
        }
        return found.map(weather -> withHalvesHours(weather, place, period, today,
                carriesHours(eligible, weather.source())));
    }

    /**
     * 그 출처가 시간별까지 함께 주는가 — <b>답한 클라이언트에게 묻는다.</b>
     *
     * <p>{@link Weather}는 출처 이름만 들고 오므로 능력은 클라이언트 쪽에 있다. 후보 목록에서
     * 그 이름을 가진 것을 찾아 물어본다.
     *
     * @return 그 이름의 클라이언트가 없으면 {@code false} — 모르는 출처는 못 주는 것으로 본다
     */
    private static boolean carriesHours(List<WeatherClient> candidates, WeatherSource source) {
        return candidates.stream()
                .filter(client -> client.source() == source)
                .anyMatch(WeatherClient::providesPrecipitationHours);
    }

    /**
     * 강수 시각을 채운다 — <b>1순위가 시간 단위를 못 줄 때만.</b>
     *
     * <p>Open-Meteo가 일별을 맡았으면 시간별이 <b>같은 응답에 함께 왔다</b>. 그때는 부를 것이
     * 없다. AccuWeather가 맡았으면 그쪽은 낮/밤 두 칸뿐이라 시각이 없다 — 그것만 보충한다.
     *
     * <p><b>실패를 삼킨다.</b> 일별은 이미 손에 있으므로 여기서 예외를 올리면 답이 통째로
     * 죽는다. 「실패를 삼키지 않는다」는 폴백 상대의 규칙이고 이것은 폴백이 아니다 —
     * 없으면 화면에서 그 줄만 빠진다.
     *
     * <p>지나간 날은 부르지 않는다. 재분석이 이미 시간별 강수량을 함께 주고, 예보 엔드포인트에
     * 과거를 물으면 빈손이다.
     *
     * <p>⚠️ <b>얹는 것은 시각만이 아니다 — 강수확률도 함께 갈린다</b>
     * ({@link Weather.Daily#withHalves}). 시각과 확률이 서로 다른 예보에서 오면
     * 한 블록에 <b>두 예보의 숫자</b>가 서고, 확률이 높은데 시각 줄이 없는 화면이 성립한다.
     *
     * <p>그래서 <b>보충 출처를 화면까지 들고 간다</b>({@code Weather.precipitationSource}).
     * 강수 줄이 통째로 이쪽 것이므로 출처 줄이 그 사실을 말해야 한다 — 「Open-Meteo가 답했는데
     * AccuWeather라고 적으면 거짓말이 된다」는 {@link WeatherSource}의 규칙이 강수 줄에도 걸린다.
     *
     * <p><b>실패·빈손·성공을 각각 로그로 남긴다.</b> 셋이 화면에서는 같아 보이므로
     * (셋 다 강수 시각 줄이 없다) 로그로 갈라야 어느 쪽인지 알 수 있다.
     *
     * @param sourceCarriesHours 일별을 맡은 출처가 시간별까지 함께 주는가
     *                           ({@link WeatherClient#providesPrecipitationHours}).
     *                           참이면 이미 손에 있으므로 묻지 않는다
     */
    private Weather withHalvesHours(Weather weather, GeoLocation place,
                                           WeatherPeriod period, LocalDate today,
                                           boolean sourceCarriesHours) {
        if (sourceCarriesHours || period.past(today)) {
            return weather;
        }
        Map<LocalDate, List<HalfDay>> halves;
        try {
            halves = hourly.halves(place, period);
        } catch (RuntimeException e) {
            log.warn("[weather] {} 강수 시각 보충 실패 — 일일 예보만 내보냅니다: {}",
                    place.name(), FailureReason.of(e));
            return weather;
        }
        if (halves.isEmpty()) {
            log.info("[weather] {} {}~{} 시간별에 강수 토막이 없습니다 — 마른 기간이거나 문턱 아래입니다",
                    place.name(), period.from(), period.to());
            return weather;
        }

        List<Weather.Daily> days = weather.days().stream()
                .map(day -> halves.containsKey(day.date())
                        ? day.withHalves(halves.get(day.date()))
                        : day)
                .toList();
        log.info("[weather] {} 강수 시각을 {}일에 얹었습니다 ({} 보충)",
                place.name(), halves.size(), WeatherSource.OPEN_METEO.displayName());
        // ⚠️ 강수 줄이 이쪽 것이 되었으므로 화면도 그렇게 말해야 한다.
        //    withHalves이 확률까지 이 시간별로 다시 세므로 일별 출처의 확률은 더 이상
        //    화면에 없다 — 숨기면 「Open-Meteo가 답했는데 AccuWeather라고 적는」 그 거짓말이 된다.
        //    일별도 Open-Meteo였으면 WeatherFormatter의 distinct()가 한 줄로 접는다
        return new Weather(weather.place(), days, weather.source(), WeatherSource.OPEN_METEO);
    }

    /**
     * <b>그 지역의 오늘.</b>
     *
     * <p>서울 자정에 부에노스아이레스를 물으면 거기는 아직 어제 낮이다. 우리 달력으로 자르면
     * 남의 하루가 둘로 쪼개지는데, 이건 뉴스 신선도에서 이미 한 번 겪어 고친 문제다.
     */
    public LocalDate today(GeoLocation place) {
        return LocalDate.ofInstant(clock.instant(), place.zone());
    }
}
