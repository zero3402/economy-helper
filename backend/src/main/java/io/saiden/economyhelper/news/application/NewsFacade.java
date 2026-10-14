package io.saiden.economyhelper.news.application;

import io.saiden.economyhelper.news.domain.Article;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.news.domain.ScoredArticle;
import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.translate.application.TranslationService;
import io.saiden.economyhelper.translate.domain.Translation;
import io.saiden.economyhelper.translate.domain.TranslationRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 수집 → 랭킹 → 번역을 한 줄로 묶는다.
 *
 * <p>텔레그램 {@code /news}와 아침 브리핑이 이 클래스를 부른다(아직 HTTP 진입점은 웹훅 하나뿐이다).
 * 채널마다 따로 조립하면 같은 검색어에 서로 다른 기사를 보여 주게 된다 — 채널이 다른 것은
 * 표현 방식뿐이어야 한다.
 */
@Service
public class NewsFacade {

    private final NewsService newsService;
    private final TranslationService translationService;
    private final QueryExpander queryExpander;

    public NewsFacade(NewsService newsService,
                      TranslationService translationService,
                      QueryExpander queryExpander) {
        this.newsService = newsService;
        this.translationService = translationService;
        this.queryExpander = queryExpander;
    }

    /**
     * 뉴스 신선도 창 — 못 찾았을 때 그 사유를 말하려면 화면이 이 값을 알아야 한다.
     * 설정과 문구가 따로 놀면 그 문구가 거짓말이 된다.
     */
    public Duration window() {
        return newsService.window();
    }

    /**
     * 최근 창 안의 발행분 중 무리마다 점수 상위 몇 건 — 정기 발송과 검색어 없는 {@code /news}가 쓴다.
     *
     * @throws Unavailable 피드가 전부 수집에 실패했을 때
     */
    public List<NewsItem> digest() {
        // NewsService가 이미 점수순으로 준다 — 그 순서 그대로 번역한다
        return translated(newsService.digest());
    }

    /**
     * {@code /news {검색어}} — 전 매체를 통틀어 <b>상위 몇 건</b>.
     *
     * <p>기사가 영문이라 한국어 검색어는 {@link QueryExpander}가 영어로 옮겨 준다.
     *
     * <p>한 건이 아니라 여러 건을 준다. 1위가 늘 원하던 기사인 것은 아닌데, 한 건뿐이면
     * 사용자가 할 수 있는 일이 검색어를 바꿔 다시 치는 것밖에 없다.
     */
    public List<NewsItem> search(String query) {
        // 원문을 함께 넘긴다 — 확장한 표현 묶음은 매칭용이고, "정말 그 주제인가"를 물으려면
        // 사용자가 실제로 친 말이 있어야 한다
        // 검색어 확장(Gemini)과 피드 수집은 서로를 기다릴 이유가 없다 — 겹친다
        Concurrently.Pair<QueryExpander.Expansion, List<Article>> both =
                Concurrently.both(() -> queryExpander.expand(query), newsService::fetchAll);
        List<ScoredArticle> found = newsService.search(both.first().groups(), query, both.second());
        if (found.isEmpty() && both.first().untranslated()) {
            // ⚠️ 「찾지 못했다」가 아니다 — 번역이 살아 있었다면 영문 기사에 걸렸을 수 있다(실물 감사 2026-10-14
            //    「/news 금리」, Gemini 503 중). 못 찾음으로 답하면 사용자가 다시 묻지 않는다
            throw new Unavailable("검색어를 번역하지 못해 '" + query + "'로는 영문 기사를 찾을 수 없었습니다");
        }
        return translated(found);
    }

    /**
     * <b>장애로 한 건도 못 찾았다</b> — 「그런 뉴스가 없다」가 아니다. 경우는 둘이다.
     *
     * <ul>
     *   <li>검색어를 번역하지 못했다({@link #search}) — 영문 기사는 번역된 낱말로만 걸리므로, 번역이 살아
     *       있었다면 찾았을 수 있다.</li>
     *   <li>피드가 <b>전부</b> 수집에 실패했다({@code NewsService.fetchEach}) — {@link #search}와 {@link #digest}
     *       둘 다 던진다.</li>
     * </ul>
     *
     * <p>웹훅이 이것을 받아 「잠시 후 다시」로 답하고, 브리핑은 뉴스 통만 뺀다({@code DailyDigestJob.section}).
     */
    public static final class Unavailable extends RuntimeException {

        public Unavailable(String message) {
            super(message);
        }
    }

    /** 번역을 한 번에 묶어 {@link NewsItem}으로 옮긴다 — 이유는 {@link TranslationService#translateAll}. */
    private List<NewsItem> translated(List<ScoredArticle> ordered) {
        List<Translation> translations = translationService.translateAll(
                ordered.stream().map(scored -> requestOf(scored.article())).toList());

        List<NewsItem> items = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            items.add(NewsItem.of(ordered.get(i), translations.get(i)));
        }
        return List.copyOf(items);
    }

    /**
     * 기사를 번역 입력으로 옮긴다 — 번역은 기사를 모른다. 캐시 키는 <b>링크</b>다
     * (같은 기사를 두 번 번역하지 않는다).
     */
    static TranslationRequest requestOf(Article article) {
        return new TranslationRequest(article.link(), article.title(), article.description());
    }
}
