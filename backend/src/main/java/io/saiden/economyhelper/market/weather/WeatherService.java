package io.saiden.economyhelper.market.weather;

import io.saiden.economyhelper.support.Failover;
import java.time.Clock;
import java.time.LocalDate;
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

    public WeatherService(List<WeatherClient> clients, Clock clock) {
        // 주입 순서를 믿지 않는다 — 위에 적은 순서가 곧 이 서비스의 계약이다
        this.clients = Failover.order(clients, ORDER, WeatherClient::source);
        this.clock = clock;
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
                        client.source().displayName(), e.toString()));
        if (found.isEmpty()) {
            log.error("[weather] 모든 출처에서 날씨를 가져오지 못했습니다");
        }
        return found;
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
