package io.saiden.economyhelper.translate.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.testsupport.SearchTermCaches;
import io.saiden.economyhelper.translate.application.port.out.SearchTermTranslator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 검색어 번역의 묶기와 캐시를 번역기 없이 고정한다 — <b>캐시에 없는 토큰만 한 번에 묻고, 돌아온 것만 담는다.</b>
 */
class QueryTranslationServiceTest {

    private final SearchTermCaches.InMemory cache = new SearchTermCaches.InMemory();

    @Test
    @DisplayName("캐시에 없는 토큰만 묶어 번역기를 한 번 부른다")
    void asksOnlyForTheMissesInOneCall() {
        cache.stored.put("금리", List.of("interest rate"));
        RecordingTranslator translator = new RecordingTranslator(Map.of(
                "비트코인", List.of("bitcoin"), "환율", List.of("exchange rate")));

        Map<String, List<String>> terms = service(translator).toEnglishTerms(List.of("비트코인", "금리", "환율"));

        assertThat(translator.batches).containsExactly(List.of("비트코인", "환율"));
        assertThat(terms).containsOnlyKeys("비트코인", "금리", "환율");
        assertThat(terms.get("금리")).containsExactly("interest rate");
    }

    @Test
    @DisplayName("전부 캐시에 있으면 번역기를 부르지 않는다")
    void neverCallsTheTranslatorWhenEverythingIsCached() {
        cache.stored.put("금리", List.of("interest rate"));
        RecordingTranslator translator = new RecordingTranslator(Map.of());

        assertThat(service(translator).toEnglishTerms(List.of("금리"))).containsOnlyKeys("금리");
        assertThat(translator.batches).isEmpty();
    }

    @Test
    @DisplayName("새로 받은 번역은 토큰 단위로 담는다 — 다음 검색어가 그 토큰을 다시 묻지 않는다")
    void cachesEachTranslatedToken() {
        RecordingTranslator translator = new RecordingTranslator(Map.of("비트코인", List.of("bitcoin")));

        service(translator).toEnglishTerms(List.of("비트코인"));
        service(translator).toEnglishTerms(List.of("비트코인"));

        assertThat(cache.stored).containsEntry("비트코인", List.of("bitcoin"));
        assertThat(translator.batches).hasSize(1);
    }

    @Test
    @DisplayName("번역기가 죽어도 캐시에서 건진 것은 남는다 — 못 옮긴 토큰만 빠지고 아무것도 담지 않는다")
    void keepsTheCachedHitsWhenTheTranslatorFails() {
        cache.stored.put("금리", List.of("interest rate"));
        SearchTermTranslator failing = tokens -> {
            throw new IllegalStateException("Gemini 503");
        };

        Map<String, List<String>> terms = service(failing).toEnglishTerms(List.of("비트코인", "금리"));

        assertThat(terms).containsOnlyKeys("금리");
        assertThat(cache.stored).containsOnlyKeys("금리");
    }

    @Test
    @DisplayName("번역기가 일부만 답하면 답한 토큰만 담는다 — 빠진 토큰을 빈 값으로 굳히지 않는다")
    void cachesOnlyTheTokensThatWereAnswered() {
        RecordingTranslator translator = new RecordingTranslator(Map.of("비트코인", List.of("bitcoin")));

        Map<String, List<String>> terms = service(translator).toEnglishTerms(List.of("비트코인", "금리"));

        assertThat(terms).containsOnlyKeys("비트코인");
        assertThat(cache.stored).containsOnlyKeys("비트코인");
    }

    @Test
    @DisplayName("묻지 않은 토큰의 답은 받지도 담지도 않는다")
    void ignoresAnswersForTokensThatWereNotAsked() {
        RecordingTranslator translator = new RecordingTranslator(Map.of(
                "비트코인", List.of("bitcoin"), "이더리움", List.of("ethereum")));

        Map<String, List<String>> terms = service(translator).toEnglishTerms(List.of("비트코인"));

        assertThat(terms).containsOnlyKeys("비트코인");
        assertThat(cache.stored).containsOnlyKeys("비트코인");
    }

    @Test
    @DisplayName("같은 토큰이 두 번 와도 한 번만 묻는다")
    void asksOnceForARepeatedToken() {
        RecordingTranslator translator = new RecordingTranslator(Map.of("금리", List.of("interest rate")));

        service(translator).toEnglishTerms(List.of("금리", "금리"));

        assertThat(translator.batches).containsExactly(List.of("금리"));
    }

    private QueryTranslationService service(SearchTermTranslator translator) {
        return new QueryTranslationService(translator, cache);
    }

    /** 묶음마다 무엇을 물었는지 기록하고, 아는 토큰만 답한다. */
    private static final class RecordingTranslator implements SearchTermTranslator {
        private final List<List<String>> batches = new ArrayList<>();
        private final Map<String, List<String>> known;

        private RecordingTranslator(Map<String, List<String>> known) {
            this.known = known;
        }

        @Override
        public Map<String, List<String>> toEnglishTerms(List<String> tokens) {
            batches.add(List.copyOf(tokens));
            // 묻지 않은 것까지 돌려준다 — 서비스가 걸러야 한다
            return new LinkedHashMap<>(known);
        }
    }
}
