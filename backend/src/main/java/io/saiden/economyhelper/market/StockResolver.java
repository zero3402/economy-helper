package io.saiden.economyhelper.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.translate.GeminiApi;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 사용자가 친 말에서 <b>종목코드와 정식 종목명</b>을 뽑아낸다.
 *
 * <p><b>별칭 표와 접미사 정규식을 버리고 LLM에 맡긴다.</b> 표는 손으로 채워야 하고 새 약칭이
 * 나올 때마다 쫓아다녀야 했다. 게다가 실제로 사고를 냈다 — Spring Boot의 relaxed binding이
 * {@code Map} 키에서 {@code [a-z0-9-]} 밖의 문자를 걸러내 <b>한글 별칭이 전부 조용히 사라졌고</b>,
 * 단위 테스트는 {@code Map}을 직접 넘겨 바인딩을 건너뛰므로 못 잡았다. 표가 없으면 그 버그도 없다.
 *
 * <p>대신 {@code 삼전}·{@code 네이버}·{@code 오늘 삼성전자 주가 알려줘}를 한 번에 처리한다 —
 * 약칭, 통칭(상장명이 {@code NAVER}인 경우), 자연어 군더더기가 전부 같은 문제이기 때문이다.
 *
 * <p><b>비용은 캐시가 막는다.</b> 아침 브리핑은 종목코드로 설정돼 있어 이 경로를 타지 않는다 —
 * LLM은 사용자가 직접 칠 때만 불리고, 같은 검색어는 7일간 한 번뿐이다.
 * {@code RelevanceScorer}와 같은 {@link GeminiApi}를 써 레이트리미터·서킷브레이커를 공유한다.
 *
 * <p><b>여기서 확정하지 않는다.</b> LLM이 준 코드·이름을 {@code StockService}가
 * 실제 시세 API에서 다시 찾는다 — 없으면 없는 것이다. 환각이 구조적으로 걸러진다.
 */
@Component
public class StockResolver {

    private static final Logger log = LoggerFactory.getLogger(StockResolver.class);

    private static final String PROMPT = """
            사용자가 한국 주식 시세를 묻고 있습니다. 아래 입력에서 어떤 종목을 찾는지 판단하세요.

            규칙:
            - **한국거래소(KOSPI·KOSDAQ) 상장 종목만** 다룹니다. 미국 등 해외 종목이면 null을 주세요.
            - 약칭·통칭을 정식 종목으로 옮기세요. 예) 삼전 → 삼성전자, 하닉 → SK하이닉스
            - "주가", "얼마", "알려줘", "오늘" 같은 군더더기는 무시하세요.
            - code는 6자리 종목코드입니다. 확실하지 않으면 code만 null로 두고 name은 채우세요.
            - 종목을 특정할 수 없으면 둘 다 null로 두세요. **추측해서 지어내지 마세요.**
            - 다른 말 없이 JSON만: {"code": "005930", "name": "삼성전자"}

            입력: %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public StockResolver(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * @return LLM이 판단한 종목. 실패하거나 특정하지 못하면 {@link Optional#empty()} —
     *         호출자는 원문으로 이름 검색을 시도한다(LLM이 죽어도 직접 이름은 걸린다)
     */
    @Cacheable(cacheNames = "stock-resolve", key = "#a0", unless = "#result == null")
    public Optional<ResolvedStock> resolve(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        try {
            ResolvedStock parsed =
                    objectMapper.readValue(api.generate(PROMPT.formatted(normalizedQuery)), ResolvedStock.class);
            if (parsed == null || parsed.isEmpty()) {
                log.info("[stock] LLM이 '{}'를 특정하지 못했습니다", normalizedQuery);
                return Optional.empty();
            }
            log.info("[stock] '{}' → {} ({})", normalizedQuery, parsed.name(), parsed.code());
            return Optional.of(parsed);
        } catch (Exception e) {
            // 원문 이름 검색으로 내려간다 — 호출자가 판단한다
            log.error("[stock] '{}' LLM 해석 실패: {}", normalizedQuery, e.toString());
            return Optional.empty();
        }
    }

    /** 검색어를 캐시 키로 쓸 수 있게 다듬는다. 접미사는 떼지 않는다 — 그건 LLM이 한다. */
    public static String cacheKeyOf(String query) {
        return QueryNormalizer.normalize(query);
    }

    /**
     * @param code 6자리 종목코드. LLM이 확신하지 못하면 {@code null}일 수 있다
     * @param name 정식 종목명. code가 빗나갔을 때의 2차 단서다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolvedStock(String code, String name) {

        boolean isEmpty() {
            return blank(code) && blank(name);
        }

        public boolean hasCode() {
            return !blank(code);
        }

        public boolean hasName() {
            return !blank(name);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
        }
    }
}
