package io.saiden.economyhelper.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.llm.GeminiApi;
import io.saiden.economyhelper.llm.LlmJson;
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
            사용자가 주식 시세를 묻고 있습니다. 아래 입력에서 어떤 종목을 찾는지 판단하세요.

            규칙:
            - **한국거래소(KOSPI·KOSDAQ)와 미국 거래소(NASDAQ·NYSE)만** 다룹니다.
              그 밖의 시장이면 전부 null을 주세요.
            - market은 한국이면 "KR", 미국이면 "US"입니다.
            - 지수를 물으면 kind를 "INDEX"로 하세요.
              · KR 지수: name에 정식 지수명(코스피, 코스닥, 코스피 200), code는 null
              · US 지수: code에 심볼(나스닥 → ^IXIC, S&P500 → ^GSPC, 다우 → ^DJI)
            - 개별 종목이면 kind를 "STOCK"으로 하세요.
              · KR: code는 6자리 종목코드 (삼성전자 → 005930)
              · US: code는 티커 (애플 → AAPL, 엔비디아 → NVDA, 테슬라 → TSLA)
            - 약칭·통칭을 정식 종목으로 옮기세요. 예) 삼전 → 삼성전자, 하닉 → SK하이닉스
            - "주가", "얼마", "알려줘", "오늘" 같은 군더더기는 무시하세요.
            - 확실하지 않으면 code만 null로 두고 name은 채우세요(KR 한정 — US는 code가 있어야 찾습니다).
            - 종목을 특정할 수 없으면 전부 null로 두세요. **추측해서 지어내지 마세요.**
            - 다른 말 없이 JSON만: {"market": "KR", "kind": "STOCK", "code": "005930", "name": "삼성전자"}

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
        // 골격은 LlmJson이 든다 — 셋이 글자까지 똑같았고, 그 탓에 한 곳만 고쳐진 적이 있다.
        // 실패하면 호출자가 원문 이름 검색으로 내려간다
        Optional<ResolvedStock> resolved = LlmJson.ask(api, objectMapper,
                PROMPT.formatted(normalizedQuery), ResolvedStock.class,
                "stock", normalizedQuery, parsed -> !parsed.isEmpty());
        resolved.ifPresent(parsed -> log.info("[stock] '{}' → {} ({}, {} {})", normalizedQuery,
                parsed.name(), parsed.code() == null ? "코드없음" : parsed.code(),
                parsed.isUs() ? "US" : "KR", parsed.kind()));
        return resolved;
    }

    /** 검색어를 캐시 키로 쓸 수 있게 다듬는다. 접미사는 떼지 않는다 — 그건 LLM이 한다. */
    public static String cacheKeyOf(String query) {
        return QueryNormalizer.normalize(query);
    }

    /**
     * @param market {@code "US"}면 FMP, 그 외(기본 KR)는 공공데이터포털. <b>벤더가 갈리는 축</b>이다
     * @param kind {@code "INDEX"}면 지수, 그 외는 개별 종목. 조회할 API가 갈린다
     * @param code 종목코드·티커. 국내 지수이거나 LLM이 확신하지 못하면 {@code null}일 수 있다
     * @param name 정식 종목명·지수명. code가 빗나갔을 때의 2차 단서이자 국내 지수의 유일한 단서다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolvedStock(String market, String kind, String code, String name) {

        private static final String INDEX = "INDEX";
        private static final String US = "US";

        /** 지수는 종목코드가 없어 이름으로만 찾는다 — 조회 경로가 통째로 다르다. */
        public boolean isIndex() {
            return INDEX.equalsIgnoreCase(kind);
        }

        /**
         * 미국 종목·지수인가.
         *
         * <p>비어 있으면 국내로 본다 — 이 봇은 한국어로 쓰이고 국내가 기본이다.
         * LLM이 market을 빠뜨려도 기존 국내 경로로 안전하게 떨어진다.
         */
        public boolean isUs() {
            return US.equalsIgnoreCase(market);
        }

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
