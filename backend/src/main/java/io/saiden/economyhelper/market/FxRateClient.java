package io.saiden.economyhelper.market;

/**
 * 환율 출처 하나.
 *
 * <p>구현이 <b>둘</b>이라 인터페이스를 둔다 — 이 프로젝트는 "구현체가 2개 이상인 곳에만
 * 인터페이스를 둔다"를 원칙으로 한다. {@code Translator} → {@code GeminiTranslator} /
 * {@code PassthroughTranslator} + {@code TranslationService}와 같은 모양이다.
 *
 * <p>{@code CLAUDE.md}의 "TOSS 증권 API, 수출입은행 API로 이중화"가 이 인터페이스와
 * {@link FxService}의 체인으로 구현된다.
 *
 * <p><b>실패는 삼키지 않고 던진다.</b> 다음 출처로 넘어갈지는 {@link FxService}가 정한다 —
 * 여기서 빈 값을 돌려주면 "실패"와 "값이 없음"을 구별할 수 없다.
 */
public interface FxRateClient {

    /** 이 클라이언트가 대표하는 출처. 로그와 메시지에 쓴다. */
    FxSource source();

    /**
     * 원/달러 현재 환율.
     *
     * @throws RuntimeException 조회 실패. 서킷브레이커가 이걸 세고, {@link FxService}가 폴백한다
     */
    FxRate usdToKrw();
}
