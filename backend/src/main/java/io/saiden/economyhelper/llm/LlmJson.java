package io.saiden.economyhelper.llm;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>프롬프트를 던지고 JSON을 받아 온다 — 실패하면 빈손이다.</b>
 *
 * <p>해석기 셋({@code StockResolver}·{@code CryptoResolver}·{@code WeatherResolver})이 이 골격을
 * 글자까지 똑같이 갖고 있었다. 같은 판단이 세 곳에 있으면 하나만 고쳐지는 날이 오는데,
 * <b>실제로 그랬다</b> — {@code WeatherResolver}만 파싱 결과의 내용을 안 보고 {@code null}만 봐서,
 * 아무것도 안 든 결과가 "성공"으로 7일 캐시됐다.
 *
 * <p><b>{@code Exception}을 전부 삼킨다.</b> {@code ARCHITECTURE.md} 4-5가 정한 계약이 그것이다 —
 * LLM은 <b>해석</b>만 하고 <b>확정</b>하지 않으므로, 죽으면 검색 품질이 내려갈 뿐 기능이 멈추지
 * 않아야 한다. 호출자마다 그 다음 수가 있다: 종목은 이름 검색으로, 날씨는 원문 지오코딩으로.
 *
 * <p><b>정적 유틸이다.</b> 빈으로 만들어 주입하면 해석기 셋의 생성자 모양이 바뀌고, 그러면
 * 익명 하위 클래스로 {@code GeminiApi}를 스텁하는 테스트들이 전부 깨진다 — 얻는 것 없이.
 */
public final class LlmJson {

    private static final Logger log = LoggerFactory.getLogger(LlmJson.class);

    private LlmJson() {
    }

    /**
     * @param tag     로그 앞머리({@code stock}·{@code crypto}·{@code weather}). 도메인마다 달라야
     *                어느 해석이 실패했는지 로그에서 갈린다
     * @param usable  파싱된 값이 쓸 만한가. <b>{@code null}만 보는 것으로는 부족하다</b> —
     *                LLM은 모든 필드가 {@code null}인 JSON도 준다
     * @return 쓸 만한 값. 비었거나 못 읽었거나 상대가 죽었으면 {@link Optional#empty()}
     */
    public static <T> Optional<T> ask(GeminiApi api, ObjectMapper mapper, String prompt,
                                      Class<T> type, String tag, String query,
                                      java.util.function.Predicate<T> usable) {
        try {
            T parsed = mapper.readValue(api.generate(prompt), type);
            if (parsed == null || !usable.test(parsed)) {
                log.info("[{}] LLM이 '{}'를 특정하지 못했습니다", tag, query);
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (Exception e) {
            // 호출자가 다음 수를 갖고 있다 — 여기서 던지면 그 폴백이 무의미해진다
            log.error("[{}] '{}' LLM 해석 실패: {}", tag, query, e.toString());
            return Optional.empty();
        }
    }
}
