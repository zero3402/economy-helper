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
 * 그래야 {@code keywordScore}의 분모가 검색어 개수로 유지된다.
 */
@Component
public class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    private final QueryTranslationService translator;

    public QueryExpander(QueryTranslationService translator) {
        this.translator = translator;
    }

    public List<KeywordGroup> expand(String query) {
        // 토큰마다 Gemini 한 번이고 서로를 모른다 — 겹친다. 「비트코인 금리」가 순차면 번역 둘이
        // 줄줄이 2~5초였다. 리미터(12/60초)는 퍼밋 수를 세므로 겹쳐도 소비량은 같고,
        // groupFor가 실패를 원문 토큰으로 삼키므로 하나가 죽어도 나머지가 산다
        return Concurrently.map(tokens(query), this::groupFor);
    }

    /**
     * 토큰 하나 — <b>친 표기</b>와 <b>조사를 뗀 형태</b>.
     *
     * @param surface 번역에 보낸다. 조사를 떼면 낱말 끝이 잘린다(「화웨이」 → 「화웨」, {@code QueryNormalizer.stripParticle})
     * @param stem    한글 기사 부분일치와 같은 낱말 거르기에 쓴다(「금리는 금리가」는 한 개념이다)
     */
    record Token(String surface, String stem) {}

    private KeywordGroup groupFor(Token token) {
        // 영어 검색어는 번역할 게 없다. 무료 티어를 태우지 않고 지연도 붙지 않는다
        if (!hasHangul(token.surface())) {
            return KeywordGroup.of(token.stem());
        }

        try {
            List<String> english = translator.toEnglishTerms(token.surface());
            List<String> terms = new ArrayList<>(english.size() + 1);
            terms.add(token.stem());
            terms.addAll(english);
            return new KeywordGroup(terms);
        } catch (Exception e) {
            // 원문 토큰으로 내려간다. 영문 기사에는 걸리지 않으므로 사용자는 "찾지 못했습니다"를
            // 받는다 — 별도 오류 문구를 만들지 않고 로그로 원인을 남긴다
            log.error("[news] '{}' 검색어 번역 실패 — 원문 토큰으로 검색합니다: {}", token.surface(), FailureReason.of(e));
            return KeywordGroup.of(token.stem());
        }
    }

    /** 한글이 한 자라도 있으면 번역 대상이다. */
    static boolean hasHangul(String text) {
        return text.codePoints()
                .anyMatch(codePoint ->
                        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL);
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
