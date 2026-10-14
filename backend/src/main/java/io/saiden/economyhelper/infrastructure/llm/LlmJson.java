package io.saiden.economyhelper.infrastructure.llm;

import io.saiden.economyhelper.shared.support.FailureReason;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>프롬프트를 던지고 JSON을 받아 온다 — 못 특정하면 빈손, 고장이면 {@link Failed}.</b>
 *
 * <p>해석기 셋({@code StockResolver}·{@code CryptoResolver}·{@code WeatherResolver})이 이 골격을
 * 함께 쓴다 — 셋에 흩어져 있던 동안 한 곳만 내용을 안 보고 {@code null}만 봐서, 아무것도 안 든
 * 결과가 "성공"으로 7일 캐시됐다.
 *
 * <p>LLM은 <b>해석</b>만 하고 <b>확정</b>하지 않으므로({@code docs/design.md} 4.6), 죽어도 기능이 멈추지
 * 않아야 한다 — 호출자마다 그 다음 수가 있다: 종목은 원문 이름으로, 날씨는 원문 지오코딩으로. 다만
 * <b>고장을 빈손으로 삼키지 않는다.</b> 둘이 같으면 다음 수까지 빈손일 때 「찾지 못했다」가 나가는데,
 * LLM이 살아 있었다면 찾았을 수 있다 — 그때는 「잠시 후 다시」다(실물 감사 2026-10-14).
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
     * @return 쓸 만한 값. LLM이 읽었는데 비었으면 {@link Optional#empty()} — 「특정하지 못했다」
     * @throws Failed 호출이나 응답 읽기가 실패했을 때 — 「고장」이다. 빈손으로 주면 호출자의 다음 수까지
     *                빈손일 때 「찾지 못했다」가 나간다(실물 감사 2026-10-14: Gemini 503·타임아웃 중의
     *                {@code /weather 미금}·{@code /stock 네이버})
     */
    public static <T> Optional<T> ask(GeminiApi api, ObjectMapper mapper, String prompt,
                                      Class<T> type, String tag, String query,
                                      Predicate<T> usable) {
        String reply = null;
        T parsed;
        boolean readable;
        try {
            reply = api.generate(prompt);
            parsed = mapper.readValue(unwrapSingle(mapper, reply), type);
            // usable도 여기 안이다 — 모양이 어긋난 값에 그 판정이 던지는 것도 못 읽은 것이다
            readable = parsed != null && usable.test(parsed);
        } catch (Exception e) {
            // 받은 글의 **모양**만 남긴다 — 한 번씩 다른 모양이 오는데(2026-09-29 '다음 주말 부산') 예외
            // 이름만으로는 무엇이 왔는지 알 길이 없다. ⚠️ 본문은 안 적는다 — 모델이 사용자 입력을 되풀이할 수 있다
            log.error("[{}] '{}' LLM 해석 실패: {} — 받은 글: {}", tag, query, FailureReason.of(e),
                    shapeOf(mapper, reply));
            // 호출자가 다음 수(원문 검색)를 갖고 있다 — 그 수도 빈손이면 「잠시 후 다시」로 답하려면 이것을 알아야 한다
            throw new Failed("[" + tag + "] LLM 해석 실패", e);
        }
        if (!readable) {
            log.info("[{}] LLM이 '{}'를 특정하지 못했습니다", tag, query);
            return Optional.empty();
        }
        return Optional.of(parsed);
    }

    /**
     * 객체 하나를 배열로 감싸 온 답({@code [{…}]})을 벗긴다 — Gemini가 가끔 그렇게 준다.
     * 둘 이상이면 어느 것인지 모르므로 그대로 두어 파싱이 실패하게 한다(고르지 않는다).
     */
    private static String unwrapSingle(ObjectMapper mapper, String reply) {
        if (reply == null || !reply.stripLeading().startsWith("[")) {
            return reply;
        }
        JsonNode root = mapper.readTree(reply);
        return root.isArray() && root.size() == 1 && root.get(0).isObject() ? root.get(0).toString() : reply;
    }

    /** 받은 글의 모양 — 길이와 최상위 JSON 종류, 객체면 필드 <b>개수</b>. 값도 키 이름도 안 적는다(둘 다 모델이 낸 글이다). */
    static String shapeOf(ObjectMapper mapper, String reply) {
        if (reply == null) {
            return "(없음)";
        }
        String kind;
        try {
            JsonNode root = mapper.readTree(reply);
            if (root.isObject()) {
                kind = "object(필드 " + root.size() + "개)";
            } else if (root.isArray()) {
                kind = "array(" + root.size() + ")";
            } else {
                kind = root.getNodeType().toString().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException notJson) {
            kind = "JSON 아님";
        }
        return reply.length() + "자, " + kind;
    }

    /**
     * <b>LLM이 준 문자열이 「없음」인가.</b> {@code null}·빈 문자열, 그리고
     * <b>{@code "null"} 리터럴 문자열</b>이 전부 없음이다.
     *
     * <p>⚠️ <b>마지막 것이 이 메서드가 있는 이유다.</b> LLM이 {@code null}을 리터럴
     * {@code "null"}로 주는 일이 <b>실제로 있다.</b> 빠뜨리면 이렇게 된다:
     *
     * <ul>
     *   <li>코인: {@code "NULL"}이 티커로 통과해 {@code KRW-NULL}을 <b>바이낸스에</b> 묻는다 —
     *       한도가 IP 단위라 밴을 늘리는 쪽이다.
     *   <li>날씨: {@code countryCode=null}이 쿼리에 실려 헛호출을 태운 뒤 원문으로 폴백한다 —
     *       지오코딩 조회가 조용히 두 배다.
     * </ul>
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

    /**
     * <b>LLM이 고장 났다</b> — 「특정하지 못했다」(빈손)가 아니다.
     *
     * <p>호출이나 응답 읽기가 실패했다(타임아웃·5xx·브레이커·리미터·못 읽는 글). 호출자는 원문으로 다음 수를
     * 두되, 그 수도 빈손이면 「찾지 못했다」가 아니라 「잠시 후 다시」로 답한다 — LLM이 살아 있었다면
     * 찾았을 수 있다. 예외이므로 해석기의 {@code @Cacheable}이 담지 않는다.
     */
    public static final class Failed extends RuntimeException {

        Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
