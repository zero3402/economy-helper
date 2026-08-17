package io.saiden.economyhelper.market.weather;

import io.saiden.economyhelper.market.weather.WeatherResolver.ResolvedPlace;
import io.saiden.economyhelper.market.weather.openmeteo.GeocodingApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code /weather} 한 번의 전체 경로 — <b>해석 → 지오코딩 → 조회</b>.
 *
 * <p>웹훅이 이 세 단계를 직접 엮으면 컨트롤러가 도메인 지식을 들게 된다. {@code NewsFacade}가
 * 검색어 확장·랭킹·번역을 한 자리에 묶은 것과 같은 이유로 여기에 모은다.
 *
 * <p><b>LLM이 죽어도 답이 나간다.</b> 해석에 실패하면 사용자 원문을 그대로 지오코딩에 넣는다 —
 * {@code 파리}·{@code Tokyo} 같은 평범한 지명은 그걸로 걸린다. {@code StockService}가 LLM 실패
 * 시 이름 검색으로 내려가는 것과 같은 구조다.
 */
@Service
public class WeatherFacade {

    private static final Logger log = LoggerFactory.getLogger(WeatherFacade.class);

    private final WeatherResolver resolver;
    private final GeocodingApi geocoding;
    private final WeatherService weatherService;

    public WeatherFacade(WeatherResolver resolver, GeocodingApi geocoding,
                         WeatherService weatherService) {
        this.resolver = resolver;
        this.geocoding = geocoding;
        this.weatherService = weatherService;
    }

    /**
     * 검색 한 건.
     *
     * @return 못 찾은 이유까지 담은 결과 — 호출자가 문구를 고른다
     */
    public Lookup search(String query) {
        Optional<ResolvedPlace> resolved = resolver.resolve(WeatherResolver.cacheKeyOf(query));

        Optional<GeoLocation> place = locate(query, resolved.orElse(null));
        if (place.isEmpty()) {
            // ⚠️ 둘을 반드시 가른다. '내일 서현'처럼 지역을 적었는데 못 찾은 경우까지
            // "지역을 적어 주세요"로 답하면, 이미 적은 사용자에게 적으라고 하는 꼴이 된다.
            // LLM이 지역을 못 읽었을 때만 물어본다
            return resolved.isPresent() && !resolved.get().hasPlace()
                    ? Lookup.noPlace()
                    : Lookup.notFound();
        }

        WeatherPeriod period = periodOf(place.get(), resolved.orElse(null));
        if (period.beyondForecast(weatherService.today(place.get()))) {
            // 빈손으로 두지 않고 며칠까지 되는지 밝힌다
            return Lookup.tooFarAhead();
        }
        return weatherService.forecast(place.get(), period)
                .map(weather -> Lookup.found(List.of(weather)))
                .orElseGet(Lookup::unavailable);
    }

    /**
     * 좌표를 정한다 — <b>LLM이 다듬은 지명을 먼저, 안 되면 원문으로.</b>
     *
     * <p>LLM이 지역을 못 읽었어도 포기하지 않는다. 사용자가 이미 지오코딩이 찾을 수 있는
     * 이름을 쳤을 수 있고, 그때 LLM 실패가 곧 검색 실패가 되면 아깝다.
     */
    private Optional<GeoLocation> locate(String query, ResolvedPlace resolved) {
        if (resolved != null && resolved.hasPlace()) {
            Optional<GeoLocation> byResolved =
                    geocoding.find(resolved.query().trim(), resolved.country());
            if (byResolved.isPresent()) {
                return byResolved;
            }
            log.info("[weather] LLM이 준 '{}'를 못 찾아 원문으로 다시 시도합니다", resolved.query());
        }
        // 지역이 아예 없는 물음('일주일치 날씨')도 여기까지 온다 — 그때는 빈손으로 돌려주고
        // 호출자가 "어느 지역인지 적어 주세요"로 답한다. 우리가 지역을 골라 주면
        // 그 답이 맞는지 사용자가 알 수 없다
        return geocoding.find(query.trim(), null);
    }

    /**
     * 기간을 편다. <b>기준은 그 지역의 오늘</b>이고, 해석이 없으면 오늘 하루치다.
     *
     * <p>연도를 적은 날짜가 먼저다. 없으면 월·일만 적은 것으로 보고 <b>가장 가까운 해</b>를
     * 코드가 고른다 — LLM에게 연도를 맡겼더니 {@code 8월 16일}에 2024년을 지어냈다.
     */
    private WeatherPeriod periodOf(GeoLocation place, ResolvedPlace resolved) {
        LocalDate today = weatherService.today(place);
        if (resolved == null) {
            return WeatherPeriod.of(today, null, null, null);
        }
        LocalDate date = resolved.absoluteDate() != null
                ? resolved.absoluteDate()
                : WeatherPeriod.nearestOccurrence(today, resolved.month(), resolved.day());
        return WeatherPeriod.of(today, date, resolved.offsetDays(), resolved.days());
    }

    /**
     * 조회 결과와 <b>못 된 이유</b>.
     *
     * <p>넷을 구분하는 이유는 사용자가 할 일이 다르기 때문이다 — 지역을 못 찾은 것은 검색어를
     * 고쳐야 하고, 너무 먼 미래는 고쳐도 안 되며, 조회 실패는 잠시 뒤 다시 치면 된다.
     */
    public record Lookup(List<Weather> places, Reason reason) {

        public enum Reason { FOUND, NO_PLACE, NOT_FOUND, TOO_FAR_AHEAD, UNAVAILABLE }

        static Lookup found(List<Weather> places) {
            return new Lookup(List.copyOf(places), Reason.FOUND);
        }

        /** 지역을 아예 안 적었다 — 적은 것을 못 찾은 {@link #notFound()}와 다른 답이 나가야 한다. */
        static Lookup noPlace() {
            return new Lookup(List.of(), Reason.NO_PLACE);
        }

        static Lookup notFound() {
            return new Lookup(List.of(), Reason.NOT_FOUND);
        }

        static Lookup tooFarAhead() {
            return new Lookup(List.of(), Reason.TOO_FAR_AHEAD);
        }

        static Lookup unavailable() {
            return new Lookup(List.of(), Reason.UNAVAILABLE);
        }
    }
}
