package io.saiden.economyhelper.market.weather;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 하루의 <b>반나절 하나</b> — 오전이면 오전, 오후면 오후에 무슨 일이 있었나.
 *
 * <p>하루는 언제나 이것 <b>둘</b>로 요약된다. 사람이 하루를 계획하는 단위가 반나절이라서다 —
 * 「오전에 우산, 오후엔 필요 없음」이 이 화면이 답해야 할 질문이고, 그러려면 두 칸이 <b>항상</b>
 * 채워져 있어야 한다. 비 오는 구간만 적으면 「오전」이 두 줄 나오거나 한 줄도 없는 날이 생겨
 * 읽는 사람이 나머지 반나절을 짐작하게 된다.
 *
 * <p><b>젖은 반나절과 마른 반나절은 담는 것이 다르다.</b>
 *
 * <ul>
 *   <li>젖으면 — 그 반나절에서 <b>가장 센 토막</b>의 시각과 확률(또는 강수량)을 든다.
 *       여러 토막이 있어도 하나로 접는다: 둘을 다 적으면 같은 접두사가 되풀이되고,
 *       사이의 마른 시간까지 한 범위로 이으면 오지 않는 비를 적는 셈이 된다.
 *   <li>마르면 — 시각도 확률도 없고 <b>그 시간대의 하늘</b>만 든다. 지어낸 「맑음」이 아니라
 *       시간별 코드에서 읽은 값이라, 하루 요약이 「흐림」인 날의 오전을 「맑음」이라 부르는
 *       모순이 생기지 않는다.
 * </ul>
 *
 * @param half   오전인가 오후인가. 마른 반나절은 시각이 없으므로 이 값이 유일한 자리 표시다
 * @param kind   무엇이 있었나 — 젖으면 비·눈 따위, 마르면 하늘 상태
 * @param from   가장 센 토막의 시작(현지시). <b>마르면 {@code null}</b>
 * @param to     그 토막의 끝. <b>포함</b>이다 — 4시~8시면 8시에도 온다. 마르면 {@code null}
 * @param chance 최대 확률(%). 예보가 아니면 {@code null}. <b>마른 반나절도 제 봉우리를 든다</b>
 *               — 화면에는 안 나가고 하루 요약이 쓴다({@link #dry})
 * @param amount 그 토막에 온 양(mm). 확률을 아는 출처이거나 마르면 {@code null}
 */
public record HalfDay(Half half, SkyCondition kind, LocalTime from, LocalTime to,
                      Integer chance, BigDecimal amount) {

    /** 정오가 가른다. 0~11시가 오전, 12~23시가 오후다. */
    public enum Half {

        MORNING("오전"),
        AFTERNOON("오후");

        private final String label;

        Half(String label) {
            this.label = label;
        }

        /** 화면에 그대로 적는 말. */
        public String label() {
            return label;
        }

        /** 그 시각이 속한 반나절. */
        public static Half of(LocalTime at) {
            return at.getHour() < 12 ? MORNING : AFTERNOON;
        }
    }

    public HalfDay {
        if (half == null) {
            throw new IllegalArgumentException("반나절이 오전인지 오후인지 없습니다");
        }
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("토막의 시작과 끝은 함께 있거나 함께 없어야 합니다");
        }
        if (from != null && to.isBefore(from)) {
            throw new IllegalArgumentException("토막의 끝이 시작보다 이릅니다: " + from + " ~ " + to);
        }
    }

    /** 예보가 쓰는 것 — 확률을 안다. 반나절은 시작 시각이 정한다. */
    public static HalfDay withChance(LocalTime from, LocalTime to,
                                     SkyCondition kind, Integer chance) {
        return new HalfDay(Half.of(from), kind, from, to, chance, null);
    }

    /** 지나간 날이 쓰는 것 — 확률이라는 개념이 없고 실제로 온 양만 있다. */
    public static HalfDay withAmount(LocalTime from, LocalTime to,
                                     SkyCondition kind, BigDecimal amount) {
        return new HalfDay(Half.of(from), kind, from, to, null, amount);
    }

    /**
     * 비도 눈도 없는 반나절 — 그 시간대의 하늘을 있는 그대로 든다.
     *
     * <p>⚠️ <b>확률은 들되 화면에는 안 적는다.</b> 마른 반나절에 「최대 18%」를 적으면 안 오는
     * 비를 적는 셈이라 {@code WeatherFormatter}가 {@link #wet()}으로 그 괄호를 막는다. 그런데도
     * 담는 이유는 <b>하루 요약이 이 값을 필요로 하기 때문</b>이다 — {@code Weather.Daily.withHalves}가
     * 강수확률을 시간별 봉우리로 갈아 끼우는데, 양쪽이 다 마르면 갈 값이 없어 <b>일별 출처의
     * 확률이 남는다.</b> 실측(2026-08-26 미금역)에서 그 자리가 「소나기 61%(AccuWeather)」인데
     * 오전·오후 둘 다 마른(Open-Meteo 봉우리 18%) 화면이 됐다 — 한 블록에 <b>두 예보의 숫자</b>가
     * 선 것이다.
     *
     * @param chance 그 반나절의 최대 확률(%). 확률을 안 주는 출처(재분석)면 {@code null}
     */
    public static HalfDay dry(Half half, SkyCondition kind, Integer chance) {
        return new HalfDay(half, kind, null, null, chance, null);
    }

    /** 비·눈이 있었나. 시각이 있으면 젖은 것이다. */
    public boolean wet() {
        return from != null;
    }

    /** 한 시간짜리 토막인가 — 화면에서 「오후 3시」와 「오후 3시~5시」를 가른다. */
    public boolean single() {
        return wet() && from.equals(to);
    }
}
