package io.saiden.economyhelper.llm;

import java.util.function.Predicate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import io.saiden.economyhelper.support.FailureReason;

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
                                      Predicate<T> usable) {
        try {
            T parsed = mapper.readValue(api.generate(prompt), type);
            if (parsed == null || !usable.test(parsed)) {
                log.info("[{}] LLM이 '{}'를 특정하지 못했습니다", tag, query);
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (Exception e) {
            // 호출자가 다음 수를 갖고 있다 — 여기서 던지면 그 폴백이 무의미해진다
            log.error("[{}] '{}' LLM 해석 실패: {}", tag, query, FailureReason.of(e));
            return Optional.empty();
        }
    }
    /**
     * <b>LLM이 준 문자열이 「없음」인가.</b> {@code null}·빈 문자열, 그리고
     * <b>{@code "null"} 리터럴 문자열</b>이 전부 없음이다.
     *
     * <p>⚠️ <b>마지막 것이 이 메서드가 있는 이유다.</b> LLM이 {@code null}을 리터럴
     * {@code "null"}로 주는 일이 <b>실제로 있다.</b> 그리고 그 판단이 해석기 셋에 다섯 벌
     * 흩어져 있던 동안 <b>두 번 물렸다</b> — 둘 다 「나머지는 막고 있는데 이것만 빠져 있었다」다.
     *
     * <ul>
     *   <li>{@code ResolvedCoin.upperSymbol}에 없어서 {@code "NULL"}이 티커로 통과해
     *       {@code KRW-NULL}을 <b>바이낸스에</b> 물었다. 한도가 IP 단위이고 Render는 공용
     *       이그레스라 「우리가 할 수 있는 것은 밴을 늘리지 않는 것뿐」인 상대다.
     *   <li>{@code ResolvedPlace.countryCode}에 없어서 {@code countryCode=null}이 쿼리에
     *       실려 나가 헛호출을 태운 뒤 원문으로 폴백했다 — 지오코딩 조회가 조용히 두 배다.
     * </ul>
     *
     * <p>같은 판단이 여러 곳에 있으면 하나만 고쳐지는 날이 온다 — 이 클래스가 해석기 골격을
     * 가져온 이유와 같은 자리이고, 이제 그 한 단 아래까지 내려온 것이다.
     */
    public static boolean blank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }

    /**
     * 쓸 만하면 <b>앞뒤를 다듬어</b> 돌려주고, 아니면 {@code null}.
     *
     * <p>{@link #blank}가 boolean으로 답하는 그 판단을 값으로 답한다 — 호출부가
     * {@code blank(x) ? null : x.trim()}을 되풀이하지 않게 한다.
     */
    public static String text(String value) {
        return blank(value) ? null : value.trim();
    }
}
