package io.saiden.economyhelper.crypto.adapter.out.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saiden.economyhelper.config.CacheNames;
import io.saiden.economyhelper.crypto.application.port.out.CoinResolver;
import io.saiden.economyhelper.crypto.domain.ResolvedCoin;
import io.saiden.economyhelper.infrastructure.llm.GeminiApi;
import io.saiden.economyhelper.infrastructure.llm.LlmJson;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 업비트에 없는 코인의 <b>티커</b>를 뽑아낸다.
 *
 * <p><b>업비트 이름 매칭이 안 걸릴 때만 불린다</b> — 왜 그 순서인지, 왜 {@code BNB} 같은 코인에만
 * 필요한지는 {@code CryptoService} javadoc에 있다.
 *
 * <p>거기까지 왔을 때의 비용은 캐시가 막는다 — 같은 검색어는 7일간 한 번뿐이고, 아침 브리핑은
 * 마켓 코드가 설정에 박혀 있어 이 경로를 아예 타지 않는다. {@code StockResolver}와 같은
 * {@link GeminiApi}를 써 레이트리미터·서킷브레이커를 공유한다.
 *
 * <p><b>여기서 확정하지 않는다.</b> LLM이 준 티커를 {@code CryptoService}가 두 거래소에서
 * 실제로 조회해 보고, 어느 쪽에도 없으면 없는 것이다 — 환각이 구조적으로 걸러진다.
 */
@Component
public class CryptoResolver implements CoinResolver {

    private static final Logger log = LoggerFactory.getLogger(CryptoResolver.class);

    private static final String PROMPT = """
            사용자가 암호화폐 시세를 묻고 있습니다. 아래 입력이 어떤 코인의 티커인지 판단하세요.

            규칙:
            - symbol은 거래소에서 쓰는 대문자 티커입니다. 예) 비트코인 → BTC, 이더리움 → ETH,
              바이낸스코인·비앤비 → BNB, 솔라나 → SOL
            - 코인이 아니거나 특정할 수 없으면 null을 주세요. 추측해서 지어내지 마세요.
            - "시세", "가격", "얼마", "알려줘" 같은 군더더기는 무시하세요.
            - 법정화폐(USD, KRW 등)는 코인이 아니므로 null입니다.

            JSON만 출력하세요: {"symbol": "..."}

            입력: %s
            """;

    private final GeminiApi api;
    private final ObjectMapper objectMapper;

    public CryptoResolver(GeminiApi api, ObjectMapper objectMapper) {
        this.api = api;
        this.objectMapper = objectMapper;
    }

    /**
     * 거래소 티커의 모양 — 대문자 영숫자 1~20자. 바이낸스 심볼은 여기에 {@code USDT}를 붙여 만든다.
     *
     * <p>모양이 다른 답({@code BTC/USDT}·{@code BTC USDT}·{@code 비트코인}·{@code btc})은 「못 가림」이다. 그대로
     * 보내면 쓰레기 심볼마다 바이낸스 IP 가중치를 쓰고(ADR-0008: 자동 밴), 값이라 7일 캐시에 남는다.
     */
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9]{1,20}");

    /**
     * @return LLM이 판단한 코인. 특정하지 못했거나 답이 티커 모양이 아니면({@link #TICKER}) {@link Optional#empty()} —
     *         빈손은 {@code unless}가 캐시에 안 담고, 업비트 매칭이 이미 빗나간 뒤라 호출자는 「찾지 못했다」로 답한다
     * @throws LlmJson.Failed LLM이 죽었을 때 — 호출자는 「잠시 후 다시」로 답한다. 예외라 캐시에 담기지 않는다
     */
    @Cacheable(cacheNames = CacheNames.CRYPTO_RESOLVE, key = "#a0", unless = "#result == null")
    @Override
    public Optional<ResolvedCoin> resolve(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        // 골격은 LlmJson이 든다 — StockResolver·WeatherResolver와 같은 모양이다
        Optional<ResolvedCoin> resolved = LlmJson.ask(api, objectMapper,
                PROMPT.formatted(normalizedQuery), Parsed.class,
                "crypto", normalizedQuery, Parsed::isTicker)
                .map(parsed -> new ResolvedCoin(parsed.ticker()));
        resolved.ifPresent(coin ->
                log.info("[crypto] '{}' → {}", normalizedQuery, coin.symbol()));
        return resolved;
    }

    /**
     * LLM 응답 그대로. 티커 모양인 것만 {@link ResolvedCoin}으로 넘긴다.
     *
     * @param symbol 대문자 티커({@code BNB})여야 하지만 LLM이 준 그대로다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parsed(String symbol) {

        /**
         * 앞뒤를 다듬은 심볼. ⚠️ <b>{@code "null"} 문자열은 걸러낸다</b>({@link LlmJson#text}).
         * 안 걸러내면 {@code "NULL"}이 티커로 통과해 {@code KRW-NULL}을 <b>바이낸스에</b>
         * 묻는다 — 확실히 쓰레기인 요청을 자동 밴하는 상대에게 보내는 셈이다.
         */
        String ticker() {
            return LlmJson.text(symbol);
        }

        /**
         * 티커 모양인가({@link #TICKER}).
         *
         * <p><b>소문자를 올려 쓰지 않는다.</b> 프롬프트가 대문자 티커를 요구하므로 소문자는 티커가 아니라
         * 자유 글({@code btc or eth}·{@code bitcoin})이 온 신호다 — 올려 쓰면 그 글이 심볼이 돼 바이낸스로 간다.
         */
        boolean isTicker() {
            String ticker = ticker();
            return ticker != null && TICKER.matcher(ticker).matches();
        }
    }
}
