package io.saiden.economyhelper.translate.application;

import io.saiden.economyhelper.translate.application.port.out.SearchTermTranslator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 검색어 번역의 공개 창구 — 다른 컨텍스트(뉴스)는 번역기 포트가 아니라 이 서비스를 부른다.
 *
 * <p>강등하지 않는다. 실패는 그대로 올라가고, 원문 토큰으로 내려가는 판단은 뉴스의
 * {@code QueryExpander}가 한다 — 검색에서 무엇이 「못 찾음」인지는 그쪽이 안다.
 */
@Service
public class QueryTranslationService {

    private final SearchTermTranslator translator;

    public QueryTranslationService(SearchTermTranslator translator) {
        this.translator = translator;
    }

    /** @return 영어 표현들. 캐시는 번역기 구현이 토큰 단위로 든다 */
    public List<String> toEnglishTerms(String token) {
        return translator.toEnglishTerms(token);
    }
}
