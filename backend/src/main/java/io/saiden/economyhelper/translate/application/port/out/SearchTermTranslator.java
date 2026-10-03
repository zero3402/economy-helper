package io.saiden.economyhelper.translate.application.port.out;

import java.util.List;

/**
 * 한국어 검색어 토큰 → 영어 표현들. 실패는 던진다 — 원문 토큰으로 내려갈지는 부르는 쪽이 정한다.
 */
public interface SearchTermTranslator {

    List<String> toEnglishTerms(String token);
}
