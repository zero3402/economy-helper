package io.saiden.economyhelper.weather.application;

import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.weather.application.port.out.Geocoder;
import io.saiden.economyhelper.weather.application.port.out.PlaceResolver;
import io.saiden.economyhelper.weather.domain.GeoLocation;
import io.saiden.economyhelper.weather.domain.ResolvedPlace;
import io.saiden.economyhelper.weather.domain.Weather;
import io.saiden.economyhelper.weather.domain.WeatherPeriod;
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

    private final PlaceResolver resolver;
    private final Geocoder geocoding;
    private final WeatherService weatherService;

    public WeatherFacade(PlaceResolver resolver, Geocoder geocoding,
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
        try {
            return lookup(query);
        } catch (RuntimeException e) {
            // resolver.resolve()·geocoding.find()에 걸린 @Cacheable 프록시가 던지는 것을 잡는다 —
            // Redis가 죽으면 캐시 계층이 던지는데 그건 해석기 안쪽 try가 못 잡는다(메서드 밖이다).
            // 없으면 /weather가 무응답이 된다
            log.error("[weather] '{}' 조회 실패: {}", query, FailureReason.of(e));
            return Lookup.unavailable();
        }
    }

    private Lookup lookup(String query) {
        // 친 그대로 넘긴다 — 캐시 키는 해석기가 다듬는다(PlaceResolver)
        Optional<ResolvedPlace> resolved = resolver.resolve(query);

        Optional<GeoLocation> place = locate(query, resolved.orElse(null));
        if (place.isEmpty()) {
            // ⚠️ 둘을 반드시 가른다. '내일 서현'처럼 지역을 적었는데 못 찾은 경우까지
            // "지역을 적어 주세요"로 답하면, 이미 적은 사용자에게 적으라고 하는 꼴이 된다.
            // LLM이 지역을 못 읽었을 때만 물어본다
            return resolved.isPresent() && !resolved.get().hasPlace()
                    ? Lookup.noPlace()
                    : Lookup.notFound();
        }

        // 그 지역의 오늘은 한 번만 잰다 — 자정을 걸친 조회가 날짜마다 다른 「오늘」을 쓰지 않게
        LocalDate today = weatherService.today(place.get());

        // 날짜를 적었는데 우리가 못 폈으면 그렇게 말한다(ResolvedPlace.mentionsDate)
        if (resolved.isPresent() && resolved.get().mentionsDate()
                && dateOf(today, resolved.get()) == null) {
            return Lookup.unreadableDate();
        }

        WeatherPeriod period = periodOf(today, resolved.orElse(null));
        if (period.beyondForecast(today)) {
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
            String asked = resolved.query().trim();
            Optional<GeoLocation> byResolved =
                    geocoding.find(asked, resolved.countryCode())
                            // 로마자로 온 이름을 물어본 한국어 지명으로 바꾼다. 지오코딩이
                            // 담을 때가 아니라 여기서 하는 이유는 그 결과가 30일 캐시에
                            // 들어가기 때문이다 — GeoLocation.labelledFor javadoc 참고
                            .map(place -> place.labelledFor(asked));
            if (byResolved.isPresent()) {
                return byResolved;
            }
            log.info("[weather] LLM이 준 '{}'를 못 찾아 원문으로 다시 시도합니다", resolved.query());
        }
        // 지역이 아예 없는 물음('일주일치 날씨')도 여기까지 온다 — 그때는 빈손으로 돌려주고
        // 호출자가 "어느 지역인지 적어 주세요"로 답한다. 우리가 지역을 골라 주면
        // 그 답이 맞는지 사용자가 알 수 없다
        String asked = query.trim();
        return geocoding.find(asked, null).map(place -> place.labelledFor(asked));
    }

    /**
     * 기간을 편다. <b>기준은 그 지역의 오늘</b>이고, 해석이 없으면 오늘 하루치다.
     *
     * <p>연도를 적은 날짜가 먼저다. 없으면 월·일만 적은 것으로 보고 <b>가장 가까운 해</b>를
     * 코드가 고른다 — LLM에게 연도를 맡겼더니 {@code 8월 16일}에 2024년을 지어냈다.
     */
    private static WeatherPeriod periodOf(LocalDate today, ResolvedPlace resolved) {
        if (resolved == null) {
            return WeatherPeriod.of(today, null, null, null);
        }
        // 날짜를 적었으면 그 날이 이긴다 — 적은 날을 버리고 다른 날을 답하면 틀린 값이다.
        // 그다음이 요일(하루), 그다음이 주말(토·일)이다 — 「일요일」을 주말로 읽으면 토요일이 끼어든다
        // ⚠️ 「다음 주」는 LLM이 두 필드 중 어디에 적을지 흔들린다 — 요일에는 weekOffset, 주말에는 offsetDays 7을
        //    달라고 했지만 반대로 오면 이번 주가 나간다. 어느 쪽으로 와도 같은 주를 고른다
        if (resolved.asksWeekday() && !resolved.mentionsDate()) {
            // 그 요일부터 며칠치 — 「일요일부터 사흘」도 되고, 꼬리는 of()가 예보 상한으로 자른다
            LocalDate from = WeatherPeriod.weekday(today, resolved.weekday(), weeksAhead(resolved)).from();
            return WeatherPeriod.of(today, from, null, resolved.days());
        }
        if (resolved.asksWeekend() && !resolved.mentionsDate()) {
            Integer weeks = weeksAhead(resolved);
            // ⚠️ 두 갈래를 다 Integer로 둔다 — 한쪽이 int면 offsetDays가 언박싱돼 null에서 NPE가 난다
            Integer offset = weeks == null ? resolved.offsetDays() : Integer.valueOf(weeks * 7);
            return WeatherPeriod.weekend(today, offset);
        }
        return WeatherPeriod.of(today, dateOf(today, resolved),
                resolved.offsetDays(), resolved.days());
    }

    /**
     * 몇 주 뒤인가 — {@code weekOffset}이 있으면 그것, 없으면 <b>7의 배수인</b> {@code offsetDays}를 주로 읽는다
     * (「다음 주 화요일」을 {@code offsetDays: 7}로 준 경우). 둘 다 아니면 {@code null}(이번 주).
     */
    private static Integer weeksAhead(ResolvedPlace resolved) {
        if (resolved.weekOffset() != null && resolved.weekOffset() != 0) {
            return resolved.weekOffset();
        }
        Integer days = resolved.offsetDays();
        return days != null && days > 0 && days % 7 == 0 ? days / 7 : null;
    }

    /**
     * 사용자가 적은 날짜 — <b>적은 것은 쓰고 안 적은 것만 채운다.</b>
     *
     * <p>연도까지 적었으면 그대로, 아니면 {@link WeatherPeriod#nearestOccurrence}가 안 적은
     * 연도·월을 오늘 기준으로 고른다. 날짜를 아예 안 적었으면 {@code null}이고 호출자가
     * {@code offsetDays}로 간다.
     */
    private static LocalDate dateOf(LocalDate today, ResolvedPlace resolved) {
        if (resolved.absoluteDate() != null) {
            return resolved.absoluteDate();
        }
        return WeatherPeriod.nearestOccurrence(today, resolved.month(), resolved.day());
    }

    /**
     * 조회 결과와 <b>못 된 이유</b>.
     *
     * <p>여섯으로 가르는 이유는 사용자가 할 일이 다르기 때문이다 — 지역을 못 찾은 것은 검색어를
     * 고쳐야 하고, 너무 먼 미래는 고쳐도 안 되며, 조회 실패는 잠시 뒤 다시 치면 된다.
     */
    public record Lookup(List<Weather> places, Reason reason) {

        public enum Reason { FOUND, NO_PLACE, NOT_FOUND, UNREADABLE_DATE, TOO_FAR_AHEAD, UNAVAILABLE }

        /** 날짜를 적었는데 펼 수 없었다 — 조용히 오늘로 만들지 않는다. */
        static Lookup unreadableDate() {
            return new Lookup(List.of(), Reason.UNREADABLE_DATE);
        }

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
