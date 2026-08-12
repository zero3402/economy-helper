package io.saiden.economyhelper.news;

import io.saiden.economyhelper.translate.TranslationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 수집 → 랭킹 → 번역을 한 줄로 묶는다.
 *
 * <p><b>텔레그램 {@code /news}와 프론트용 REST가 둘 다 이 클래스를 부른다.</b>
 * 채널마다 따로 조립하기 시작하면 같은 검색어에 서로 다른 기사를 보여주게 된다.
 * 채널이 다른 건 표현 방식(텔레그램 메시지 vs JSON)뿐이어야 한다.
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

    /** 매체별 1건 — 정기 발송과 프론트 첫 화면이 같은 목록을 쓴다. */
    public List<NewsItem> digest() {
        Map<NewsSource, ScoredArticle> top = newsService.digest();
        List<NewsItem> items = new ArrayList<>(top.size());
        // NewsSource 선언 순서를 따라 매체 순서를 고정한다 — 발송할 때마다 순서가 바뀌면 읽기 불편하다
        for (NewsSource source : NewsSource.values()) {
            ScoredArticle scored = top.get(source);
            if (scored != null) {
                items.add(NewsItem.of(scored, translationService.translate(scored.article())));
            }
        }
        return List.copyOf(items);
    }

    /**
     * {@code /news {검색어}} — 전 매체 1위 한 건.
     *
     * <p>기사가 영문이라 한국어 검색어는 {@link QueryExpander}가 영어로 옮겨 준다.
     */
    public Optional<NewsItem> search(String query) {
        return newsService.search(queryExpander.expand(query))
                .map(scored -> NewsItem.of(scored, translationService.translate(scored.article())));
    }
}
