package io.saiden.economyhelper.translate.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * 한국어 검색어 토큰들 → 토큰마다의 영어 표현들. <b>한 번의 호출로 묶어 옮긴다.</b>
 *
 * <p>호출·읽기 실패는 던진다 — 원문 토큰으로 내려갈지는 부르는 쪽이 정한다.
 */
public interface SearchTermTranslator {

    /**
     * @param tokens 옮길 토큰들. 비어 있으면 부르지 않는다
     * @return 토큰 → 영어 표현들. <b>못 옮긴 토큰은 키가 없다</b>(빈 목록으로 담지 않는다)
     */
    Map<String, List<String>> toEnglishTerms(List<String> tokens);
}
