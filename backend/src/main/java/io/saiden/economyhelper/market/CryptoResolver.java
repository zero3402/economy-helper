package io.saiden.economyhelper.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.translate.GeminiApi;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 업비트에 없는 코인의 <b>티커</b>를 뽑아낸다.
 *
 * <p><b>업비트가 걸리면 이 클래스는 불리지 않는다.</b> 원화 마켓 283개는 이름이 한글·영문 둘 다
 * 있어 대부분 그대로 걸리고, 남는 모호함은 24시간 거래대금이 가른다({@code CryptoService} 주석의
 * 실측 표). 그 경로는 캐시된 목록에 대한 순수 계산이라 공짜인데, 앞에 LLM을 두면
 * {@code /crypto 비트코인}에도 Gemini가 나간다 — 느려지고 무료 티어만 태운다.
 *
 * <p>필요한 건 <b>업비트에 아예 없는 코인</b>뿐이다. 실측: 원화 마켓 283개에 {@code KRW-BNB}가
 * 없는데 바이낸스에는 {@code BNBUSDT}가 있다. 이때는 후보 목록 자체가 없어 거래대금으로 가릴
 * 대상이 없고, 바이낸스는 심볼만 주고 한글 이름을 주지 않아 {@code 비앤비}를 받을 방법이 없다.
 *
 * <p>거기까지 왔을 때의 비용은 캐시가 막는다 — 같은 검색어는 7일간 한 번뿐이고, 아침 브리핑은
 * 마켓 코드가 설정에 박혀 있어 이 경로를 아예 타지 않는다. {@link StockResolver}와 같은
 * {@link GeminiApi}를 써 레이트리미터·서킷브레이커를 공유한다.
 *
 * <p><b>여기서 확정하지 않는다.</b> LLM이 준 티커를 {@code CryptoService}가 두 거래소에서
 * 실제로 조회해 보고, 어느 쪽에도 없으면 없는 것이다 — 환각이 구조적으로 걸러진다.
 */
@Component
public class CryptoResolver {

    private static final Logger log = LoggerFactory.getLogger(CryptoResolver.class);

    private static final String PROMPT = """
            사용자가 암호화폐 시세를 묻고 있습니다. 아래 입력에서 어떤 코인을 찾는지 판단하세요.

            규칙:
            - symbol은 거래소에서 쓰는 대문자 티커입니다. 예) 비트코인 → BTC, 이더리움 → ETH,
              바이낸스코인·비앤비 → BNB, 솔라나 → SOL
            - name은 한국에서 통용되는 이름입니다. 없으면 티커를 그대로 쓰세요.
            - 코인이 아니거나 특정할 수 없으면 둘 다 null을 주세요.
            - "시세", "가격", "얼마", "알려줘" 같은 군더더기는 무시하세요.
            - 스테이블코인·법정화폐(USD, KRW 등)는 코인이 아니면 null입니다.

            JSON만 출력하세요: {"symbol": "...", "name": "..."}

            입력: %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public CryptoResolver(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * @return LLM이 판단한 코인. 실패하거나 특정하지 못하면 {@link Optional#empty()} —
     *         호출자는 업비트 이름 매칭으로 내려간다
     */
    @Cacheable(cacheNames = "crypto-resolve", key = "#a0", unless = "#result == null")
    public Optional<ResolvedCoin> resolve(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        try {
            ResolvedCoin parsed =
                    objectMapper.readValue(api.generate(PROMPT.formatted(normalizedQuery)), ResolvedCoin.class);
            if (parsed == null || parsed.upperSymbol() == null || parsed.upperSymbol().isBlank()) {
                log.info("[crypto] LLM이 '{}'를 특정하지 못했습니다", normalizedQuery);
                return Optional.empty();
            }
            log.info("[crypto] '{}' → {} ({})", normalizedQuery, parsed.upperSymbol(), parsed.displayName());
            return Optional.of(parsed);
        } catch (Exception e) {
            log.error("[crypto] '{}' LLM 해석 실패: {}", normalizedQuery, e.toString());
            return Optional.empty();
        }
    }

    /** 검색어를 캐시 키로 쓸 수 있게 다듬는다. */
    public static String cacheKeyOf(String query) {
        return QueryNormalizer.normalize(query);
    }

    /**
     * @param symbol 대문자 티커({@code BNB}). 바이낸스 심볼은 여기에 {@code USDT}를 붙여 만든다
     * @param name   화면에 쓸 이름. 없으면 티커를 쓴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolvedCoin(String symbol, String name) {

        public String upperSymbol() {
            return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
        }

        public String displayName() {
            return name == null || name.isBlank() ? upperSymbol() : name;
        }
    }
}
