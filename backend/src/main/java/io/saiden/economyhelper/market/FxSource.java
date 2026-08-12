package io.saiden.economyhelper.market;

/**
 * 환율 출처.
 *
 * <p><b>화면에 반드시 밝힌다.</b> 두 출처는 값의 의미가 다르다 — 토스는 1분 주기로 갱신되는
 * 참고 고시 환율이고, 수출입은행은 <b>영업일 11시경 하루 한 번</b> 고시하는 매매기준율이다.
 * 실측에서 같은 시각에 토스 {@code 1414.7} / 수출입은행 {@code 1,415}로 달랐다.
 *
 * <p>특히 주말에 수출입은행으로 폴백하면 며칠 전 값이 나가는데, 출처와 날짜를 숨기면
 * 그건 고장이 아니라 거짓말이 된다.
 */
public enum FxSource {

    TOSS("토스증권", true),
    KEXIM("수출입은행 매매기준율", false);

    private final String displayName;
    private final boolean intraday;

    FxSource(String displayName, boolean intraday) {
        this.displayName = displayName;
        this.intraday = intraday;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 하루 중에도 값이 바뀌는가.
     *
     * <p>참이면 시각까지("08-12 10:36 기준"), 거짓이면 날짜만("08-08 고시") 보여준다.
     * 하루 한 번 고시하는 값에 분 단위를 붙이면 실제보다 신선해 보인다.
     */
    public boolean intraday() {
        return intraday;
    }
}
