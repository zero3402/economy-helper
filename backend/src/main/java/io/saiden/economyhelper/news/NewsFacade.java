package io.saiden.economyhelper.news;

import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.translate.Translation;
import io.saiden.economyhelper.translate.TranslationService;
import java.util.ArrayList;
import java.util.List;
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

    /** 오늘 발행분 중 점수 상위 몇 건 — 정기 발송과 프론트 첫 화면이 같은 목록을 쓴다. */
    public List<NewsItem> digest() {
        // NewsService가 이미 점수순으로 상위 몇 건을 준다 — 그 순서 그대로 번역한다.
        // 번역을 겹친다 — 순차로 돌면 건수만큼 곱해져 그것만으로 10~25초였다
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
        return translated(newsService.search(queryExpander.expand(query), query));
    }

    /**
     * 번역을 겹쳐 {@link NewsItem}으로 옮긴다.
     *
     * <p>기사당 Gemini 한 번이고 서로 무관한데 줄줄이 기다리면 건수만큼 곱해진다.
     * 한 번에 묶어 보내지 않는 이유는 캐시다 — 링크 단위로 캐시해야 이미 번역한 기사를
     * 다시 태우지 않는다.
     */
    private List<NewsItem> translated(List<ScoredArticle> ordered) {
        List<Translation> translations =
                Concurrently.map(ordered, scored -> translationService.translate(scored.article()));

        List<NewsItem> items = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            items.add(NewsItem.of(ordered.get(i), translations.get(i)));
        }
        return List.copyOf(items);
    }
}
