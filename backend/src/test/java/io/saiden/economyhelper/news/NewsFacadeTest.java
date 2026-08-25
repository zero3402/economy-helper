package io.saiden.economyhelper.news;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.translate.Translation;
import io.saiden.economyhelper.translate.TranslationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기사와 번역을 <b>인덱스로</b> 짝짓는 자리 — 어긋나면 <b>기사 A에 B의 한글</b>이 붙는다.
 *
 * <p>화면이 그럴듯해서 아무도 모르는 종류의 사고다. 지금은 세 겹이 손으로 맞춰져 있다:
 * {@code GeminiTranslator}가 개수를 검사해 던지고, {@code TranslationService}가 그것을 잡아
 * <b>같은 개수</b>의 원문으로 떨어지며, {@code translateAll}이 원본 순서로 다시 세운다.
 * <b>그 세 겹에 테스트가 하나도 없었다.</b>
 *
 * <p>여기서는 <b>링크로 짝을 확인한다</b> — 인덱스가 우연히 맞은 것과 구분되어야 한다.
 */
class NewsFacadeTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    @DisplayName("기사마다 제 번역이 붙는다 — 링크로 확인한다, 인덱스가 우연히 맞은 것과 구분해야 한다")
    void pairsEveryArticleWithItsOwnTranslation() {
        List<ScoredArticle> ordered = List.of(
                scored("Oil holds advance", "https://a.example/oil"),
                scored("Gold slips", "https://b.example/gold"),
                scored("Yen weakens", "https://c.example/yen"));

        List<NewsItem> items = facade(ordered, articles -> articles.stream()
                // 링크의 마지막 토막을 제목으로 쓴다 — 짝이 밀리면 바로 드러난다
                .map(article -> Translation.of(
                        "번역:" + article.link().substring(article.link().lastIndexOf('/') + 1), ""))
                .toList()).digest();

        assertThat(items).extracting(NewsItem::link, NewsItem::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple("https://a.example/oil", "번역:oil"),
                org.assertj.core.groups.Tuple.tuple("https://b.example/gold", "번역:gold"),
                org.assertj.core.groups.Tuple.tuple("https://c.example/yen", "번역:yen"));
    }

    @Test
    @DisplayName("번역이 뒤바뀌어 오면 화면도 뒤바뀐다 — 그래서 개수·순서 계약을 아래층이 지켜야 한다")
    void showsWhatHappensWhenTheOrderIsBroken() {
        // ⚠️ 이 테스트는 「올바름」이 아니라 **의존성의 크기**를 못 박는다. NewsFacade는 순서를
        //    스스로 검증하지 않으므로(링크로 짝지으려면 Translation이 링크를 들어야 한다)
        //    GeminiTranslator의 개수 검사와 TranslationService의 순서 복원이 곧 이 화면의
        //    정확성이다. 그 둘을 완화하면 여기서 조용히 틀린 화면이 나온다는 사실을 남긴다
        List<ScoredArticle> ordered = List.of(
                scored("Oil holds advance", "https://a.example/oil"),
                scored("Gold slips", "https://b.example/gold"));

        List<NewsItem> items = facade(ordered, articles -> {
            List<Translation> reversed = new ArrayList<>(
                    articles.stream().map(article -> Translation.of("번역:" + article.title(), "")).toList());
            java.util.Collections.reverse(reversed);
            return reversed;
        }).digest();

        assertThat(items.get(0).link()).isEqualTo("https://a.example/oil");
        assertThat(items.get(0).title())
                .as("아래층이 순서를 어기면 이렇게 된다 — 그것이 이 계약이 load-bearing인 이유다")
                .isEqualTo("번역:Gold slips");
    }

    @Test
    @DisplayName("기사가 없으면 빈 목록 — 번역기를 부르지 않는다")
    void translatesNothingWhenThereIsNothing() {
        List<Article> asked = new ArrayList<>();
        assertThat(facade(List.of(), articles -> {
            asked.addAll(articles);
            return List.of();
        }).digest()).isEmpty();
        assertThat(asked).isEmpty();
    }

    // --- 도우미 ---

    private static ScoredArticle scored(String title, String link) {
        return new ScoredArticle(
                new Article(NewsSource.CNBC, title, title + " body", link, NOW, 0),
                1.0, new ScoredArticle.ScoreBreakdown(0, 0, 0, 0));
    }

    /** {@code digest()}가 부르는 두 협력자만 갈아 끼운다 — 스프링도 HTTP도 타지 않는다. */
    private static NewsFacade facade(List<ScoredArticle> ordered,
                                     java.util.function.Function<List<Article>, List<Translation>> translate) {
        NewsService news = new NewsService(null, null, null, null,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                java.time.Duration.ofHours(24), 8, 0.4, 3, 3) {
            @Override
            public List<ScoredArticle> digest() {
                return ordered;
            }
        };
        TranslationService translations = new TranslationService(null, null) {
            @Override
            public List<Translation> translateAll(List<Article> articles) {
                return translate.apply(articles);
            }
        };
        return new NewsFacade(news, translations, null);
    }
}
