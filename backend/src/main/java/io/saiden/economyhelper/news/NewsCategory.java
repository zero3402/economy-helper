package io.saiden.economyhelper.news;

import io.saiden.economyhelper.news.rank.KeywordGroup;
import java.util.List;
import java.util.Locale;

/**
 * 기사가 앉을 무리 — <b>코인이냐 경제냐.</b>
 *
 * <p>브리핑이 코인 다섯 건과 경제 다섯 건을 따로 채우므로, 한 기사가 어느 쪽 자리를 쓰는지를
 * 누군가는 정해야 한다. 그 판단이 이 열거형에 있다.
 *
 * <p><b>화면에는 안 나온다.</b> 통 제목은 {@code 뉴스 1/10}로 그대로이고 열 건은 점수순으로
 * 섞여 나간다 — 이 값이 정하는 것은 <b>어느 열 건이 들어오는가</b>이지 순서가 아니다.
 * 그래서 {@code displayName()} 따위가 없다.
 *
 * <p><b>LLM에게 묻지 않는다.</b> Gemini 무료 티어(12회/60초)가 이미 이 파이프라인의 실질
 * 병목이라(매체당 관련도 채점 1회 + 번역 배치 1회) 분류에 호출을 더 얹을 여유가 없다.
 * 코인 기사는 제목이나 요약문에 코인 이름을 거의 반드시 적으므로 글자로 잡힌다.
 *
 * <p><b>이 값을 캐시에 담지 않는다.</b> 피드 캐시({@code feed}, 10분)에는 {@link Article}만
 * 들어가고 무리는 <b>읽을 때</b> 계산된다. 그래서 아래 낱말 목록을 고치는 순간 이미 캐시에
 * 앉아 있는 기사까지 함께 낫는다 — {@code CLAUDE.md}의 「파생된 값을 캐시에 담지 않는다」다.
 * 캐시 판 번호를 올릴 것이 없는 이유도 이것이다.
 */
public enum NewsCategory {

    CRYPTO,
    ECONOMY;

    /**
     * 이 기사는 어느 무리인가 — 규칙 둘을 순서대로 본다.
     *
     * <ol>
     *   <li><b>코인만 싣기로 한 피드에서 왔으면</b> 코인이다({@link NewsSource#cryptoSection()}).
     *       내용을 보지 않는다.</li>
     *   <li>아니면 <b>제목·요약문에 코인 낱말이 있으면</b> 코인이다 — 그 밖은 경제다.</li>
     * </ol>
     *
     * <p><b>둘째 규칙이 없으면 안 된다.</b> Yahoo·CNBC에 실린 코인 기사가 경제 자리를
     * 차지하고, 정작 코인 자리는 코인 피드만으로 채워야 한다. 코인 기사가 금융 일반 피드에
     * 드물게 실린다는 것({@link NewsSource#INVESTING_CRYPTO}의 실측)은 <b>드물다는 뜻이지
     * 없다는 뜻이 아니다.</b>
     */
    public static NewsCategory of(Article article) {
        if (article.source().cryptoSection()) {
            return CRYPTO;
        }
        String haystack = article.text().toLowerCase(Locale.ROOT);
        return TERMS.stream().anyMatch(group -> group.matches(haystack)) ? CRYPTO : ECONOMY;
    }

    /**
     * 코인 낱말 — <b>개념마다 한 묶음이다</b>({@link KeywordGroup}).
     *
     * <p>⚠️ <b>짧은 티커를 넣지 않는다.</b> {@code matches}는 부분 문자열 대조이므로
     * {@code eth}는 {@code whether}·{@code together}에, {@code sol}은 {@code solution}·
     * {@code sold}에, {@code defi}는 <b>{@code deficit}</b>에 걸린다 — 재정적자 기사가
     * 코인 자리를 먹는다. 그래서 이름을 끝까지 적는다({@code ethereum}·{@code solana}).
     * 같은 이유로 {@code ripple}(파급 효과)과 맨 {@code coin}({@code coincide})도 뺐다.
     *
     * <p>{@code defi}·{@code tether}처럼 위험한 낱말이 빠져도 손실이 작다 — 그 주제의 기사는
     * {@code crypto}·{@code blockchain}·{@code stablecoin} 가운데 하나를 거의 반드시 적는다.
     */
    private static final List<KeywordGroup> TERMS = List.of(
            KeywordGroup.of("bitcoin", "btc"),
            KeywordGroup.of("ethereum"),
            // cryptocurrency·crypto-backed·crypto tax를 모두 덮는다
            KeywordGroup.of("crypto"),
            KeywordGroup.of("blockchain", "onchain", "on-chain"),
            KeywordGroup.of("stablecoin", "usdt", "usdc"),
            KeywordGroup.of("altcoin", "memecoin"),
            KeywordGroup.of("solana"),
            KeywordGroup.of("xrp"),
            KeywordGroup.of("nft", "web3"),
            KeywordGroup.of("binance", "coinbase", "upbit"));
}
