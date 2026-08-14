package io.saiden.economyhelper;

import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.news.NewsSource;
import io.saiden.economyhelper.news.feed.FeedFetcher;
import io.saiden.economyhelper.telegram.Command;
import io.saiden.economyhelper.telegram.MessageFormatter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 진짜 스프링 컨텍스트 + 진짜 외부 API로 모든 기능을 태운다.
 *
 * <p>Redis가 없어 캐시만 끈다. 나머지는 운영과 같은 빈이다 —
 * 레이트리미터·서킷브레이커·설정 주입이 전부 그대로 걸린다.
 */
@SpringBootTest
class LiveVerificationScratchTest {

    @DynamicPropertySource
    static void noRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired FxService fxService;
    @Autowired StockService stockService;
    @Autowired CryptoService cryptoService;
    @Autowired NewsFacade newsFacade;
    @Autowired FeedFetcher feedFetcher;

    @Test
    void verifyEverything() {
        line("환율 · /fx");
        Optional<FxRate> fx = fxService.usdToKrw();
        fx.ifPresentOrElse(
                rate -> show(MessageFormatter.formatFx(rate)),
                () -> show("!!! 환율 실패 — " + MessageFormatter.fxUnavailable()));

        for (String query : List.of("삼성전자", "SK하이닉스", "코스피", "애플", "나스닥", "코카콜라")) {
            line("주식 · /stock " + query);
            Optional<StockQuote> quote = stockService.quote(query);
            quote.ifPresentOrElse(
                    q -> show(MessageFormatter.formatStock(q, fx.orElse(null))),
                    () -> show("!!! 못 찾음 — " + MessageFormatter.stockNotFound(query)));
        }

        BigDecimal usdtKrw = cryptoService.usdtKrw().orElse(null);
        for (String query : List.of("비트코인", "이더리움", "BNB", "테더", "리플")) {
            line("코인 · /crypto " + query);
            Optional<CryptoQuote> quote = cryptoService.quote(query);
            quote.ifPresentOrElse(
                    q -> show(MessageFormatter.formatCrypto(q, usdtKrw)),
                    () -> show("!!! 못 찾음 — " + MessageFormatter.cryptoNotFound(query)));
        }

        line("뉴스 수집 · 매체별 건수(페이월 차단 후)");
        for (NewsSource source : NewsSource.values()) {
            var articles = feedFetcher.fetch(source);
            long foreign = articles.stream().filter(a -> !source.owns(a.link())).count();
            show(source.displayName() + " → " + articles.size() + "건"
                    + (articles.isEmpty() ? "  *** 비었다 ***" : "")
                    + (foreign > 0 ? "  *** 남의 매체 " + foreign + "건 샜다 ***" : ""));
        }

        line("뉴스 검색 · /news 금리");
        List<NewsItem> found = newsFacade.search("금리");
        show(found.isEmpty()
                ? "!!! 못 찾음 — " + MessageFormatter.noResults("금리")
                : MessageFormatter.formatNews(found));

        line("아침 브리핑 · 뉴스 통");
        List<NewsItem> digest = newsFacade.digest();
        show(digest.isEmpty() ? "!!! 비었다" : MessageFormatter.formatNews(digest));

        line("아침 브리핑 · 증시 통");
        List<StockQuote> stocks = new java.util.ArrayList<>(stockService.indicesOf(List.of("코스피", "코스닥")));
        stocks.addAll(stockService.quotesOf(List.of("005930", "000660")));
        stocks.addAll(stockService.usQuotesOf(List.of("^IXIC", "^GSPC", "NVDA", "AAPL")));
        show(stocks.isEmpty() ? "!!! 비었다"
                : MessageFormatter.formatStockDigest(stocks, fx.orElse(null)));

        line("아침 브리핑 · 코인 통");
        List<CryptoQuote> cryptos = cryptoService.quotesOf(List.of("KRW-BTC", "KRW-ETH", "KRW-USDT"));
        show(cryptos.isEmpty() ? "!!! 비었다"
                : MessageFormatter.formatCryptoDigest(cryptos, usdtKrw));

        line("안내 · /help");
        show(MessageFormatter.help());

        line("안내 · 인자 없음 / 모르는 명령");
        show(MessageFormatter.usage(Command.STOCK));
        show(MessageFormatter.unknownCommand());
    }

    private static void line(String label) {
        System.out.println("\n[검증] ========== " + label + " ==========");
    }

    private static void show(String message) {
        System.out.println("[검증] " + message
                .replaceAll("<a href=\"[^\"]*\"><b>(.*?)</b></a>", "【$1】(링크)")
                .replaceAll("<b>(.*?)</b>", "【$1】")
                .replaceAll("<[^>]+>", "")
                .replace("\n", "\n[검증] "));
    }
}
