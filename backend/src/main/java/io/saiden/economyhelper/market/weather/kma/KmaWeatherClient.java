package io.saiden.economyhelper.market.weather.kma;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.market.weather.GeoLocation;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherClient;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 기상청 — <b>국내 1순위.</b> 단기예보(오늘~사흘 뒤)만 맡는다.
 *
 * <p><b>왜 단기만인가.</b> 그 나흘에서는 기상청이 명확히 우월하다 — 5km 동네예보 격자에
 * <b>시간별</b>을 함께 준다. 그래서 국내에서 「한 블록의 강수 값은 한 예보에서 나온다」가
 * 완전해진다({@link #providesPrecipitationHours}).
 *
 * <p>⚠️ <b>중기예보(+4일 이후)는 붙였다가 물렸다.</b> 두 축에서 Open-Meteo가 더 정확했다:
 *
 * <pre>
 *              기상청 중기        Open-Meteo
 * 공간 정밀도   도 단위 광역        ~1km 격자
 * 시각         없다               있다
 * </pre>
 *
 * <p>화면에서 그 차이가 보인다 — 중기는 시각을 안 줘서 {@code ☔ 오전 비}가 되는데
 * Open-Meteo는 {@code ☔ 오후 1시~7시 비 (최대 80%)}다. 게다가 중기는 좌표가 아니라
 * <b>지역코드</b>를 받아 대표지점 최근접으로 권역을 골라야 했고, 그 기하가 행정경계를
 * 재현하지 못했다 — 실측(대표지점 26개 · 표본 42곳)에서 <b>완도가 제주 권역</b>으로,
 * 남해·옥천·가평이 이웃 권역으로 갔다. 대표지점을 늘리면 다른 곳이 깨졌다.
 * (Codex 적대적 리뷰가 완도 사례로 잡았다.)
 *
 * <p><b>대가는 도달 범위다.</b> 이중화는 <b>한 답을 한 출처가</b> 낸다. 그래서 나흘을 넘는
 * 요청({@code 일주일치 성남})은 {@link #supports}에서 빠져 <b>통째로</b> Open-Meteo가 맡는다 —
 * 앞 나흘의 기상청 우위까지 함께 내놓는 셈이지만, 이레를 「기상청 나흘 + 광역 사흘」로 섞는
 * 것보다 낫다.
 *
 * <p>⚠️ <b>키가 없으면 아예 안 부른다</b>({@link #supports}). AccuWeather는 키가 없어도 매
 * 조회마다 헛호출을 한 번 태우는데, 그 실수를 여기서 되풀이하지 않는다.
 */
@Component
public class KmaWeatherClient implements WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(KmaWeatherClient.class);

    /** 남한의 시간대는 하나다. 이것이 「국내인가」의 두 번째 열쇠다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 오늘부터 나흘(오늘 + 3) — <b>단기예보가 온전히 덮는 범위</b>다.
     *
     * <p>실측으로 +3일은 3시간 간격 여덟 칸이지만 오전·오후가 다 있고, +4일은 00시 한 칸이라
     * 「반나절마다 반드시 한 줄」을 못 채운다({@code KmaVillageApi} javadoc의 표).
     */
    private static final int MAX_DAYS = 4;

    private final KmaVillageApi village;
    private final boolean configured;

    public KmaWeatherClient(KmaVillageApi village,
                            @Value("${economy-helper.weather.kma.api-key:}") String apiKey) {
        this.village = village;
        this.configured = apiKey != null && !apiKey.isBlank();
    }

    @Override
    public WeatherSource source() {
        return WeatherSource.KMA;
    }

    /**
     * <b>시간대와 격자가 함께 「국내」를 정한다.</b>
     *
     * <p>격자만으로는 못 자른다 — 격자가 직사각형이라 실측(2026-08-26)에서 후쿠오카
     * {@code (123,42)}·평양 {@code (39,158)}·블라디보스토크 {@code (135,250)}·선양
     * {@code (2,219)}이 모두 안에 들어왔다. 반대로 <b>울릉도·독도·마라도·백령도는 진짜 국내</b>라
     * 격자를 버릴 수도 없다.
     *
     * <p>그래서 시간대를 함께 본다. 남한은 {@code Asia/Seoul} 하나뿐이고 위 넷은 각각
     * {@code Asia/Tokyo}·{@code Asia/Pyongyang}·{@code Asia/Vladivostok}·
     * {@code Asia/Shanghai}다. 오전 6시 알람도 설정의 {@code weather.zone}이 그것이라 통한다.
     *
     * <p>⚠️ <b>나라 이름으로는 못 가른다.</b> 알람 경로는 지오코딩을 안 타 나라가 {@code null}이고
     * ({@code WeatherDigestJob}), 지오코딩이 주는 이름은 {@code language=ko}에 따라 갈린다.
     */
    @Override
    public boolean supports(GeoLocation place, WeatherPeriod period, LocalDate today) {
        return configured
                && place != null
                && SEOUL.equals(place.zone())
                && KmaGrid.of(place.latitude(), place.longitude()) != null
                // ⚠️ 시작일도 본다. period.past()는 **끝**만 보므로 「어제~오늘」이 통과하는데,
                //    단기예보에 지난 날짜가 없어 오늘 하나만 돌려주게 된다 — 물어본 어제가
                //    조용히 사라지고, 그것을 줄 수 있는 아카이브로 넘어가지도 않는다
                && !period.from().isBefore(today)
                && !period.to().isAfter(today.plusDays(MAX_DAYS - 1L));
    }

    /**
     * <b>시간별을 함께 준다.</b> 그래서 {@code WeatherService}가 Open-Meteo 보충 호출을 아예
     * 안 하고 {@code precipitationSource}도 비어 출처 줄이 한 줄로 남는다 — 국내에서
     * 「한 블록의 강수 값은 한 예보에서 나온다」가 완전해지는 자리다.
     */
    @Override
    public boolean providesPrecipitationHours() {
        return true;
    }

    /**
     * ⚠️ <b>캐시 접두사가 {@code kma:}다.</b> 세 출처가 {@code weather} 캐시 하나를 접두사로만
     * 나눠 쓰므로({@code OpenMeteoCacheKeyTest}) 겹치면 남의 답이 우리 이름으로 나간다.
     */
    @Override
    @Cacheable(cacheNames = CacheNames.WEATHER,
            key = "'kma:' + #a0.latitude() + ',' + #a0.longitude() + ',' + #a1.from() + ',' + #a1.to()")
    @CircuitBreaker(name = "weatherKma")
    public Weather forecast(GeoLocation place, WeatherPeriod period) {
        KmaGrid grid = KmaGrid.of(place.latitude(), place.longitude());
        if (grid == null) {
            throw new IllegalStateException("기상청 격자 밖입니다: " + place.name());
        }
        List<Weather.Daily> days = new ArrayList<>();
        for (KmaVillageApi.VillageDay day : village.days(grid, period.from())) {
            if (inside(day.date(), period)) {
                days.add(Weather.Daily
                        .withChance(day.date(), day.sky(), day.low(), day.high(), null)
                        .withHalves(day.halves()));
            }
        }
        requireEveryDay(period, days);
        return new Weather(place, List.copyOf(days), source());
    }


    /**
     * <b>물어본 날이 하나라도 비면 던진다 — 이 출처만 계약보다 엄하다.</b>
     *
     * <p>{@code WeatherClient}는 「요청한 범위를 다 못 줄 수는 있다 … 그건 실패가 아니다」라고
     * 정해 뒀다. 예보 길이가 출처마다 달라 <b>뒷부분이 비는 것</b>을 허용하는 규칙이다.
     *
     * <p>그런데 기상청은 <b>{@link #supports}가 이미 나흘로 좁혀 놨고, 그 나흘을 폴백 둘이
     * 온전히 덮는다</b>(AccuWeather 오늘~+4 · Open-Meteo 16일). 그래서 여기서 부분 응답을
     * 성공으로 돌려주는 것은 <b>언제나 손해</b>다 — 이중화가 첫 성공에서 멈추므로 온전히 줄 수
     * 있는 출처가 시도조차 안 되고, 그 반쪽이 10분 캐시에 굳는다.
     *
     * <p>{@code VillageBlock.toDays}가 <b>일부러</b> 날을 버린다(반나절이 하나뿐이거나 기온이
     * 없는 날). 그 판단은 그 자리에서 맞지만, <b>그래서 남은 것으로 답을 확정할지</b>는
     * 여기서 정한다. (Codex 적대적 리뷰가 잡았다.)
     *
     * @throws IllegalStateException 요청 범위의 날이 하나라도 빠졌을 때
     */
    private static void requireEveryDay(WeatherPeriod period, List<Weather.Daily> days) {
        long asked = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        if (days.size() < asked) {
            throw new IllegalStateException("기상청 응답에 요청한 날이 모자랍니다: "
                    + days.size() + "/" + asked + " (" + period.from() + "~" + period.to() + ")");
        }
    }

    private static boolean inside(LocalDate date, WeatherPeriod period) {
        return !date.isBefore(period.from()) && !date.isAfter(period.to());
    }
}
