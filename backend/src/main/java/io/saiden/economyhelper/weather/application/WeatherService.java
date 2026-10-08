package io.saiden.economyhelper.weather.application;

import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.shared.support.Failover;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.weather.application.port.out.HourlyPrecipitationClient;
import io.saiden.economyhelper.weather.application.port.out.WeatherClient;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.HalfDay;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
import io.saiden.economyhelper.weather.domain.WeatherSource;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * <p><b>기상청(국내 오늘~+3일) → AccuWeather(오늘~+4일) → Open-Meteo(16일)</b>. 뒤로 갈수록
     * 제약이 적다 — 받쳐 주는 쪽이 제약이 적어야 이중화가 성립한다. 칸마다 맡는 기간은 각자의
     * {@code supports}가 정한다(표는 ADR-0009).
     *
     * <p>재분석이 목록 맨 뒤인 것은 우선순위가 낮아서가 아니라 <b>맡는 기간이 다르기</b>
     * 때문이다 — 지난 날짜에서는 앞의 둘이 {@code supports}에서 빠져 이쪽만 남는다.
     */
    private static final List<WeatherSource> ORDER = List.of(
            WeatherSource.KMA, WeatherSource.ACCU_WEATHER,
            WeatherSource.OPEN_METEO, WeatherSource.OPEN_METEO_ARCHIVE);

    private final List<WeatherClient> clients;
    private final Clock clock;

    /**
     * 강수 시각 보충. <b>{@code WeatherClient}가 아니다</b> — 그 계약은 실패를 던지라고 요구하는데
     * 보충은 삼켜야 하고, 폴백 순서에 서지도 않는다. 그래서 포트를 따로 둔다.
     */
    private final HourlyPrecipitationClient hourly;

    public WeatherService(List<WeatherClient> clients, Clock clock,
                          HourlyPrecipitationClient hourly) {
        // 주입 순서를 믿지 않는다 — 위에 적은 순서가 곧 이 서비스의 계약이다
        this.clients = Failover.order(clients, ORDER, WeatherClient::source);
        // FxService와 같은 그물이다 — ORDER에 없는 출처는 조용히 사라진다
        Failover.unordered(clients, WeatherClient::source, ORDER).forEach(dropped ->
                log.error("[weather] {} 클라이언트가 ORDER에 없어 영영 안 불립니다 — 이중화에서 빠졌습니다",
                        dropped.source()));
        this.clock = clock;
        this.hourly = hourly;
    }

    /**
     * @return 처음 성공한 출처의 날씨. 전부 실패하거나 맡을 출처가 없으면 {@link Optional#empty()}
     */
    public Optional<Weather> forecast(GeoLocation place, WeatherPeriod period) {
        LocalDate today = today(place);
        // 못 하는 출처는 부르지 않는다 — 먼저 걸러 두면 "시도했는가"를 목록이 말해 준다
        List<WeatherClient> eligible = clients.stream()
                .filter(client -> client.supports(place, period, today))
                .toList();

        if (eligible.isEmpty()) {
            // 지난 날짜인데 재분석까지 못 쓰는 상황 등. 실패와 구분해 남긴다
            log.warn("[weather] {} ~ {} 범위를 맡을 출처가 없습니다", period.from(), period.to());
            return Optional.empty();
        }

        // 맨 앞 후보가 시간별을 못 주면(국외 — AccuWeather) 답은 대개 그것이 내고 보충이 필요하다.
        // 그때는 예보와 보충을 **겹친다** — 순차면 국외 /weather마다 Open-Meteo 시간별(실측 912ms)을
        // 예보 뒤에 따로 기다렸다. 앞 후보가 실패해 시간별을 주는 출처가 답하면 보충 한 번이 헛돈다(실패 때만).
        // 국내는 맨 앞이 기상청(시간별을 준다)이라 순차다 — 답한 출처를 보고 정한다
        boolean supplementCertain = !period.past(today)
                && !eligible.getFirst().providesPrecipitationHours();
        Optional<Weather> found;
        Optional<Map<LocalDate, List<HalfDay>>> halves = Optional.empty();
        if (supplementCertain) {
            Concurrently.Pair<Optional<Weather>, Optional<Map<LocalDate, List<HalfDay>>>> both =
                    Concurrently.both(() -> firstOf(eligible, place, period), () -> halvesOf(place, period));
            found = both.first();
            halves = both.second();
        } else {
            found = firstOf(eligible, place, period);
        }
        if (found.isEmpty()) {
            log.error("[weather] 모든 출처에서 날씨를 가져오지 못했습니다");
            return found;
        }
        Weather weather = found.get();
        if (carriesHours(eligible, weather.source()) || period.past(today)) {
            return found;
        }
        if (!supplementCertain) {
            halves = halvesOf(place, period);
        }
        return Optional.of(halves.map(hours -> withHalves(weather, place, period, hours)).orElse(weather));
    }

    private Optional<Weather> firstOf(List<WeatherClient> eligible, GeoLocation place, WeatherPeriod period) {
        return Failover.first(eligible, client -> client.forecast(place, period),
                // 다음 출처가 있으면 조용히 넘어간다. 이게 이중화가 하는 일이다
                (client, e) -> log.warn("[weather] {} 조회 실패 — 다음 출처로 넘어갑니다: {}",
                        client.source().displayName(), FailureReason.of(e)));
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
     * <p><b>실패를 삼킨다</b> — 보충이라 없으면 화면에서 그 줄만 빠진다. 부를지는
     * {@link #forecast}가 정한다(답한 출처가 {@link WeatherClient#providesPrecipitationHours}이거나
     * 지나간 날이면 안 부른다).
     *
     * <p>⚠️ <b>얹는 것은 시각만이 아니다 — 강수확률도 함께 갈린다</b>
     * ({@link Weather.Daily#withHalves}). 그래서 <b>보충 출처를 화면까지 들고 간다</b>
     * ({@code Weather.precipitationSource}).
     *
     * <p><b>실패·빈손·성공을 각각 로그로 남긴다</b> — 화면에서는 셋이 같아 보인다.
     */
    private Optional<Map<LocalDate, List<HalfDay>>> halvesOf(GeoLocation place, WeatherPeriod period) {
        try {
            return Optional.of(hourly.halves(place, period));
        } catch (RuntimeException e) {
            log.warn("[weather] {} 강수 시각 보충 실패 — 일일 예보만 내보냅니다: {}",
                    place.name(), FailureReason.of(e));
            return Optional.empty();
        }
    }

    /** 받아 둔 시간별을 일별에 얹는다 — 비었거나 날짜가 안 겹치면 그대로 돌려준다. */
    private static Weather withHalves(Weather weather, GeoLocation place, WeatherPeriod period,
                                      Map<LocalDate, List<HalfDay>> halves) {
        if (halves.isEmpty()) {
            // ⚠️ 「마른 기간이라서」가 **아니다** — 마른 반나절도 담기므로(HalfDay.dry) 비었다는 것은
            //    응답에 시간별 시각이 아예 없었다는 뜻이다
            log.warn("[weather] {} {}~{} 시간별 응답에 시각이 없습니다 — 오전·오후 줄이 빠집니다",
                    place.name(), period.from(), period.to());
            return weather;
        }

        List<Weather.Daily> days = new ArrayList<>(weather.days().size());
        int replaced = 0;
        for (Weather.Daily day : weather.days()) {
            List<HalfDay> onThatDay = halves.get(day.date());
            if (onThatDay == null) {
                days.add(day);
            } else {
                days.add(day.withHalves(onThatDay));
                replaced++;
            }
        }
        if (replaced == 0) {
            // 시각은 왔는데 물어본 날짜와 하나도 겹치지 않았다(날짜 창이 어긋난 경우). 그러면 화면의 강수 줄은
            // 여전히 일별 출처 것이므로 Open-Meteo를 출처로 적으면 안 된다 — 아래 규칙의 거울상이다
            log.warn("[weather] {} 시간별 {}일이 물어본 날짜와 하나도 겹치지 않습니다 — 보충 없이 내보냅니다",
                    place.name(), halves.size());
            return weather;
        }
        log.info("[weather] {} 강수 시각을 {}일에 얹었습니다 ({} 보충)",
                place.name(), replaced, WeatherSource.OPEN_METEO.displayName());
        // ⚠️ 강수 줄이 이쪽 것이 되었으므로 화면도 그렇게 말해야 한다.
        //    일별도 Open-Meteo였으면 WeatherFormatter의 distinct()가 한 줄로 접는다
        return new Weather(weather.place(), days, weather.source(), WeatherSource.OPEN_METEO);
    }

    /**
     * <b>그 지역의 오늘.</b>
     *
     * <p>서울 자정에 부에노스아이레스를 물으면 거기는 아직 어제 낮이다. 우리 달력으로 자르면
     * 남의 하루가 둘로 쪼개진다.
     */
    public LocalDate today(GeoLocation place) {
        return LocalDate.ofInstant(clock.instant(), place.zone());
    }
}
