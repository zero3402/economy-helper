package io.saiden.economyhelper.digest.application;

import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.crypto.application.CryptoService;
import io.saiden.economyhelper.crypto.domain.CryptoQuote;
import io.saiden.economyhelper.digest.application.port.out.DigestMessage;
import io.saiden.economyhelper.digest.application.port.out.DigestNotifier;
import io.saiden.economyhelper.digest.application.port.out.SendHistory;
import io.saiden.economyhelper.digest.domain.DigestResult;
import io.saiden.economyhelper.fx.application.FxService;
import io.saiden.economyhelper.fx.domain.FxRate;
import io.saiden.economyhelper.news.application.NewsFacade;
import io.saiden.economyhelper.news.domain.NewsItem;
import io.saiden.economyhelper.shared.domain.DailyBar;
import io.saiden.economyhelper.shared.support.Concurrently;
import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.stock.application.StockService;
import io.saiden.economyhelper.stock.domain.StockOutlook;
import io.saiden.economyhelper.stock.domain.StockQuote;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 9시(KST) 아침 브리핑을 보낸다 — 환율·증시·코인·뉴스 <b>네 갈래</b>다.
 *
 * <p>한 통에 다 담지 않는 이유는 성격이 다르기 때문이다. 시세는 한눈에 훑고 뉴스는 읽는다.
 * 게다가 넷을 합치면 텔레그램 한 통 상한(4,096자)에 닿을 수 있다.
 *
 * <p><b>뉴스 갈래는 기사마다 한 통이다</b> — 글 열세 통(시세 셋 + 뉴스 열)에 차트 사진이 붙어
 * 최대 스물네 통이 나간다. 묶지 않는 이유는 텔레그램이 미리보기 카드를 메시지 맨 아래에 하나만
 * 붙여서다 — 여러 건을 묶으면 첫 기사 카드가 마지막 기사 것처럼 보인다(실제로 그렇게 나갔다).
 *
 * <p>뉴스가 열 건인 것은 코인 다섯 + 경제 다섯이기 때문이다({@code NewsService.digest}).
 * 같은 방에 초당 한 통이라(간격은 창구 — {@link DigestNotifier} 구현이 지킨다) ~25초가 걸리는데
 * <b>발송 창이 두 시간</b>이라 늦어지는 것이 문제가 되지 않는다 → ADR-0015.
 *
 * <p><b>부분 실패를 허용한다.</b> 넷 중 하나가 죽어도 나머지는 나간다 — 환율이 안 된다고
 * 뉴스까지 막을 이유가 없다. <b>전부 실패했을 때만</b> 슬롯을 되돌려 다음 시도를 열어 둔다.
 *
 * <p>중복 발송은 <b>두 겹</b>으로 막는다. {@link SchedulerLock}이 인스턴스 간 동시 실행을 막아
 * 수집·번역을 두 번 하지 않게 하고, {@link SendHistory}가 슬롯 단위로 발송 자체를 한 번으로 묶는다.
 */
