package io.saiden.economyhelper.news;

import io.saiden.economyhelper.translate.Translation;
import io.saiden.economyhelper.translate.TranslationService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 수집 → 랭킹 → 번역을 한 줄로 묶는다.
 *
 * <p>텔레그램 {@code /news}가 이 클래스를 부른다 — 프론트용 REST가 붙는 날에도 같은 자리를
 * 쓰도록 갈라 뒀다(아직 HTTP 진입점은 웹훅 하나뿐이다)
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

    /**
     * 뉴스 신선도 창 — 못 찾았을 때 그 사유를 말하려면 화면이 이 값을 알아야 한다.
     * 설정과 문구가 따로 놀면 그 문구가 거짓말이 된다.
     */
    public Duration window() {
        return newsService.window();
    }

    /** 최근 창 안의 발행분 중 점수 상위 몇 건 — 정기 발송이 쓰는 목록이다(프론트가 붙으면 같은 것을 쓴다 — 아직 없다). */
    public List<NewsItem> digest() {
        // NewsService가 이미 점수순으로 상위 몇 건을 준다 — 그 순서 그대로 번역한다.
        // 번역은 한 번에 묶어 부른다 — 건별로 부르면 호출이 건수만큼 늘고, 그 호출들이
        // 관련도 채점 뒤에 줄을 서서 리미터가 소진되면 번역부터 잘려 나갔다
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
     * 번역을 묶어 {@link NewsItem}으로 옮긴다.
     *
     * <p><b>한 번에 묶어 보낸다.</b> 예전에는 기사마다 한 번씩 불렀는데, 그러면 브리핑 한 번에
     * 번역만 세 번이 나가고 그 셋이 관련도 채점(매체당 1회) <b>뒤에</b> 줄을 선다 —
     * Gemini 리미터가 소진되면 잘려 나가는 쪽이 정확히 번역이라 "번역이 일시적으로 불가"가
     * 자주 떴다. 캐시는 그대로다: {@code translateAll}이 캐시에 없는 것만 골라 묶는다.
     */
    private List<NewsItem> translated(List<ScoredArticle> ordered) {
        List<Translation> translations = translationService.translateAll(
                ordered.stream().map(ScoredArticle::article).toList());

        List<NewsItem> items = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            items.add(NewsItem.of(ordered.get(i), translations.get(i)));
        }
        return List.copyOf(items);
    }
}
