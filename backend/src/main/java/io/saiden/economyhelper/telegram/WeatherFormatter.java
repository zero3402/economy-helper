package io.saiden.economyhelper.telegram;

import static io.saiden.economyhelper.telegram.MessageLayout.DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.SHORT_DATE;
import static io.saiden.economyhelper.telegram.MessageLayout.empty;
import static io.saiden.economyhelper.telegram.MessageLayout.head;
import static io.saiden.economyhelper.telegram.MessageLayout.oneDecimal;
import static io.saiden.economyhelper.telegram.MessageLayout.sources;
import static io.saiden.economyhelper.telegram.MessageLayout.title;

import io.saiden.economyhelper.market.weather.HalfDay;
import io.saiden.economyhelper.market.weather.SkyCondition;
import io.saiden.economyhelper.market.weather.Weather;
import io.saiden.economyhelper.market.weather.WeatherPeriod;
import io.saiden.economyhelper.market.weather.WeatherSource;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 날씨 통 — <b>알람도 {@code /weather} 검색도 이것 하나를 쓴다.</b>
 *
 * <p><b>답은 언제나 일일 예보다.</b> 현재 기온을 적지 않는다 — 현재값과 일일값을 섞으면
 * 두 시간축이 한 화면에 서고, 6시 알람에서 그 시각 기온은 하루를 계획하는 데 쓸모도 없다.
 *
 * <p><b>강수는 출처가 주는 것을 제 이름으로 적는다</b> — 예보는 확률, 지나간 날은 강수량이다.
 * 강수량을 확률이라 부르면 값을 다른 것인 척 하는 것이다.
 */
public final class WeatherFormatter {

    private WeatherFormatter() {
    }

    /**
     * 날씨 통 — <b>오전 6시 알람도 {@code /weather} 한 지역도 이것 하나를 쓴다.</b>
     *
     * <p>{@code /stock}·{@code /crypto}와 같은 규칙이다 — 검색 답은 알람 통에 지역이 하나뿐인
     * 경우일 뿐이므로 포매터를 나누지 않는다. 나누면 같은 값이 두 모양으로 그려진다.
     *
     * <p><b>답은 언제나 일일 예보다.</b> 현재 기온을 적지 않는다 — 현재값과 일일값을 섞으면
     * "지금 21°C인데 최고가 29°C"처럼 두 시간축이 한 화면에 서고, 6시 알람에서 그 시각 기온은
     * 하루를 계획하는 데 쓸모도 없다.
     *
     * <p><b>계층이 세 겹으로 겹치지 않는다.</b> 알람은 지역 넷 × 하루, 검색은 지역 하나 × 여러 날이라
     * 「지역 → 날짜 → 값」이 동시에 서는 경우가 없다. 그래서 하루짜리 답은 날짜를 블록 제목으로
     * 올리지 않고 맨 아래 기준 줄에만 둔다.
     */
    public static String format(List<Weather> places) {
        if (places.isEmpty()) {
            return empty(Command.WEATHER);
        }
        StringBuilder message = new StringBuilder(title(Command.WEATHER));
        for (Weather place : places) {
            message.append("\n\n<b>").append(Html.escape(place.place().displayName())).append("</b>");
            appendDays(message, place.days());
        }
        // 출처와 기준은 통 하나처럼 끝맺는다 — 값 다음 빈 줄, 출처, 빈 줄, 기준.
        // 출처가 갈리면 하단에 각자 제 블록으로 쌓인다. 범위는 한 번에 조회하므로 같다
        return message.append("\n\n").append(sourcesOf(places))
                .append("\n\n").append(basisLinesOf(places))
                .toString();
    }

    /**
     * 하루면 값만, 여러 날이면 <b>하루가 블록 하나</b>다.
     *
     * <p>하루짜리에 날짜 제목을 붙이지 않는 이유는 그 날짜가 이미 맨 아래 기준 줄에 있기
     * 때문이다 — 같은 사실을 두 번 적으면 어느 쪽이 계층인지 흐려진다.
     */
    private static void appendDays(StringBuilder message, List<Weather.Daily> days) {
        boolean single = days.size() == 1;
        for (Weather.Daily day : days) {
            message.append("\n\n");
            if (!single) {
                message.append(SHORT_DATE.format(day.date())).append("\n");
            }
            if (day.sky().known()) {
                message.append(day.sky().label()).append("\n");
            }
            message.append(oneDecimal(day.low())).append("°C / ")
                    .append(oneDecimal(day.high())).append("°C");
            appendPrecipitation(message, day);
            appendHalves(message, day);
        }
    }

