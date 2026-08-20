package io.saiden.economyhelper.market.weather;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 하루 안에서 <b>비가 오는 한 토막</b> — 「언제부터 언제까지 · 무엇이 · 얼마나」.
 *
 * <p><b>왜 필요한가.</b> 일 단위 요약은 「비옴」까지만 말한다. 실측(2026-08-20 성남시)으로
 * 그 손실이 보인다 — 일 단위는 이슬비 코드에 최대 강수확률 80%였는데, 시간별로는
 * 13~19시에 몰려 있고 <b>오전은 마른 날</b>이었다. 우산을 언제 챙길지는 그 요약으로 알 수 없다.
 *
 * <p><b>하늘 상태와 다르다.</b> {@link SkyCondition}은 그날을 한 마디로 부르는 어휘이고, 이쪽은
 * 그 안의 시각이다. 맑다고 적힌 날에도 소나기 토막이 있을 수 있다 — 그게 이 레코드가 있는 이유다.
 *
 * <p><b>종류는 새로 만들지 않는다.</b> {@link SkyCondition}에 {@code SHOWERS}·{@code SNOW}·
 * {@code SNOW_SHOWERS}·{@code THUNDERSTORM}이 이미 있으므로 그것을 쓴다 — 같은 것을 두 어휘로
 * 부르면 언젠가 화면에서 갈린다.
 *
 * <p><b>확률과 강수량은 여기서도 두 칸이다.</b> {@code Daily}가 칸을 나눠 둔 것과 같은 이유다 —
 * 예보는 확률을 주고 지나간 날은 실제로 온 양을 준다. 「강수는 출처가 주는 것을 제 이름으로
 * 적는다」가 시간 단위에서도 그대로다.
 *
 * @param from   시작 시각(그 지역 현지시). 정시 단위다
 * @param to     끝 시각. <b>포함</b>이다 — 13시부터 19시까지면 19시에도 온다
 * @param kind   무엇이 오는가. 하늘 상태 어휘를 그대로 쓴다
 * @param chance 그 토막의 <b>최대</b> 확률(%). 예보가 아니면 {@code null}
 * @param amount 그 토막에 온 양(mm). 확률을 아는 출처에서는 {@code null}
 */
public record PrecipitationSpell(LocalTime from, LocalTime to, SkyCondition kind,
                        Integer chance, BigDecimal amount) {

    public PrecipitationSpell {
        if (from == null || to == null) {
            throw new IllegalArgumentException("강수 토막에 시각이 없습니다");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("강수 토막의 끝이 시작보다 이릅니다: " + from + " ~ " + to);
        }
    }

    /** 예보가 쓰는 생성자 — 확률을 안다. */
    public static PrecipitationSpell withChance(LocalTime from, LocalTime to, SkyCondition kind, Integer chance) {
        return new PrecipitationSpell(from, to, kind, chance, null);
    }

    /** 지나간 날이 쓰는 생성자 — 확률이라는 개념이 없고 실제로 온 양만 있다. */
    public static PrecipitationSpell withAmount(LocalTime from, LocalTime to, SkyCondition kind,
                                       BigDecimal amount) {
        return new PrecipitationSpell(from, to, kind, null, amount);
    }

    /** 한 시간짜리 토막인가 — 화면에서 「오후 3시」와 「오후 3시~5시」를 가른다. */
    public boolean single() {
        return from.equals(to);
    }
}
