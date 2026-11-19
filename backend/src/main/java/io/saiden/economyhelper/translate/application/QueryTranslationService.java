package io.saiden.economyhelper.translate.application;

import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.translate.application.port.out.SearchTermCache;
import io.saiden.economyhelper.translate.application.port.out.SearchTermTranslator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 검색어 번역의 공개 창구 — 다른 컨텍스트(뉴스)는 번역기 포트가 아니라 이 서비스를 부른다.
 *
 * <p><b>캐시에 없는 토큰만 묶어 번역기를 한 번 부른다</b> — {@code TranslationService.translateAll}과 같은 방식이다.
 * 토큰 단위 캐시({@code 금리}·{@code 환율}은 여러 검색어에 걸쳐 되풀이된다)를 지키면서, 한 검색어가
 * Gemini 한도(60초에 12회)를 토큰 수만큼 쓰지 않게 한다.
 *
 * <p>던지지 않는다. 못 옮긴 토큰은 <b>결과에 키가 없고</b>, 원문 토큰으로 내려가는 판단은 뉴스의
 * {@code QueryExpander}가 한다 — 검색에서 무엇이 「못 찾음」인지는 그쪽이 안다.
 */
@Service
public class QueryTranslationService {

    private static final Logger log = LoggerFactory.getLogger(QueryTranslationService.class);

    private final SearchTermTranslator translator;
    private final SearchTermCache cache;

    public QueryTranslationService(SearchTermTranslator translator, SearchTermCache cache) {
        this.translator = translator;
        this.cache = cache;
    }

    /**
     * @return 토큰 → 영어 표현들. <b>키가 없는 토큰은 번역이 빠진 것</b>이다(고장이든 모델이 못 옮겼든).
     *         번역기가 실패해도 캐시에서 건진 것은 그대로 남는다
     */
    public Map<String, List<String>> toEnglishTerms(List<String> tokens) {
        List<String> asked = tokens.stream().distinct().toList();
        Map<String, List<String>> known = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        // 읽기 실패를 미스로 바꾸는 것은 캐시 어댑터(SpringSearchTermCache)의 몫이다
        List<Optional<List<String>>> cached = Concurrently.map(asked, cache::find);
        for (int i = 0; i < asked.size(); i++) {
            String token = asked.get(i);
            cached.get(i).ifPresentOrElse(hit -> known.put(token, hit), () -> misses.add(token));
        }
        if (!misses.isEmpty()) {
            known.putAll(translateMisses(misses));
        }
        return Map.copyOf(known);
    }

    /**
     * 미스를 한 번에 묻고, <b>돌아온 것만</b> 캐시에 넣는다.
     *
     * <p>못 옮긴 토큰을 빈 값으로 담지 않는다 — 일시적 실패가 30일 굳으면 그 검색어는 그 기간 내내 영문 기사에 안 걸린다.
     */
    private Map<String, List<String>> translateMisses(List<String> misses) {
        Map<String, List<String>> answered;
        try {
            answered = translator.toEnglishTerms(misses);
        } catch (Exception e) {
            log.error("[translate] 검색어 {} 번역 실패 — 원문 토큰으로 검색합니다: {}", misses, FailureReason.of(e));
            return Map.of();
        }
        Map<String, List<String>> fresh = new LinkedHashMap<>();
        // 물은 토큰의 답만 받는다 — 번역기가 다른 키를 돌려줘도 캐시에 넣지 않는다
        for (String token : misses) {
            List<String> terms = answered.get(token);
            if (terms != null && !terms.isEmpty()) {
                fresh.put(token, terms);
                cache.put(token, terms);
            }
        }
        return fresh;
    }
}
