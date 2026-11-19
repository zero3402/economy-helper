package io.saiden.economyhelper.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.domain.KeywordGroup;
import io.saiden.economyhelper.translate.application.QueryTranslationService;
import io.saiden.economyhelper.testsupport.SearchTermCaches;
import io.saiden.economyhelper.translate.application.port.out.SearchTermTranslator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 검색어 → 개념 묶음 변환을 Gemini 없이 고정한다.
 *
 * <p>가장 중요한 두 가지는 <b>영어 검색어는 번역기를 부르지 않는다</b>(무료 티어를 아낀다)와
 * <b>번역이 실패해도 검색 자체는 진행된다</b>는 점이다.
 */
class QueryExpanderTest {

    @Test
    @DisplayName("한국어 토큰은 원문과 영어 표현을 함께 담은 한 묶음이 된다")
    void koreanTokenExpandsToOneGroupWithEnglishTerms() {
        RecordingTranslator translator = new RecordingTranslator(List.of("bitcoin", "btc"), null);

        List<KeywordGroup> groups = expander(translator).expand("비트코인").groups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).terms()).containsExactly("비트코인", "bitcoin", "btc");
        assertThat(translator.requested).containsExactly("비트코인");
    }

    @Test
    @DisplayName("번역에는 친 낱말을 보낸다 — 「화웨이」를 조사로 잘라 「화웨」로 보내지 않는다")
    void translatesTheTypedWordNotTheParticleStrippedOne() {
        RecordingTranslator translator = new RecordingTranslator(List.of("huawei"), null);

        List<KeywordGroup> groups = expander(translator).expand("화웨이").groups();

        assertThat(translator.requested).containsExactly("화웨이");
        assertThat(groups.get(0).terms()).contains("huawei");
    }

    @Test
    @DisplayName("S&P·AT&T·U.S.는 기호를 지킨다 — sp·att·us로 망가지면 spending·attack·business에 걸린다")
    void keepsSymbolsInsideTokens() {
        assertThat(stems("S&P 500 AT&T U.S.")).containsExactly("s&p", "500", "at&t", "u.s");
    }

    @Test
    @DisplayName("검색어 토큰은 다섯까지만 번역한다 — 붙여 넣은 긴 글 하나가 Gemini 분당 한도를 다 먹지 않게")
    void capsTheNumberOfTranslatedTokens() {
        RecordingTranslator translator = new RecordingTranslator(List.of("x"), null);

        expander(translator).expand("가나 다라 마바 사아 자차 카타 파하").groups();

        assertThat(translator.requested).hasSize(QueryExpander.MAX_TOKENS);
    }

    @Test
    @DisplayName("영어 검색어는 번역기를 부르지 않는다 — 무료 티어를 태울 이유가 없다")
    void englishTokenSkipsTranslation() {
        RecordingTranslator translator = new RecordingTranslator(List.of("절대 안 쓰임"), null);

        List<KeywordGroup> groups = expander(translator).expand("oil").groups();

        assertThat(groups.get(0).terms()).containsExactly("oil");
        assertThat(translator.requested).isEmpty();
    }

    @Test
    @DisplayName("한글 토큰만 모아 한 번에 묻고, 답은 토큰별로 되붙인다 — 개념 경계를 지킨다")
    void asksOnceForAllKoreanTokensAndKeepsOneGroupPerToken() {
        RecordingTranslator translator = new RecordingTranslator(List.of("translated"), null);

        List<KeywordGroup> groups = expander(translator).expand("비트코인 금리 oil").groups();

        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).terms()).containsExactly("비트코인", "translated");
        assertThat(groups.get(1).terms()).containsExactly("금리", "translated");
        assertThat(groups.get(2).terms()).containsExactly("oil");
        assertThat(translator.calls)
                .as("토큰마다 부르면 검색 한 번이 Gemini 한도를 토큰 수만큼 쓴다")
                .hasValue(1);
        assertThat(translator.requested).containsExactly("비트코인", "금리");
    }

    @Test
    @DisplayName("번역기가 한 토큰만 못 옮기면 그 토큰만 원문으로 내려가고 고장을 알린다")
    void fallsBackOnlyForTheTokenThatWasNotTranslated() {
        SearchTermTranslator onlyBitcoin = tokens -> Map.of("비트코인", List.of("bitcoin"));

        QueryExpander.Expansion expansion = expander(onlyBitcoin).expand("비트코인 금리");

        assertThat(expansion.groups().get(0).terms()).containsExactly("비트코인", "bitcoin");
        assertThat(expansion.groups().get(1).terms()).containsExactly("금리");
        assertThat(expansion.untranslated()).isTrue();
    }

    @Test
    @DisplayName("번역이 실패하면 원문 토큰만으로 내려간다 — 검색 자체가 죽지는 않는다")
    void fallsBackToRawTokenWhenTranslationFails() {
        RecordingTranslator translator =
                new RecordingTranslator(null, new IllegalStateException("429 Too Many Requests"));

        QueryExpander.Expansion expansion = expander(translator).expand("금리 oil");
        List<KeywordGroup> groups = expansion.groups();

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).terms()).containsExactly("금리");
        assertThat(groups.get(1).terms()).containsExactly("oil");
        assertThat(expansion.untranslated())
                .as("고장으로 빠진 번역을 알린다 — 못 찾았을 때 「찾지 못했다」라 단정하지 않게").isTrue();
    }

    @Test
    @DisplayName("번역이 됐거나 번역할 것이 없으면 고장이 아니다")
    void reportsNoFailureWhenNothingBroke() {
        RecordingTranslator translator = new RecordingTranslator(List.of("interest rate"), null);

        assertThat(expander(translator).expand("금리 oil").untranslated())
                .isFalse();
    }

    @Test
    @DisplayName("빈 검색어는 묶음도 없다 — 호출자가 전체를 훑지 않도록")
    void blankQueryYieldsNoGroups() {
        QueryExpander expander = expander(new RecordingTranslator(List.of("x"), null));

        assertThat(expander.expand("   ").groups()).isEmpty();
        assertThat(expander.expand(null).groups()).isEmpty();
    }

    @Test
    @DisplayName("소문자 토큰으로 쪼개고 중복을 지운다")
    void tokenizesQuery() {
        assertThat(stems("  Fed  RATE  fed ")).containsExactly("fed", "rate");
        assertThat(stems(null)).isEmpty();
    }

    private static QueryExpander expander(SearchTermTranslator translator) {
        return new QueryExpander(new QueryTranslationService(translator, SearchTermCaches.none()));
    }

    /** 조사를 뗀 형태만 — 토큰화 규칙을 본다. */
    private static List<String> stems(String query) {
        return QueryExpander.tokens(query).stream().map(QueryExpander.Token::stem).toList();
    }

    /** 어떤 토큰이 번역을 요청받았는지 기록한다 — 호출 여부가 이 클래스의 핵심 계약이다. */
    private static final class RecordingTranslator implements SearchTermTranslator {
        private final List<String> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> result;
        private final RuntimeException failure;

        private RecordingTranslator(List<String> result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public Map<String, List<String>> toEnglishTerms(List<String> tokens) {
            calls.incrementAndGet();
            requested.addAll(tokens);
            if (failure != null) {
                throw failure;
            }
            Map<String, List<String>> byToken = new LinkedHashMap<>();
            tokens.forEach(token -> byToken.put(token, result));
            return byToken;
        }
    }
}
