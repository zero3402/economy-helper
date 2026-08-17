package io.saiden.economyhelper.market.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.translate.GeminiApi;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 사용자가 친 말에서 <b>지명과 기간</b>을 함께 뽑아낸다.
 *
 * <p>{@code 내일 서현 날씨}·{@code 2025년 8월 19일 서현 날씨}·{@code 일주일치 날씨}가 전부 같은
 * 문제다 — 군더더기를 걷어내고 무엇을 묻는지 판단하는 일이라 지명과 기간을 갈라 둘 이유가 없다.
 * {@code StockResolver}와 같은 뼈대다.
 *
 * <p><b>여기서 확정하지 않는다.</b> LLM이 준 지명을 {@code GeocodingApi}가 실제로 찾고, 없으면
 * 없는 것이다 — 환각이 구조적으로 걸러진다. <b>좌표를 지어내게 두지 않는 것</b>이 요점이다.
 *
 * <p>이 보정이 필요한 근거가 있다. 지오코딩에 {@code 서현}을 그대로 넣으면 <b>김포시 서현</b>
 * (37.646, 126.605)이 1순위로 나온다 — 분당 서현역이 아니다. LLM이 {@code 성남}으로 옮겨 주면
 * 옳은 좌표로 간다.
 *
 * <p>⚠️ <b>상대 표현을 날짜로 굳히지 않는다.</b> LLM에게 {@code 내일}을 계산시켜 받으면 그 값이
 * 캐시에 7일간 남아 <b>내일이 영영 그 날짜가 된다.</b> 상대는 {@code offsetDays}로 받고 날짜
 * 계산은 {@link WeatherPeriod}가 그날 한다. 절대 날짜는 애초에 낡지 않으므로 그대로 캐시한다 —
 * 이 구분 덕분에 캐시 키가 검색어 하나면 충분하다.
 *
 * <p><b>비용은 캐시가 막는다.</b> 오전 6시 알람은 좌표가 설정에 박혀 있어 이 경로를 아예 타지
 * 않는다 — LLM은 사용자가 직접 칠 때만 불리고 같은 검색어는 7일간 한 번뿐이다.
 */
@Component
public class WeatherResolver {

    private static final Logger log = LoggerFactory.getLogger(WeatherResolver.class);

    private static final String PROMPT = """
            사용자가 날씨를 묻고 있습니다. 아래 입력에서 **어느 지역**의 **언제** 날씨인지 판단하세요.

            규칙:
            - query: 지오코딩(지명 검색)이 찾을 수 있는 **도시·행정구역 이름**으로 옮기세요.
              · 역·건물·동네 이름은 그것이 속한 도시로 바꾸세요. 예) 분당 서현역 → 성남, 강남역 → 서울
              · 해외 지명은 현지 표기나 영문으로 두세요. 예) 파리 → Paris
              · 지역을 안 적었으면 null로 두세요. 추측해서 지어내지 마세요.
            - country: 그 지역이 속한 나라의 ISO 3166-1 alpha-2 코드. 예) 한국 → KR, 프랑스 → FR
              확실하지 않으면 null로 두세요.
            - **날짜를 직접 계산하지 마세요.** 오늘이 며칠인지 모른다고 가정하세요.
              · "내일" → offsetDays: 1 · "모레" → 2 · "어제" → -1 · 안 적었으면 0
              · date는 사용자가 **연·월·일을 직접 적었을 때만** "YYYY-MM-DD"로 채우고,
                그 밖에는 반드시 null로 두세요.
            - days: 며칠치인지 숫자로. 안 적었으면 1입니다.
              · "일주일치" → 7 · "3일치" → 3 · "열흘치" → 10 · "이번 주" → 7 · "주말" → 2
            - "날씨", "알려줘", "어때" 같은 군더더기는 무시하세요.
            - 다른 말 없이 JSON만: {"query": "성남", "country": "KR", "date": null, "offsetDays": 1, "days": 1}

            입력: %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public WeatherResolver(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * @return LLM이 읽은 지명과 기간. 실패하면 {@link Optional#empty()} — 호출자는 원문 그대로
     *         지오코딩을 시도한다({@code 파리}·{@code Tokyo} 같은 평범한 지명은 그걸로 걸린다)
     */
    @Cacheable(cacheNames = "weather-resolve", key = "#a0", unless = "#result == null")
    public Optional<ResolvedPlace> resolve(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        try {
            ResolvedPlace parsed = objectMapper.readValue(
                    api.generate(PROMPT.formatted(normalizedQuery)), ResolvedPlace.class);
            if (parsed == null) {
                log.info("[weather] LLM이 '{}'를 읽지 못했습니다", normalizedQuery);
                return Optional.empty();
            }
            log.info("[weather] '{}' → {} ({}), date={} offset={} days={}", normalizedQuery,
                    parsed.query(), parsed.country(), parsed.date(), parsed.offsetDays(), parsed.days());
            return Optional.of(parsed);
        } catch (Exception e) {
            // 원문 지오코딩으로 내려간다 — 호출자가 판단한다
            log.error("[weather] '{}' LLM 해석 실패: {}", normalizedQuery, e.toString());
            return Optional.empty();
        }
    }

    /** 검색어를 캐시 키로 쓸 수 있게 다듬는다. 접미사는 떼지 않는다 — 그건 LLM이 한다. */
    public static String cacheKeyOf(String query) {
        return QueryNormalizer.normalize(query);
    }

    /**
     * @param query      지오코딩에 넘길 지명. 지역을 못 읽었으면 {@code null}
     * @param country    ISO 3166-1 alpha-2. 같은 지명이 여러 나라에 있을 때 좁힌다
     * @param date       사용자가 연·월·일을 직접 적었을 때만 찬다. 그 밖에는 {@code null}
     * @param offsetDays 오늘로부터 며칠 뒤. <b>절대 날짜로 굳히지 않는 이유가 여기 있다</b>
     * @param days       며칠치. 비어 있으면 하루
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolvedPlace(String query, String country, String date,
                                Integer offsetDays, Integer days) {

        public boolean hasPlace() {
            return query != null && !query.isBlank() && !"null".equalsIgnoreCase(query.trim());
        }

        /**
         * 절대 날짜. LLM이 엉뚱한 문자열을 주면 {@code null}로 떨어뜨린다 —
         * 여기서 던지면 검색 전체가 죽는데, 날짜 하나 때문에 그럴 이유가 없다.
         */
        public LocalDate absoluteDate() {
            if (date == null || date.isBlank() || "null".equalsIgnoreCase(date.trim())) {
                return null;
            }
            try {
                return LocalDate.parse(date.trim());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
