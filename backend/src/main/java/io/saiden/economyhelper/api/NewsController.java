package io.saiden.economyhelper.api;

import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트엔드용 REST (Phase 2 Vue에서 쓴다).
 *
 * <p>텔레그램 웹훅과 <b>같은 {@link NewsFacade}</b>를 부른다 — 다른 건 표현 방식뿐이다.
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsFacade newsFacade;

    public NewsController(NewsFacade newsFacade) {
        this.newsFacade = newsFacade;
    }

    /** 매체별 1건 — 정기 발송과 같은 목록. */
    @GetMapping("/top")
    public List<NewsItem> top() {
        return newsFacade.digest();
    }

    /** 텔레그램 {@code /news {검색어}}와 같은 결과. */
    @GetMapping("/search")
    public ResponseEntity<NewsItem> search(@RequestParam("q") String query) {
        return newsFacade.search(query)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