    /**
     * 강수 — <b>출처가 주는 것을 제 이름으로 적는다.</b>
     *
     * <p>확률은 예보만 준다 — 지나간 날은 확률이라는 개념 자체가 없고, 예보 응답에서 확률이
     * 빠지면 강수량으로 떨어진다. <b>강수량을 확률이라 부르지 않는다</b> — 값을 다른 것인 척
     * 하지 않는다는 규칙이 {@code (종가)}·{@code (고시)}와 같은 자리에서 여기에도 걸린다.
     */
    private static void appendPrecipitation(StringBuilder message, Weather.Daily day) {
        if (day.precipitationChance() != null) {
            message.append("\n강수확률 ").append(day.precipitationChance()).append("%");
        } else if (day.precipitationAmount() != null) {
            message.append("\n강수량 ").append(oneDecimal(day.precipitationAmount())).append("mm");
        }
    }

    /**
     * 조회처. 지역마다 폴백이 갈릴 수 있으므로 <b>하단에 모은다</b> — 모양은 {@link #sourcesOf}가
     * 정한다(증시와 같은 규칙이다).
     *
     * <p><b>지역 블록에는 출처를 달지 않는다.</b> 넷 중 하나가 폴백했을 뿐인데 지역마다 달면
     * 같은 이름이 다섯 번 찍힌다. 대신 <b>어느 지역이 폴백했는지를 이름으로 밝히지 않는다</b> —
     * 그래도 증상은 본문에 남는다. 그 지역만 강수확률이 아니라 강수량으로 바뀐다({@code appendPrecipitation}).
     *
     * <p><b>선언 순으로 정렬한다.</b> 등장 순이면 첫 지역이 폴백했을 때 2순위가 위로 올라오는데,
     * 세로로 쌓이면 그 순서가 눈에 보인다. {@code WeatherSource}의 선언 순이 곧 이중화
     * 순서({@code WeatherService})라 1순위가 언제나 위다.
     *
     * <p><b>한 지역에 출처가 둘일 수 있다.</b> 1순위(AccuWeather)는 하루를 낮/밤 두 칸으로만
     * 주므로 강수 줄(확률과 시각)이 통째로 Open-Meteo 시간별에서 온다 — 그때
     * {@code Weather.precipitationSource}가 채워지고 여기서 함께 오른다.
     * <b>숨기면 거짓말이 된다</b>: 화면의 강수확률이 AccuWeather 것이 아닌데 AccuWeather만
     * 적으면, 그건 「폴백으로 Open-Meteo가 답했는데 AccuWeather라고 적는」 것과 같은 일이다.
     * 일별까지 Open-Meteo가 맡은 날은 두 값이 같아 {@code distinct()}가 한 줄로 접는다.
     *
     * <p>이름이 {@code sourcesOf}가 아닌 이유는 제네릭 소거 때문이다 — 증시 쪽과 인자 목록이
     * 같아져 오버로드가 성립하지 않는다.
     */
    private static String sourcesOf(List<Weather> places) {
        return sources(places.stream()
                .flatMap(place -> Stream.of(place.source(), place.precipitationSource()))
                .filter(Objects::nonNull)
                .distinct().sorted().map(WeatherSource::displayName));
    }

