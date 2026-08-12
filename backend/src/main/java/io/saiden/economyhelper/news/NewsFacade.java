package io.saiden.economyhelper.news;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.news.rank.KeywordGroup;
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
    private final List<KeywordGroup> digestKeywords;

    public NewsFacade(NewsService newsService,
                      TranslationService translationService,
                      QueryExpander queryExpander,
                      EconomyHelperProperties properties) {
        this.newsService = newsService;
        this.translationService = translationService;
        this.queryExpander = queryExpander;
        this.digestKeywords = digestKeywordsOf(properties);
    }

    /**
     * 설정의 재테크 사전은 이미 영어이고 항목마다 표현이 하나씩이라 <b>1항목 묶음</b>이 된다.
     * 분모가 {@code min(17, 3) = 3}으로 유지돼 8단계까지의 동작과 같다.
     */
    private static List<KeywordGroup> digestKeywordsOf(EconomyHelperProperties properties) {
        if (properties.digest() == null || properties.digest().keywords() == null) {
            return List.of();
        }
        return properties.digest().keywords().stream()
                .map(KeywordGroup::of)
                .filter(group -> !group.isEmpty())
                .toList();
    }

    /** 매체별 1건 — 정기 발송과 프론트 첫 화면이 같은 목록을 쓴다. */
    public List<NewsItem> digest() {
        Map<NewsSource, ScoredArticle> top = newsService.digest(digestKeywords);
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
