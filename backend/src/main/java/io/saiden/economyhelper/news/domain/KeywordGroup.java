package io.saiden.economyhelper.news.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 같은 개념을 가리키는 표현들의 묶음.
 *
 * <p>검색어 {@code 비트코인}은 {@code [비트코인, bitcoin, btc]}로 확장된다. 이 셋을 각각 별개
 * 키워드로 세면 {@code bitcoin} 하나만 걸려도 "셋 중 하나"가 되어 점수가 희석된다.
 * 묶음은 <b>개념 하나</b>이므로 안에서 하나라도 걸리면 그 개념이 걸린 것으로 센다.
 *
 * <p>{@link NewsCategory}의 코인 낱말 목록도 같은 타입이다.
 */
public record KeywordGroup(List<String> terms) {

    public KeywordGroup {
        terms = terms == null ? List.of() : terms.stream()
                .filter(term -> term != null && !term.isBlank())
                // 기사 본문도 소문자로 맞춰 비교하므로 여기서 한 번만 정규화한다
                .map(term -> term.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** {@code null}이 섞여도 생성자가 걸러낸다. */
    public static KeywordGroup of(String... terms) {
        return new KeywordGroup(Arrays.asList(terms));
    }

    /**
     * @param lowercasedText 이미 소문자로 맞춘 검사 대상 — 묶음마다 다시 소문자화하지 않으려고
     *                       호출자가 미리 처리한다
     */
    public boolean matches(String lowercasedText) {
        return terms.stream().anyMatch(term -> occursIn(lowercasedText, term));
    }

    /** 이 길이 이하의 영문 낱말은 낱말 전체가 맞아야 한다 — {@code us}·{@code ai}·{@code fed}. */
    private static final int WHOLE_WORD_MAX = 3;

    /**
     * 낱말이 글 안에 있는가.
     *
     * <p>⚠️ <b>한글은 부분일치, 영문은 낱말 경계부터.</b> 영문을 부분일치로 보면 {@code rate}가 {@code corporate}에,
     * {@code ai}가 {@code said}에, {@code us}가 {@code business}에 걸려 거의 모든 기사가 맞는다 — 그러면 관련도
     * 검사(상위 몇 건만 LLM에 보낸다)에 무관한 기사만 올라가 「찾지 못했습니다」가 나간다. 그래서 영문 낱말은
     * <b>낱말 머리에서 시작</b>해야 하고, 세 글자 이하는 <b>낱말 끝까지</b> 맞아야 한다(긴 낱말은 {@code rates}처럼
     * 굴절을 받는다). 한글은 조사가 붙어 있어(「금리가」) 경계를 볼 수 없다.
     */
    static boolean occursIn(String text, String term) {
        if (term.codePoints().anyMatch(KeywordGroup::isHangul)) {
            return text.contains(term);
        }
        boolean wholeWord = term.length() <= WHOLE_WORD_MAX;
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + 1)) {
            boolean startsWord = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + term.length();
            boolean endsWord = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startsWord && (!wholeWord || endsWord)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHangul(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL;
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }
}
