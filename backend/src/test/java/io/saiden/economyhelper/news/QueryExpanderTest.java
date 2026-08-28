package io.saiden.economyhelper.news;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.news.rank.KeywordGroup;
import io.saiden.economyhelper.llm.GeminiApi;
import io.saiden.economyhelper.translate.QueryTranslator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

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

        List<KeywordGroup> groups = new QueryExpander(translator).expand("비트코인");

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).terms()).containsExactly("비트코인", "bitcoin", "btc");
        assertThat(translator.requested).containsExactly("비트코인");
    }

    @Test
    @DisplayName("영어 검색어는 번역기를 부르지 않는다 — 무료 티어를 태울 이유가 없다")
    void englishTokenSkipsTranslation() {
        RecordingTranslator translator = new RecordingTranslator(List.of("절대 안 쓰임"), null);

        List<KeywordGroup> groups = new QueryExpander(translator).expand("oil");

        assertThat(groups.get(0).terms()).containsExactly("oil");
        assertThat(translator.requested).isEmpty();
    }

    @Test
    @DisplayName("토큰마다 따로 번역해 개념 경계를 지킨다")
    void translatesEachTokenSeparately() {
        RecordingTranslator translator = new RecordingTranslator(List.of("translated"), null);

        List<KeywordGroup> groups = new QueryExpander(translator).expand("비트코인 금리 oil");

        assertThat(groups).hasSize(3);
        // 토큰별 번역은 겹쳐서 돌므로 요청 순서는 정해져 있지 않다 — 무엇을 물었는지만 본다
        assertThat(translator.requested)
                .as("검색어 전체를 한 번에 번역하면 결과를 원래 토큰에 되붙일 수 없다")
                .containsExactlyInAnyOrder("비트코인", "금리");
    }

    @Test
    @DisplayName("번역이 실패하면 원문 토큰만으로 내려간다 — 검색 자체가 죽지는 않는다")
    void fallsBackToRawTokenWhenTranslationFails() {
        RecordingTranslator translator =
                new RecordingTranslator(null, new IllegalStateException("429 Too Many Requests"));

        List<KeywordGroup> groups = new QueryExpander(translator).expand("금리 oil");

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).terms()).containsExactly("금리");
        assertThat(groups.get(1).terms()).containsExactly("oil");
    }

    @Test
    @DisplayName("빈 검색어는 묶음도 없다 — 호출자가 전체를 훑지 않도록")
    void blankQueryYieldsNoGroups() {
        QueryExpander expander = new QueryExpander(new RecordingTranslator(List.of("x"), null));

        assertThat(expander.expand("   ")).isEmpty();
        assertThat(expander.expand(null)).isEmpty();
    }

    @Test
    @DisplayName("소문자 토큰으로 쪼개고 중복을 지운다")
    void tokenizesQuery() {
        assertThat(QueryExpander.tokenize("  Fed  RATE  fed ")).containsExactly("fed", "rate");
        assertThat(QueryExpander.tokenize(null)).isEmpty();
    }

    @Test
    @DisplayName("한글이 한 자라도 섞이면 번역 대상이다")
    void detectsHangul() {
        assertThat(QueryExpander.hasHangul("금리")).isTrue();
        assertThat(QueryExpander.hasHangul("s&p500 지수")).isTrue();
        assertThat(QueryExpander.hasHangul("oil")).isFalse();
        assertThat(QueryExpander.hasHangul("s&p500")).isFalse();
    }

    /** 어떤 토큰이 번역을 요청받았는지 기록한다 — 호출 여부가 이 클래스의 핵심 계약이다. */
    private static final class RecordingTranslator extends QueryTranslator {
        private final List<String> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> result;
        private final RuntimeException failure;

        private RecordingTranslator(List<String> result, RuntimeException failure) {
            super(new GeminiApi(RestClient.builder(), "https://example.invalid", "unused", "unused"),
                    JsonMapper.builder().build());
            this.result = result;
            this.failure = failure;
        }

        @Override
        public List<String> toEnglishTerms(String token) {
            requested.add(token);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
