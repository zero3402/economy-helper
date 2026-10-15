package io.saiden.economyhelper.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.KeywordGroup;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.news.domain.NewsSource;
import io.saiden.economyhelper.news.domain.ScoredArticle;
import io.saiden.economyhelper.testsupport.TestProperties;
import io.saiden.economyhelper.translate.application.QueryTranslationService;
import io.saiden.economyhelper.translate.application.TranslationService;
import io.saiden.economyhelper.translate.application.port.out.SearchTermTranslator;
import io.saiden.economyhelper.translate.domain.Translation;
import io.saiden.economyhelper.translate.domain.TranslationRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기사와 번역을 <b>인덱스로</b> 짝짓는 자리 — 어긋나면 <b>기사 A에 B의 한글</b>이 붙는다.
 *
 * <p>화면이 그럴듯해서 아무도 모르는 종류의 사고다. 세 겹이 맞춰져 있다:
 * {@code GeminiTranslator}가 개수를 검사해 던지고, {@code TranslationService}가 그것을 잡아
 * <b>같은 개수</b>의 원문으로 떨어지며, {@code translateAll}이 원본 순서로 다시 세운다.
 *
 * <p>여기서는 <b>링크로 짝을 확인한다</b> — 인덱스가 우연히 맞은 것과 구분되어야 한다.
 *
 * <p>⚠️ <b>「순서가 뒤바뀌면 화면도 뒤바뀐다」는 테스트는 두지 않는다.</b>
 * {@code TranslationService.translateAll}이 링크로 찾아 다시 세우므로 실물 경로에서 일어날 수
 * 없는 시나리오다. 순서 계약은 그것을 지키는 층에 있다({@code TranslationServiceTest}·
 * {@code GeminiTranslatorTest}).
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
                .map(article -> Translation.of("번역:" + lastSegmentOfLink(article), ""))
                .toList()).digest();

        assertThat(items).extracting(NewsItem::link, NewsItem::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple("https://a.example/oil", "번역:oil"),
                org.assertj.core.groups.Tuple.tuple("https://b.example/gold", "번역:gold"),
                org.assertj.core.groups.Tuple.tuple("https://c.example/yen", "번역:yen"));
    }

    @Test
    @DisplayName("기사가 없으면 빈 목록 — 번역기를 부르지 않는다")
    void translatesNothingWhenThereIsNothing() {
        List<TranslationRequest> asked = new ArrayList<>();
        assertThat(facade(List.of(), articles -> {
            asked.addAll(articles);
            return List.of();
        }).digest()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("검색어 번역이 죽고 한 건도 못 찾았으면 「찾지 못했다」가 아니라 장애다 — 실물 감사 2026-10-14 「/news 금리」")
    void signalsAnOutageWhenTheQueryCouldNotBeTranslatedAndNothingMatched() {
        NewsFacade facade = searchFacade(List.of(), token -> {
            throw new IllegalStateException("Gemini 503");
        });

        assertThatThrownBy(() -> facade.search("금리")).isInstanceOf(NewsFacade.Unavailable.class);
    }

    @Test
    @DisplayName("번역이 죽어도 원문 토큰이 찾은 기사는 낸다 — 장애는 빈손일 때만 알린다")
    void answersWhatTheUntranslatedQueryFound() {
        NewsFacade facade = searchFacade(List.of(scored("금리 동결", "https://a.example/rate")), token -> {
            throw new IllegalStateException("Gemini 503");
        });

        assertThat(facade.search("금리")).hasSize(1);
    }

    @Test
    @DisplayName("번역이 됐는데 못 찾은 것은 그대로 「찾지 못했다」다 — 장애가 아니다")
    void returnsEmptyWhenTheTranslatedQueryFindsNothing() {
        NewsFacade facade = searchFacade(List.of(), token -> List.of("interest rate"));

        assertThat(facade.search("금리")).isEmpty();
    }

    @Test
    @DisplayName("번역 캐시 키는 링크에 글의 지문을 붙인다 — 같은 URL 아래 제목이 바뀌면 옛 번역을 붙이지 않는다")
    void translationKeyFollowsTheHeadlineNotJustTheLink() {
        // Yahoo·CNBC는 같은 URL로 제목을 고친다. 링크만 키로 쓰면 옛 제목의 번역이 새 기사에 7일간 붙는다 — 틀린 값이다
        String link = "https://a.example/fed";
        Article first = new Article(NewsSource.CNBC, "Fed holds rates", "Rates stay put.", link, NOW, 0);
        Article retitled = new Article(NewsSource.CNBC, "Fed cuts rates", "Rates stay put.", link, NOW, 0);
        Article sameText = new Article(NewsSource.CNBC, "Fed holds rates", "Rates stay put.", link, NOW, 3);
        Article noBody = new Article(NewsSource.CNBC, "Fed holds rates", null, link, NOW, 0);

        String key = NewsFacade.requestOf(first).key();

        assertThat(NewsFacade.requestOf(retitled).key()).as("제목이 바뀌면 다른 키").isNotEqualTo(key);
        assertThat(NewsFacade.requestOf(sameText).key()).as("글이 같으면 같은 키 — 캐시가 계속 맞는다").isEqualTo(key);
        assertThat(NewsFacade.requestOf(noBody).key()).as("요약문이 없어도 키가 선다").isNotEqualTo(key);
        assertThat(key).as("로그·Redis에서 사람이 읽도록 링크가 앞에 그대로 남는다").startsWith(link);
    }

    // --- 도우미 ---

    /** {@code search()}가 부르는 셋만 갈아 끼운다 — 수집은 빈손, 점수는 {@code found}가 정한다. */
    private static NewsFacade searchFacade(List<ScoredArticle> found, SearchTermTranslator translator) {
        NewsService news = new NewsService(null, null, null, null,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                TestProperties.builder()
                        .newsWindow(java.time.Duration.ofHours(24))
                        .llmCandidates(8).relevanceThreshold(0.4)
                        .searchResults(5).cryptoResults(5).economyResults(5)
                        .build()) {
            @Override
            public List<Article> fetchAll() {
                return List.of();
            }

            @Override
            public List<ScoredArticle> search(Collection<KeywordGroup> keywords, String query, List<Article> fetched) {
                return found;
            }
        };
        TranslationService translations = new TranslationService(null, null) {
            @Override
            public List<Translation> translateAll(List<TranslationRequest> articles) {
                return articles.stream().map(article -> Translation.of(article.title(), "")).toList();
            }
        };
        return new NewsFacade(news, translations, new QueryExpander(new QueryTranslationService(translator)));
    }

    /** 키는 링크 + {@code |지문}이다({@code NewsFacade.requestOf}) — 링크 부분의 마지막 토막만 쓴다. */
    private static String lastSegmentOfLink(TranslationRequest article) {
        String link = article.key().substring(0, article.key().indexOf('|'));
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static ScoredArticle scored(String title, String link) {
        return new ScoredArticle(
                new Article(NewsSource.CNBC, title, title + " body", link, NOW, 0),
                1.0, new ScoredArticle.ScoreBreakdown(0, 0, 0, 0));
    }

    /** {@code digest()}가 부르는 두 협력자만 갈아 끼운다 — 스프링도 HTTP도 타지 않는다. */
    private static NewsFacade facade(List<ScoredArticle> ordered,
                                     java.util.function.Function<List<TranslationRequest>, List<Translation>> translate) {
        NewsService news = new NewsService(null, null, null, null,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                TestProperties.builder()
                        .newsWindow(java.time.Duration.ofHours(24))
                        .llmCandidates(8).relevanceThreshold(0.4)
                        .searchResults(5).cryptoResults(5).economyResults(5)
                        .build()) {
            @Override
            public List<ScoredArticle> digest() {
                return ordered;
            }
        };
        TranslationService translations = new TranslationService(null, null) {
            @Override
            public List<Translation> translateAll(List<TranslationRequest> articles) {
                return translate.apply(articles);
            }
        };
        return new NewsFacade(news, translations, null);
    }
}
