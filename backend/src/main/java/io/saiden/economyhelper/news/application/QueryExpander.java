package io.saiden.economyhelper.news.application;

import io.saiden.economyhelper.news.domain.KeywordGroup;
import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.shared.support.QueryNormalizer;
import io.saiden.economyhelper.translate.application.QueryTranslationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 검색어를 {@link KeywordGroup} 목록으로 만든다 — 토큰화·한글 판별·번역·폴백을 한 군데 모았다.
 *
 * <p><b>토큰 하나가 개념 하나다.</b> 검색어 전체를 한 번에 번역하면 결과 단어들을 원래 토큰에
 * 되붙일 수 없어 개념 경계가 무너진다. {@code 비트코인 금리}는 두 개념이므로
 * {@code [비트코인, bitcoin, btc]}와 {@code [금리, interest rate, rates]} 두 묶음이 된다.
 * 그래야 {@code PopularityScorer}의 keywordMatch 분모가 검색어 개수로 유지된다.
 */
@Component
public class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    private final QueryTranslationService translator;

    public QueryExpander(QueryTranslationService translator) {
        this.translator = translator;
    }

    public Expansion expand(String query) {
        // 토큰마다 Gemini 한 번이고 서로를 모른다 — 겹친다. 「비트코인 금리」가 순차면 번역 둘이
        // 줄줄이 2~5초였다. 리미터(12/60초)는 퍼밋 수를 세므로 겹쳐도 소비량은 같고,
        // groupFor가 실패를 원문 토큰으로 내려 주므로 하나가 죽어도 나머지가 산다
        List<Grouped> grouped = Concurrently.map(tokens(query), this::groupFor);
        return new Expansion(grouped.stream().map(Grouped::group).toList(),
                grouped.stream().anyMatch(Grouped::untranslated));
    }

    /**
     * 확장 결과.
     *
     * @param groups       토큰마다 한 묶음
     * @param untranslated 번역이 <b>고장으로</b> 빠진 한글 토큰이 있다 — 그 토큰은 원문으로만 찾으므로 영문 기사에
     *                     안 걸린다. 그래서 못 찾았을 때 「찾지 못했다」라 단정할 수 없다({@code NewsFacade.search})
     */
    public record Expansion(List<KeywordGroup> groups, boolean untranslated) {

        public Expansion {
            groups = List.copyOf(groups);
        }
    }

    /** 토큰 하나의 묶음과, 번역이 고장으로 빠졌는지. */
    private record Grouped(KeywordGroup group, boolean untranslated) {}

    /**
     * 토큰 하나 — <b>친 표기</b>와 <b>조사를 뗀 형태</b>.
     *
     * @param surface 번역에 보낸다. 조사를 떼면 낱말 끝이 잘린다(「화웨이」 → 「화웨」, {@code QueryNormalizer.stripParticle})
     * @param stem    한글 기사 부분일치와 같은 낱말 거르기에 쓴다(「금리는 금리가」는 한 개념이다)
     */
    record Token(String surface, String stem) {}

    private Grouped groupFor(Token token) {
        // 영어 검색어는 번역할 게 없다. 무료 티어를 태우지 않고 지연도 붙지 않는다
        if (!QueryNormalizer.hasHangul(token.surface())) {
            return new Grouped(KeywordGroup.of(token.stem()), false);
        }

        try {
            List<String> english = translator.toEnglishTerms(token.surface());
            List<String> terms = new ArrayList<>(english.size() + 1);
            terms.add(token.stem());
            terms.addAll(english);
            return new Grouped(new KeywordGroup(terms), false);
        } catch (Exception e) {
            // 원문 토큰으로 내려간다 — 한글 기사에는 그대로 걸린다. 영문 기사에는 안 걸리므로 그 사실을
            // 함께 넘긴다: 그래서 한 건도 못 찾으면 「찾지 못했다」가 아니라 「잠시 후 다시」다(NewsFacade)
            log.error("[news] '{}' 검색어 번역 실패 — 원문 토큰으로 검색합니다: {}", token.surface(), FailureReason.of(e));
            return new Grouped(KeywordGroup.of(token.stem()), true);
        }
    }

    /**
     * 검색어 하나에서 번역할 토큰 수의 상한. 토큰마다 Gemini 한 번이고 리미터는 분당 12회를 온 앱이 나눠 쓴다 —
     * 수백 낱말을 붙여 넣으면 그 한 건이 관련도·번역 몫까지 다 먹고 브리핑까지 「모두 통과」로 밀린다.
     * 다섯이면 사람이 치는 검색어는 다 들어간다.
     */
    static final int MAX_TOKENS = 5;

    /** {@link #tokens}의 조사를 뗀 형태만 — 테스트가 토큰화 규칙을 본다. */
    static List<String> tokenize(String query) {
        return tokens(query).stream().map(Token::stem).toList();
    }

    /**
     * 토큰들 — 조사를 뗀 형태({@code stem})로 겹침을 거른다: {@code /news 금리는 금리가}는 한 개념이라 한 번만 번역된다.
     * 다섯을 넘으면 앞의 다섯만 쓴다({@link #MAX_TOKENS}).
     */
    static List<Token> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Token> byStem = new LinkedHashMap<>();
        for (String raw : query.trim().split("\\s+")) {
            String surface = QueryNormalizer.searchSurface(raw);
            String stem = QueryNormalizer.stripParticle(surface);
            if (!stem.isBlank()) {
                byStem.putIfAbsent(stem, new Token(surface, stem));
            }
        }
        List<Token> tokens = List.copyOf(byStem.values());
        if (tokens.size() > MAX_TOKENS) {
            log.info("[news] 검색어 토큰이 {}개라 앞의 {}개만 씁니다", tokens.size(), MAX_TOKENS);
            return tokens.subList(0, MAX_TOKENS);
        }
        return tokens;
    }
}
