package io.saiden.economyhelper.news;

import io.saiden.economyhelper.news.rank.KeywordGroup;
import io.saiden.economyhelper.text.QueryNormalizer;
import io.saiden.economyhelper.translate.QueryTranslator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    private final QueryTranslator translator;

    public QueryExpander(QueryTranslator translator) {
        this.translator = translator;
    }

    public List<KeywordGroup> expand(String query) {
        List<KeywordGroup> groups = new ArrayList<>();
        for (String token : tokenize(query)) {
            groups.add(groupFor(token));
        }
        return List.copyOf(groups);
    }

    private KeywordGroup groupFor(String token) {
        // 영어 검색어는 번역할 게 없다. 무료 티어를 태우지 않고 지연도 붙지 않는다
        if (!hasHangul(token)) {
            return KeywordGroup.of(token);
        }

        try {
            List<String> english = translator.toEnglishTerms(token);
            List<String> terms = new ArrayList<>(english.size() + 1);
            terms.add(token);
            terms.addAll(english);
            return new KeywordGroup(terms);
        } catch (Exception e) {
            // 원문 토큰으로 내려간다. 영문 기사에는 걸리지 않으므로 사용자는 "찾지 못했습니다"를
            // 받는다 — 별도 오류 문구를 만들지 않고 로그로 원인을 남긴다
            log.error("[검색] '{}' 검색어 번역 실패 — 원문 토큰으로 검색합니다: {}", token, e.toString());
            return KeywordGroup.of(token);
        }
    }

    /** 한글이 한 자라도 있으면 번역 대상이다. */
    static boolean hasHangul(String text) {
        return text.codePoints()
                .anyMatch(codePoint ->
                        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL);
    }

    /**
     * <p>토큰마다 {@link QueryNormalizer#forSearchToken}을 태우는 이유는 <b>캐시 적중률</b>이다.
     * {@code 금리}·{@code 금리는}·{@code 금리가}가 각각 다른 키로 캐시되면 같은 개념에
     * Gemini를 세 번 태운다 — 무료 티어에서 그냥 버리는 호출이다.
     * 조사를 뗀 뒤 {@code distinct}가 걸리므로 {@code /news 금리는 금리가}도 한 번만 번역된다.
     */
    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.trim().split("\\s+"))
                .map(QueryNormalizer::forSearchToken)
                .filter(token -> !token.isBlank())
                .distinct()
                .toList();
    }
}