    /**
     * 하루를 <b>오전 한 줄, 오후 한 줄</b>로.
     *
     * <p>두 줄이 <b>언제나</b> 나간다. 사람이 하루를 계획하는 단위가 반나절이므로 두 칸이 다
     * 채워져야 「오전엔 우산, 오후엔 필요 없음」을 한눈에 읽는다. 비 오는 구간만 적으면
     * 「오전」이 두 줄 나오거나 한 줄도 없는 날이 생겨 나머지 반나절을 짐작하게 된다.
     *
     * <p>젖은 반나절은 <b>가장 센 토막</b>의 시각과 확률을, 마른 반나절은 <b>그 시간대의
     * 하늘</b>을 적는다({@code HalfDays}가 정한다). 마른 쪽에 시각이 없는 것은 적을 것이 없어서다 —
     * 없는 값을 「0시~11시」로 채우지 않는다.
     *
     * <p><b>그림은 그 반나절이 무엇이었나를 가리킨다.</b> {@code ☔} 하나로 적으면 눈 오는 날에
     * 우산이 붙고, 마른 반나절에도 우산이 붙는다.
     *
     * <p>⚠️ {@code SkyCondition}은 <b>제 어휘에 이모지를 쓰지 않는다</b> — 그래서 그림 표가
     * 그 열거형이 아니라 여기 있다. 하늘 상태 어휘는 깨끗하게 남는다.
     */
    private static void appendHalves(StringBuilder message, Weather.Daily day) {
        for (HalfDay half : day.halves()) {
            // ⚠️ 마른데 하늘까지 못 읽었으면 적을 것이 없다. 젖었으면 <b>이름이 없어도 줄은
            //    낸다</b> — 시각과 확률이 이미 할 말을 하고, 여기서 건너뛰면 「반나절마다 반드시
            //    한 줄」이 깨져 읽는 사람이 나머지 반나절을 짐작하게 된다
            if (!half.kind().known() && !half.wet()) {
                continue;
            }
            message.append("\n").append(iconOf(half.kind())).append(" ")
                    .append(half.half().label());
            if (half.wet()) {
                message.append(" ").append(range(half.from(), half.to()));
            }
            if (half.kind().known()) {
                message.append(" ").append(half.kind().label());
            }
            // ⚠️ 마른 반나절은 확률을 들고 있어도 안 적는다(HalfDay.dry) — 안 오는 비에
            //    「최대 18%」를 붙이는 꼴이 된다. 그 값은 하루 요약이 쓰는 것이다
            if (!half.wet()) {
                continue;
            }
            if (half.chance() != null) {
                message.append(" (최대 ").append(half.chance()).append("%)");
            } else if (half.amount() != null) {
                message.append(" (").append(oneDecimal(half.amount())).append("mm)");
            }
        }
    }

    /** 종류에 맞는 그림. 눈에 우산을 붙이지 않고, 마른 하늘에도 우산을 붙이지 않는다. */
    private static String iconOf(SkyCondition kind) {
        return switch (kind) {
            case CLEAR -> "☀️";
            case MOSTLY_CLEAR -> "🌤️";
            case PARTLY_CLOUDY -> "⛅";
            // 기상청의 「구름많음」. ⛅(구름 조금)과 ☁️(흐림) 사이가 이 그림이다
            case MOSTLY_CLOUDY -> "🌥️";
            case CLOUDY -> "☁️";
            case FOG -> "🌫️";
            case SNOW, SNOW_SHOWERS -> "❄️";
            case SLEET -> "🌨️";
            case THUNDERSTORM, HAIL_THUNDERSTORM -> "⛈️";
            default -> "☔";
        };
    }

    /**
     * {@code 1시~7시} — 한 토막의 시간대. <b>오전·오후는 붙이지 않는다.</b>
     *
     * <p>그 접두사는 줄 앞의 반나절 이름이 이미 들고 있다({@link #appendHalves}). 여기서 또
     * 붙이면 {@code 오후 오후 1시~7시}가 된다.
     *
     * <p>⚠️ <b>토막은 정오를 넘지 않는다</b> — {@code HalfDays}가 반나절 안에서만 잇는다.
     * 그래서 앞끝과 뒤끝이 언제나 같은 반나절이고, 줄 앞의 이름 하나가 둘을 다 가리킨다.
     */
    private static String range(java.time.LocalTime from, java.time.LocalTime to) {
        if (from.equals(to)) {
            return twelve(from.getHour()) + "시";
        }
        return twelve(from.getHour()) + "시~" + twelve(to.getHour()) + "시";
    }


    /**
     * 12시간제의 시 숫자 — <b>0시와 12시가 모두 {@code 12}다.</b>
     *
     * <p>{@code hour % 12}만 쓰면 자정과 정오가 {@code 0시}가 되어 없는 표기가 나온다.
     */
    private static int twelve(int hour) {
        int rest = hour % 12;
        return rest == 0 ? 12 : rest;
    }

