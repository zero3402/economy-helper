package io.saiden.economyhelper.news.rank;

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
 * <p>정기 발송의 재테크 사전은 항목마다 표현이 하나뿐이라 1항목 묶음이 된다 — 같은 타입으로
 * 검색과 발송을 함께 다룬다.
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
        return terms.stream().anyMatch(lowercasedText::contains);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }
}
