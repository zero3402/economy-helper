package io.saiden.economyhelper.market;

/**
 * 환율 출처.
 *
 * <p><b>화면에 반드시 밝힌다.</b> 두 출처는 고시 주체도 시점도 다르다 —
 * 유럽중앙은행과 한국 정부가 각자 자기 기준으로 하루 한 번 낸다. 실측에서 같은 날
 * Frankfurter {@code 1412.17} / 수출입은행 {@code 1,415}로 달랐다.
 *
 * <p>폴백이 일어나면 며칠 전 값이 나갈 수 있는데, 출처와 날짜를 숨기면
 * 그건 고장이 아니라 거짓말이 된다.
 *
 * <p>둘 다 인증에 IP 등록을 요구하지 않는다 — 배포 환경에서
 * 쓸 수 없었다. 아래 둘은 인증도 IP 제한도 없어 로컬과 배포가 같은 구성으로 돈다.
 */
public enum FxSource {

    /** 유럽중앙은행 고시 환율을 그대로 전달하는 무인증 공개 서비스. */
    FRANKFURTER("유럽중앙은행", false),

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
     * <p>참이면 시각까지("08-12 10:36 기준"), 거짓이면 날짜만("08-11 고시") 보여준다.
     * 하루 한 번 고시하는 값에 분 단위를 붙이면 실제보다 신선해 보인다.
     *
     * <p>지금은 둘 다 거짓이지만 분기를 남겨 둔다 — 실시간 출처를 다시 붙일 수 있고,
     * 그때 표기 규칙을 새로 만들 이유가 없다.
     */
    public boolean intraday() {
        return intraday;
    }
}