    /**
     * 기준 줄 — 날짜와 <b>그 값의 성격</b>이다.
     *
     * <p>하루치 값에 시각을 붙이면 그 시각의 값인 것처럼 읽힌다. 그래서 {@code (종가)}·
     * {@code (고시)}와 같은 규칙으로 {@code (예보)}·{@code (실측)}을 붙인다 — 지나간 날은
     * 예보가 아니라 실제로 그랬던 값이다.
     */
    private static String basisOf(Weather weather) {
        String kind = weather.source().forecast() ? " (예보)" : " (실측)";
        return weather.from().equals(weather.to())
                ? DATE.format(weather.from()) + kind
                : DATE.format(weather.from()) + " ~ " + SHORT_DATE.format(weather.to()) + kind;
    }

    /**
     * 기준 줄 — <b>성격마다 한 줄</b>이다. 증시의 {@code basisLines}·출처의 {@link #sourcesOf}와
     * 같은 규칙이다: 여럿이어도 한 블록이고, 한 줄에 하나씩 내려 적는다.
     *
     * <p>⚠️ 예전에는 <b>첫 지역 하나</b>로 정했다. 그런데 {@link #sourcesOf}가 존재하는
     * 전제가 "지역마다 폴백이 갈릴 수 있다"인데 성격은 안 갈렸다 — 예보와 실측이 섞인 통에서
     * 꼬리가 통째로 {@code (예보)}라고 말했고, 그건 값을 다른 것인 척 하는 일이다.
     * 지금은 실제로 도달하지 않지만({@code WeatherFacade}는 한 지역씩 답하고 알람은 전부 오늘이다)
     * 도달 불가에 기대면 그 가정이 깨지는 날 조용히 거짓말이 나간다.
     *
     * <p>성격이 하나뿐인 평상시에는 한 줄이므로 화면이 예전과 한 글자도 다르지 않다.
     */
    private static String basisLinesOf(List<Weather> places) {
        return places.stream().map(WeatherFormatter::basisOf)
                .distinct().collect(Collectors.joining("\n"));
    }

    public static String notFound(String query) {
        return head(Command.WEATHER)
                + "'" + Html.escape(query) + "'에 해당하는 지역을 찾지 못했습니다.\n\n"
                + "도시나 지역 이름으로 입력해 주세요.\n\n"
                + "예) /weather 서울 · /weather 내일 성남 · /weather 일주일치 파리";
    }

    /**
     * 지역을 안 적었다 — <b>우리가 골라 주지 않고 묻는다.</b>
     *
     * <p>사용자가 고르지 않은 지역으로 답하면 그 값이 맞는지 사용자가 알 수 없다. 인자 없이
     * {@code /stock}을 쳤을 때 사용법을 띄우는 것과 같은 자리다.
     *
     * <p>못 찾은 것({@link #notFound})과 다른 답이다 — 이미 지역을 적은 사람에게
     * 적으라고 하면 안 된다.
     */
    public static String needsPlace() {
        return head(Command.WEATHER)
                + "어느 지역인지 함께 적어 주세요.\n\n"
                + "예) /weather 서울 · /weather 내일 성남 · /weather 일주일치 파리";
    }

    /**
     * 날짜를 적었는데 펼 수 없었다 — <b>조용히 오늘로 만들지 않는다.</b>
     *
     * <p>{@code 2025년 8월}처럼 일자 없이 연·월만 적으면 펼 날이 없다. 그때 오늘 날씨를
     * 답하면 사용자는 자기가 적은 날짜가 무시된 줄 모른다 — 그럴듯한 숫자가 나와서 더 나쁘다.
     */
    public static String unreadableDate() {
        return head(Command.WEATHER)
                + "날짜를 읽지 못했습니다. 하루를 짚어 적어 주세요.\n\n"
                + "예) /weather 16일 서울 · /weather 8월 16일 서울 · /weather 2025년 8월 19일 서울";
    }

    /** 예보가 닿지 않는 날. <b>며칠까지 되는지를 함께 말한다</b> — 빈손만 주면 고장으로 보인다. */
    public static String tooFarAhead() {
        return head(Command.WEATHER)
                + "날씨 예보는 오늘부터 " + WeatherPeriod.MAX_FORECAST_DAYS + "일까지만 볼 수 있습니다.";
    }

    public static String unavailable() {
        return head(Command.WEATHER) + "날씨를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }
}