@Component
public class DailyDigestJob extends TriggerableJob {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);

    /**
     * 슬롯 접두사가 <b>비어 있다.</b> 이미 돌고 있는 키를 그대로 두기 위해서다 — 여기서
     * 이름을 바꾸면 배포 직후의 슬롯이 "안 보낸 것"으로 보여 브리핑이 한 번 더 나간다.
     * 나중에 붙는 잡이 접두사를 반드시 정하도록 {@link DigestSlot}이 값을 요구한다.
     */
    private static final String SLOT_PREFIX = "";

    private final NewsFacade facade;
    private final FxService fxService;
    private final StockService stockService;
    private final CryptoService cryptoService;
    private final DigestNotifier notifier;
    private final DigestSlot slot;
    private final List<Index> indexNames;
    private final List<String> stockCodes;
    private final List<String> cryptoMarkets;
    private final List<UsSymbol> usSymbols;

    public DailyDigestJob(NewsFacade facade,
                          FxService fxService,
                          StockService stockService,
                          CryptoService cryptoService,
                          DigestNotifier notifier,
                          SendHistory history,
                          Clock clock,
                          EconomyHelperProperties properties) {
        this.facade = facade;
        this.fxService = fxService;
        this.stockService = stockService;
        this.cryptoService = cryptoService;
        this.notifier = notifier;
        this.slot = new DigestSlot(history, clock, ZoneId.of(properties.digest().zone()),
                SLOT_PREFIX, "digest");
        this.indexNames = orEmpty(properties.digest().indices());
        this.stockCodes = orEmpty(properties.digest().stocks());
        this.cryptoMarkets = orEmpty(properties.digest().cryptos());
        this.usSymbols = orEmpty(properties.digest().usSymbols());
    }

    /** 설정 목록이 비어 있어도 브리핑이 죽지 않게 한다 — 그 통만 빠진다. */
    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 스케줄 진입점.
     *
     * <p>{@code @SchedulerLock}은 프록시로 걸리므로 <b>이 메서드에만</b> 유효하다.
     * {@link #run(boolean)}을 여기서 직접 부르는 건 자기 호출이라 락을 타지 않는다 —
     * 의도한 것이다. 락은 스케줄 실행에만 필요하고, 수동 트리거는 슬롯 선점으로 충분하다.
     */
    @Scheduled(cron = "${economy-helper.digest.cron}", zone = "${economy-helper.digest.zone}")
    @SchedulerLock(name = "dailyDigest", lockAtLeastFor = "PT5M", lockAtMostFor = "PT20M")
    public void sendScheduled() {
        DigestResult result = run(false);
        // 스케줄 경로는 아무도 응답을 보지 않는다. 실패했으면 여기서라도 크게 남겨야
        // "아침에 아무것도 안 왔다"가 다음 날에야 발견되는 일을 막는다
        if (!result.sent() && !result.failed().isEmpty()) {
            log.error("[digest] 스케줄 발송이 아무것도 내보내지 못했습니다: {}", result.failed());
        }
    }

    @Override
    protected DigestResult execute(boolean force) {
        DigestSlot.Claim claim = slot.claim(force);
        if (!claim.proceed()) {
            return DigestResult.skipped(claim.id(), claim.blockedReason());
        }

        List<String> delivered = new ArrayList<>();
        List<DigestResult.Failure> failed = new ArrayList<>();

        // 환율은 두 통이 함께 쓴다 — 여기서 한 번만 조회해 증시 통까지 들고 간다. 통마다
        // 따로 조회하면 환율 통에 찍힌 값과 미국 종목의 원화 환산이 서로 다를 수 있다.
        FxRate fx = fxService.orNull();

        // 통 하나 안에서 글과 차트가 나눠 쓰는 값들
        Once<List<StockService.Answer>> domestic = Once.of(() -> stockService.answersOf(stockCodes));
        Once<List<StockService.Answer>> american = Once.of(() -> stockService.usAnswersOf(usSymbols));
        Once<List<CryptoQuote>> coins = Once.of(() -> cryptoService.quotesOf(cryptoMarkets));

        // ⚠️ 여기부터 release 판단까지는 **무엇이 새어도 슬롯을 되돌려야 한다.** section()이
        //    RuntimeException을 값으로 바꾸지만 Error와 인터럽트(Concurrently가 IllegalStateException으로
        //    올린다 — 배포 중 종료가 그 경로)는 그대로 새고, 그러면 슬롯은 잡힌 채 아무것도 안 나가
        //    그날 창의 나머지 틱이 전부 「이미 보냈다」가 된다. 한 통이라도 나갔으면 되돌리지 않는다
        try {
            collectAndSend(fx, domestic, american, coins, delivered, failed);
        } catch (RuntimeException | Error e) {
            if (delivered.isEmpty()) {
                slot.release(claim);
                log.error("[digest] {} 수집 중 예외 — 슬롯을 되돌립니다: {}", claim.id(), e.toString());
            }
            throw e;
        }

        if (delivered.isEmpty()) {
            // 넷 다 실패했다. "보냈다"로 남기면 이 시간대는 복구 후에도 영영 비어 있다
            slot.release(claim);
            log.warn("[digest] {} 슬롯에 보낼 내용이 하나도 없습니다 — 발송하지 않습니다", claim.id());
            return DigestResult.allFailed(claim.id(), failed);
        }

        log.info("[digest] {} 슬롯 발송 완료 — 성공 {} / 실패 {}", claim.id(), delivered, failed);
        return DigestResult.completed(claim.id(), delivered, failed);
    }

    /** 네 통을 겹쳐 모아 순서대로 보낸다. 슬롯 되돌림은 부르는 쪽({@link #execute})의 몫이다. */
    private void collectAndSend(FxRate fx, Once<List<StockService.Answer>> domestic,
                                Once<List<StockService.Answer>> american, Once<List<CryptoQuote>> coins,
                                List<String> delivered, List<DigestResult.Failure> failed) {
        // 네 통의 수집을 겹친다 — 서로 무관한 외부 호출이고 뉴스(피드 + Gemini)가 가장 길다.
        List<Section> sections = Concurrently.map(List.of(
                section("환율", () -> fx == null ? null : new DigestMessage.Fx(fx),
                        () -> List.of(chartOf("환율", "KRW", fxService::dailyBars))),
                // ⚠️ 글과 차트가 조회를 **한 번만** 나눠 쓴다. 따로 부르면 증시 통 하나가 KIS를
                //    시세 9회 + 일봉 8회(호출 사이 1초) 쓰는 사이 1분 시세 캐시가 식어 호출을 다시
                //    태우고, 글의 값과 차트 캡션이 서로 다른 조회에서 온다
                section("증시", () -> stockMessage(fx, domestic.get(), american.get()),
                        () -> stockCharts(domestic.get(), american.get())),
                section("코인", () -> cryptoMessage(fx, coins.get()),
                        () -> cryptoCharts(coins.get())),
                section("뉴스", this::newsMessage, List::of)), Supplier::get);

        // 발송은 순서대로 — 텔레그램이 같은 방에 초당 한 통을 권고한다(간격은 창구가 지킨다)
        for (Section section : sections) {
            send(section, delivered, failed);
        }
    }

    /**
     * <b>한 번만 계산하고 그 값을 다시 준다</b> — 통 하나의 글과 차트가 같은 조회를 나눠 쓴다.
     *
     * <p><b>캐시가 아니다</b> — 브리핑 한 번보다 오래 살지 않아 키도 만료도 없다.
     * <b>동기화하지 않는다</b> — 한 통의 글과 차트는 같은 스레드에서 차례로 돌고, 통마다 제 것을 든다.
     * <b>실패는 기억하지 않는다</b> — {@code message}가 던지면 통이 접혀 차트를 아예 안 부른다.
     */
    private static final class Once<T> implements Supplier<T> {

        private final Supplier<T> work;
        private boolean computed;
        private T value;

        private Once(Supplier<T> work) {
            this.work = work;
        }

        static <T> Once<T> of(Supplier<T> work) {
            return new Once<>(work);
        }

        /** {@code null}을 sentinel로 쓰지 않는다 — 값이 {@code null}이면 매번 다시 계산된다. */
        @Override
        public T get() {
            if (!computed) {
                value = work.get();
                computed = true;
            }
            return value;
        }
    }

    /**
     * 통 하나의 수집 결과.
     *
     * <p><b>뉴스도 한 통의 값이다.</b> 기사마다 한 통으로 쪼개는 것은 창구가 적을 때 정한다.
     *
     * @param message 보낼 값. {@code null}이면 {@code failure}에 이유가 있다
     * @param failure 실패 사유. 성공이면 {@code null}
     * @param charts  통에 딸린 차트들 — <b>종목마다 한 장</b>이고 글 다음에 순차로 나간다.
     *                텍스트 통은 그대로 둔다: 「무리 하나가 통 하나처럼 끝맺는다」가 출처·기준을
     *                한 번만 적기 위해 있는 규칙이라, 쪼개면 「국내」·「미국」과 출처 줄이
     *                종목마다 되풀이된다. 글이 요약을 맡고 사진이 차트를 맡는다
     */
    private record Section(String name, DigestMessage message, String failure,
                           List<DigestMessage.Chart> charts) {
    }

    /**
     * 수집을 <b>예외 없이</b> 끝낸다.
     *
     * <p>동시에 도는 자리라 예외가 그대로 올라가면 <b>다른 통까지 함께 죽는다</b> —
     * "넷 중 하나가 실패해도 나머지는 나간다"가 여기서 깨진다. 사유를 값으로 바꿔 들고 간다.
     *
     * @param message 보낼 값. {@code null}이면 보낼 내용이 없다
     * @param charts  통에 딸릴 차트들. <b>여기서 실패해도 통은 나간다</b> — 차트는 보충이지
     *                답이 아니다. 그래서 값을 모으는 {@code message}와 달리 이 공급자의 실패는
     *                통을 죽이지 않는다
     */
    private Supplier<Section> section(String name, Supplier<DigestMessage> message,
                                      Supplier<List<DigestMessage.Chart>> charts) {
        return () -> {
            try {
                DigestMessage value = message.get();
                if (value == null) {
                    log.info("[digest] {} 통에 보낼 내용이 없습니다", name);
                    return new Section(name, null, "보낼 내용이 없습니다", List.of());
                }
                return new Section(name, value, null, chartsOrNone(name, charts));
            } catch (RuntimeException e) {
                log.error("[digest] {} 통 수집 실패: {}", name, e.toString());
                return new Section(name, null, DigestResult.Failure.of(name, e).reason(), List.of());
            }
        };
    }

    /**
     * 브리핑 증시 통의 차트 — <b>지수와 종목마다 한 장.</b> 실패하면 그 한 장만 빠진다.
     *
     * <p><b>순서가 통의 글과 같다.</b> 사진이 글 뒤에 줄줄이 나가므로 순서가 어긋나면 어느 값의
     * 그림인지 세어 봐야 한다.
     *
     * <p>⚠️ <b>지수는 단위가 없다</b>({@code null}) — {@code StockQuote.Money.unit()}이 정한다.
     *
     * <p>⚠️ <b>KIS 호출이 통마다 늘어난다.</b> 호출 사이 1초를 지키므로 그림 수만큼 늦어진다
     * (설정 그대로면 지수 넷 + 국내 종목 + 미국 종목 둘). 발송 창이 두 시간이라 문제가 되지
     * 않고, 12시간 캐시가 그것을 하루 한 번으로 눌러 준다.
     */
    private List<DigestMessage.Chart> stockCharts(List<StockService.Answer> domestic,
                                                  List<StockService.Answer> american) {
        List<DigestMessage.Chart> charts = new ArrayList<>();
        for (Index index : indexNames) {
            charts.add(chartOf(index.name(), null, StockService.Series.domesticIndex(index.name())));
        }
        // 글이 쓴 그 답을 그대로 받는다 — 다시 조회하면 캡션이 다른 조회의 값을 말할 수 있다
        charts.addAll(chartsOf(domestic));
        charts.addAll(chartsOf(american));
        return charts;
    }

    /** 답마다 차트 한 장 — <b>열쇠가 없는 것만 빠진다.</b> 단위는 시세가 든 통화 그대로다. */
    private List<DigestMessage.Chart> chartsOf(List<StockService.Answer> answers) {
        List<DigestMessage.Chart> charts = new ArrayList<>();
        for (StockService.Answer answer : answers) {
            if (answer.series() == null) {
                continue;
            }
            StockQuote quote = answer.quote();
            charts.add(chartOf(quote.name(), quote.currency().unit(), answer.series()));
        }
        return charts;
    }

    /** 브리핑 코인 통의 차트 — 코인마다 한 장. 업비트는 키가 없고 한 호출로 열나흘을 준다. */
    private List<DigestMessage.Chart> cryptoCharts(List<CryptoQuote> quotes) {
        List<DigestMessage.Chart> charts = new ArrayList<>();
        for (CryptoQuote quote : quotes) {
            if (quote.market() == null) {
                continue;
            }
            charts.add(chartOf(quote.name(), "KRW", () -> cryptoService.dailyBars(quote.market())));
        }
        return charts;
    }

    /** 차트 수집은 실패해도 삼킨다 — 값은 이미 통에 담겼다. */
    private List<DigestMessage.Chart> chartsOrNone(String name,
                                                   Supplier<List<DigestMessage.Chart>> charts) {
        try {
            return charts.get();
        } catch (RuntimeException e) {
            log.info("[digest] {} 통의 차트를 빼고 보냅니다: {}", name, FailureReason.of(e));
            return List.of();
        }
    }

    private DigestMessage.Chart chartOf(String subject, String unit, StockService.Series series) {
        return chartOf(subject, unit, () -> stockService.dailyBarsOf(series));
    }

    /**
     * 일봉을 <b>지금</b> 받아 둔다 — 통들의 수집과 함께 겹쳐 돌게. 그리는 것은 창구가 보낼 때 한다.
     *
     * <p><b>실패도 받아 둔다.</b> 여기서 삼키지 않고 창구가 그림을 만들 때 다시 던지므로, 「못 받아
     * 뺀다」와 「칸이 모자라 뺀다」를 가르는 로그가 검색 경로와 같은 한 자리({@code Charts})에 남는다.
     */
    private static DigestMessage.Chart chartOf(String subject, String unit, Supplier<List<DailyBar>> bars) {
        List<DailyBar> fetched;
        try {
            fetched = bars.get();
        } catch (RuntimeException e) {
            return new DigestMessage.Chart(subject, unit, () -> {
                throw e;
            });
        }
        return new DigestMessage.Chart(subject, unit, () -> fetched);
    }

    /**
     * 통 하나를 보낸다. <b>실패해도 예외를 밖으로 내보내지 않는다</b> —
     * 다음 통이 계속 나가야 하기 때문이다.
     *
     * <p>다만 <b>사유는 버리지 않는다.</b> 이름만 남기면 "환율 실패"까지만 알 수 있어
     * 설정이 틀린 것인지 외부 API가 죽은 것인지 구분하려면 배포처 로그를 뒤져야 한다.
     */
    private void send(Section section, List<String> delivered, List<DigestResult.Failure> failed) {
        if (section.failure() != null) {
            failed.add(new DigestResult.Failure(section.name(), section.failure()));
            return;
        }
        // 통 단위가 아니라 이름 단위로 센다. 뉴스 세 통이 '뉴스'로 한 번만 남아야
        // 결과가 "무엇이 나갔나"로 읽힌다
        DigestNotifier.Delivery delivery = notifier.send(section.message(), section.charts(), () -> {
            if (!delivered.contains(section.name())) {
                delivered.add(section.name());
            }
        });
        if (delivery.failure() != null) {
            log.error("[digest] {} 통 발송 실패: {}", section.name(), delivery.failure().toString());
            failed.add(DigestResult.Failure.of(section.name(), delivery.failure()));
        }
    }

    /**
     * 국내·미국 지수와 종목을 한 통에 담는다.
     *
     * <p>조회 API가 셋으로 갈리지만 <b>같은 증시 이야기</b>다 — 따로 보내면 통이 여섯 개가 되고
     * 통 사이 간격도 그만큼 는다. 일부가 죽어도 나머지만으로 통을 만든다.
     *
     * @param fx 미국 종목의 원화 환산에 쓸 환율. {@code null}이면 달러로만 나간다
     */
    private DigestMessage stockMessage(FxRate fx, List<StockService.Answer> domestic,
                                       List<StockService.Answer> american) {
        List<StockQuote> quotes = new ArrayList<>(stockService.indicesOf(indexNames));
        // 종목에만 전망이 붙는다 — 지수에는 목표주가를 낼 주체가 없어 StockService가 걸러낸다.
        // 국내와 미국을 한 지도에 담는다: 화면은 무리로 가르지만 전망은 종목마다 붙는다
        Map<StockQuote, StockOutlook> outlooks = new HashMap<>();
        collect(domestic, quotes, outlooks);
        collect(american, quotes, outlooks);
        return quotes.isEmpty() ? null : new DigestMessage.Stocks(quotes, fx, outlooks);
    }

    /** 받은 답을 시세 목록과 전망 지도로 나눠 담는다 — 국내와 미국이 같은 모양이라 한 자리다. */
    private static void collect(List<StockService.Answer> answers, List<StockQuote> quotes,
                                Map<StockQuote, StockOutlook> outlooks) {
        for (StockService.Answer answer : answers) {
            quotes.add(answer.quote());
            if (answer.outlook() != null) {
                outlooks.put(answer.quote(), answer.outlook());
            }
        }
    }

    /**
     * @param fx 바이낸스 값의 원화 환산과 김프에 쓴다. {@code null}이면 둘 다 빠지고
     *           USDT/USD 값만 나간다 — 환산을 못 한다고 시세를 빼는 것은 과하다
     */
    private DigestMessage cryptoMessage(FxRate fx, List<CryptoQuote> quotes) {
        return quotes.isEmpty() ? null : new DigestMessage.Coins(quotes, fx);
    }

    private DigestMessage newsMessage() {
        List<NewsItem> items = facade.digest();
        return items.isEmpty() ? null : new DigestMessage.News(items);
    }
}
