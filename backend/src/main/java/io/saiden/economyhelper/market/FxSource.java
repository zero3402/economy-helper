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
 * <p>셋 모두 IP 등록을 요구하지 않아 로컬과 배포가 같은 구성으로 돈다.
 *
 * <p><b>선언 순서가 곧 이중화 순서다</b>({@code FxService.ORDER}) — 신선한 것이 앞이다.
 */
public enum FxSource {

    /**
     * 1순위. <b>셋 중 유일하게 하루 중에도 움직인다.</b>
     *
     * <p>실측에서 오늘 봉의 고가·저가가 형성 중이었다 — 하루 한 번 고시가 아니다. 대신 앱키가
     * 필요하고 초당 한도가 있어, 받쳐 주는 자리는 키 없는 쪽에 맡긴다.
     */
    KIS("한국투자증권", true),

    /**
     * 2순위. 유럽중앙은행 고시를 그대로 전달하는 무인증 공개 서비스.
     *
     * <p><b>수출입은행보다 앞이다.</b> 오전 9시 브리핑 시각에 받을 수 있는 가장 최근 값이
     * 어제 16시 CET(≈ 어제 23시 KST)로, 수출입은행의 어제 11시 고시보다 신선하다. 키도 한도도
     * 없어 받쳐 주는 자리에 맞다.
     */
    FRANKFURTER("유럽중앙은행", false),

    /**
     * 3순위. 원/달러의 한국 공식 고시라 최후 보루로 남긴다.
     *
     * <p>앞이 성공하면 호출되지 않으므로 남기는 비용이 없다. 대신 하루 1,000회 한도가 있고
     * 비영업일·11시 이전에는 비어 온다.
     */
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
     * <p>{@link #KIS}가 이 분기를 쓴다. 실시간 출처를 다시 붙일 수 있다며 남겨 둔 자리에
     * 실제로 들어온 것이다.
     */
    public boolean intraday() {
        return intraday;
    }
}
