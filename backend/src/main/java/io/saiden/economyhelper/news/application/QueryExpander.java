package io.saiden.economyhelper.news.application;

import io.saiden.economyhelper.news.domain.KeywordGroup;
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
 * <p><b>토큰 하나가 개념 하나다.</b> 번역은 한 번에 묶어 묻지만 답은 토큰별로 받는다 — 한 문장으로
 * 옮기면 결과 단어들을 원래 토큰에 되붙일 수 없어 개념 경계가 무너진다. {@code 비트코인 금리}는 두 개념이므로
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
        List<Token> tokens = tokens(query);
        // 영어 검색어는 번역할 게 없다 — 한글 토큰만 모아 한 번에 묻는다(무료 티어를 태우지 않고 지연도 붙지 않는다)
        List<String> korean = tokens.stream()
                .map(Token::surface)
                .filter(QueryNormalizer::hasHangul)
                .toList();
        Map<String, List<String>> english = korean.isEmpty() ? Map.of() : translator.toEnglishTerms(korean);

        List<Grouped> grouped = tokens.stream().map(token -> groupFor(token, english)).toList();
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

    /**
     * @param english 한글 토큰 → 영어 표현들. <b>키가 없는 한글 토큰은 번역이 빠진 것이다</b> — 원문 토큰으로
     *                내려간다. 한글 기사에는 그대로 걸리지만 영문 기사에는 안 걸리므로 그 사실을 함께 넘긴다:
     *                그래서 한 건도 못 찾으면 「찾지 못했다」가 아니라 「잠시 후 다시」다({@code NewsFacade.search})
     */
    private static Grouped groupFor(Token token, Map<String, List<String>> english) {
        if (!QueryNormalizer.hasHangul(token.surface())) {
            return new Grouped(KeywordGroup.of(token.stem()), false);
        }
        List<String> translated = english.get(token.surface());
        if (translated == null) {
            return new Grouped(KeywordGroup.of(token.stem()), true);
        }
        List<String> terms = new ArrayList<>(translated.size() + 1);
        terms.add(token.stem());
        terms.addAll(translated);
        return new Grouped(new KeywordGroup(terms), false);
    }

    /**
     * 검색어 하나에서 쓰는 토큰 수의 상한. 수백 낱말을 붙여 넣으면 번역 프롬프트가 그만큼 길어지고, 묶음이
     * 늘수록 엉뚱한 기사가 걸린다. 다섯이면 사람이 치는 검색어는 다 들어간다.
     */
    static final int MAX_TOKENS = 5;

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
