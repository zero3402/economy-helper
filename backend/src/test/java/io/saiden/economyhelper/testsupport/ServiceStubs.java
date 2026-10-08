package io.saiden.economyhelper.testsupport;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.crypto.application.CryptoService;
import io.saiden.economyhelper.crypto.domain.CryptoQuote;
import io.saiden.economyhelper.fx.application.FxService;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.news.application.NewsFacade;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.shared.domain.DailyBar;
import io.saiden.economyhelper.stock.application.StockListings;
import io.saiden.economyhelper.stock.application.StockService;
import io.saiden.economyhelper.stock.domain.StockOutlook;
import io.saiden.economyhelper.stock.domain.StockQuote;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 서비스 가짜 — 브리핑·웹훅 테스트가 나눠 쓴다. 해석·이중화 규칙은 각 서비스의 테스트가 보고,
 * 여기 가짜를 쓰는 쪽은 <b>라우팅과 조립</b>만 본다.
 *
 * <p>⚠️ <b>가짜는 덮은 메서드만 믿을 수 있다.</b> 나머지 협력자는 비어 있다 — 덮지 않은 경로를 타면
 * {@code null} 협력자에서 터지거나(조용히 삼켜질 수 있다) LLM·이름 검색이면 {@link AssertionError}로
 * 바로 드러난다. 그래서 시험하는 경로의 메서드는 반드시 덮는다: 예컨대 브리핑이 부르는 것은
 * {@code quotesOf}가 아니라 {@code answersOf}다.
 */
public final class ServiceStubs {

    /** 업비트가 시각을 안 줄 때 {@link CryptoService}가 쓰는 시계 — 가짜는 시세를 덮으므로 읽히지 않는다. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    private ServiceStubs() {
    }

    // --- 환율 ---------------------------------------------------------------

    /** 시세만 주는 환율. {@code empty}면 출처가 다 죽은 상태다. 일봉은 덮지 않는다. */
    public static FxService fx(Optional<FxRate> rate) {
        return new FxService(List.of(), null) {
            @Override
            public Optional<FxRate> usdToKrw() {
                return rate;
            }
        };
    }

    // --- 증시 ---------------------------------------------------------------

    /** 지수도 종목도 일봉도 빈손인 증시 — 브리핑에서 증시 통이 빠진다. */
    public static StockService deadStock() {
        return new StockStub() {
            @Override
            public List<StockQuote> indicesOf(List<EconomyHelperProperties.Index> indices) {
                return List.of();
            }

            @Override
            public List<Answer> answersOf(List<String> codes) {
                return List.of();
            }

            @Override
            public List<DailyBar> dailyBarsOf(Series series) {
                return List.of();
            }
        };
    }

    /**
     * 협력자가 전부 비어 있는 {@link StockService} — 익명 클래스로 필요한 메서드만 덮어 쓴다.
     *
     * <p>이름 검색과 LLM 해석은 <b>부르면 실패한다</b>: 브리핑은 코드로만 조회하고, 웹훅 가짜는
     * {@code answer}를 덮는다. 실수로 그쪽으로 새면 여기서 드러난다.
     */
    public static class StockStub extends StockService {

        public StockStub() {
            super(List.of(), List.of(),
                    name -> {
                        throw new AssertionError("가짜가 덮지 않은 경로가 이름 검색을 불렀습니다: " + name);
                    },
                    new StockListings(List::of),
                    query -> {
                        throw new AssertionError("가짜가 덮지 않은 경로가 LLM 해석을 불렀습니다: " + query);
                    },
                    (code, fund) -> StockOutlook.NONE, symbol -> StockOutlook.NONE, symbol -> null, null);
        }
    }

    // --- 코인 ---------------------------------------------------------------

    /** 마켓 코드로 묻는 경로({@code quotesOf}) — 브리핑이 쓴다. 빈 목록이면 코인 통이 빠진다. */
    public static CryptoService cryptoQuotes(List<CryptoQuote> quotes) {
        return new CryptoStub() {
            @Override
            public List<CryptoQuote> quotesOf(List<String> markets) {
                return quotes;
            }
        };
    }

    /**
     * 협력자가 전부 비어 있는 {@link CryptoService} — 익명 클래스로 필요한 메서드만 덮어 쓴다.
     *
     * <p>업비트·바이낸스는 {@code null}이고 LLM 해석은 <b>부르면 실패한다</b> — 브리핑은 마켓
     * 코드로 조회하므로 그 경로를 타지 않는다.
     */
    public static class CryptoStub extends CryptoService {

        public CryptoStub() {
            super(null, null,
                    query -> {
                        throw new AssertionError("가짜가 덮지 않은 경로가 LLM 해석을 불렀습니다: " + query);
                    },
                    FIXED_CLOCK);
        }
    }

    // --- 뉴스 ---------------------------------------------------------------

    /**
     * 검색 답과 <b>검색어 없는 답</b>({@code digest})을 따로 준다 — 호출자가 갈래를 고르는지 보려면
     * 두 경로가 서로 다른 값을 돌려줘야 한다. 같은 목록을 주면 어느 쪽을 불렀는지 알 수 없다.
     */
    public static NewsFacade news(List<NewsItem> searchResults, List<NewsItem> digestResults) {
        return new NewsFacade(null, null, null) {
            @Override
            public List<NewsItem> search(String query) {
                return searchResults;
            }

            @Override
            public List<NewsItem> digest() {
                return digestResults;
            }

            /** 못 찾음 안내가 "최근 몇 시간"을 말하려면 이 값이 필요하다 — 운영 기본값과 같게 둔다. */
            @Override
            public Duration window() {
                return Duration.ofHours(24);
            }
        };
    }
}
