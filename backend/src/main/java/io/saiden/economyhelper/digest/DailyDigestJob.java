package io.saiden.economyhelper.digest;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.StockOutlook;
import io.saiden.economyhelper.market.chart.ChartImage;
import io.saiden.economyhelper.market.chart.DailyBar;
import io.saiden.economyhelper.support.FailureReason;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.telegram.Charts;
import io.saiden.economyhelper.telegram.CryptoFormatter;
import io.saiden.economyhelper.telegram.FxFormatter;
import io.saiden.economyhelper.telegram.NewsFormatter;
import io.saiden.economyhelper.telegram.StockFormatter;
import io.saiden.economyhelper.telegram.TelegramClient;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * <p><b>뉴스 갈래는 기사마다 한 통이라 실제 발송은 최대 열세 통이다</b>(시세 셋 + 뉴스 열).
 * 텔레그램이 미리보기 카드를 메시지 맨 아래에 하나만 붙여, 여러 건을 묶으면 첫 기사 카드가
 * 마지막 기사 것처럼 보인다 — 실제로 그렇게 나갔다.
 *
 * <p>뉴스가 열 건인 것은 코인 다섯 + 경제 다섯이기 때문이다({@code NewsService.digest}).
 * 같은 방에 초당 한 통이라(간격은 {@code TelegramClient}가 지킨다) 스물네 통이 ~25초인데
 * <b>발송 창이 두 시간</b>이라 늦어지는 것이 문제가 되지 않는다.
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
    private final TelegramClient telegram;
    private final DigestSlot slot;
    private final List<Index> indexNames;
    private final List<String> stockCodes;
    private final List<String> cryptoMarkets;
    private final List<UsSymbol> usSymbols;

    public DailyDigestJob(NewsFacade facade,
                          FxService fxService,
                          StockService stockService,
                          CryptoService cryptoService,
                          TelegramClient telegram,
                          SendHistory history,
                          Clock clock,
                          EconomyHelperProperties properties) {
        this.facade = facade;
        this.fxService = fxService;
        this.stockService = stockService;
        this.cryptoService = cryptoService;
        this.telegram = telegram;
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

        // 환율은 두 통이 함께 쓴다 — 여기서 한 번만 조회해 증시 통까지 들고 간다. 포매터가
        // 스스로 조회하면 환율 통에 찍힌 값과 미국 종목의 원화 환산이 서로 다를 수 있다.
        FxRate fx = fxService.orNull();

        // 통 하나 안에서 글과 차트가 나눠 쓰는 값들. 캐시를 새로 만들지 않는다 —
        // 브리핑 한 번보다 오래 살 값이 아니고, 통마다 제 것을 들고 있으면 그만이다
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
        // 네 통의 수집을 겹친다. 서로 무관한 외부 호출인데 줄줄이 기다렸고, 그중 뉴스 하나가
        // (피드 5 + Gemini 10) 대부분을 차지했다.
        List<Section> sections = Concurrently.map(List.of(
                section("환율", () -> fx == null ? List.of() : List.of(FxFormatter.format(fx)),
                        false, () -> chartOf("환율", "KRW", fxService::dailyBars)),
                // 증시 통은 실리는 것마다 차트가 한 장씩 붙는다(지수 넷 · 국내 종목 · 미국 종목).
                // 못 그린 것만 빠지고 통은 그대로 나간다 — 보충이지 폴백이 아니다
                // ⚠️ 조회를 **한 번만** 한다. 예전에는 글이 answersOf/usAnswersOf를 부르고
                //    차트가 같은 둘을 그대로 다시 불렀다 — 시세 캐시가 1분인데 증시 통 하나가
                //    KIS를 시세 9회 + 일봉 8회 쓰고 호출 사이 1초를 지키므로, 캐시가 그 사이에
                //    식으면 두 번째 조회가 호출을 통째로 다시 태운다. 게다가 그때는 글에 찍힌
                //    값과 차트 캡션이 **서로 다른 조회**에서 온 것이 된다
                section("증시", () -> stockMessage(fx, domestic.get(), american.get()), false,
                        () -> stockCharts(domestic.get(), american.get())),
                section("코인", () -> cryptoMessage(fx, coins.get()), false,
                        () -> cryptoCharts(coins.get())),
                // 뉴스 통만 미리보기를 켠다 — 링크가 있는 통이 여기뿐이다.
                // 기사마다 통을 쪼개므로 통마다 그 기사의 카드가 붙는다
                section("뉴스", this::newsMessages, true)), Supplier::get);

        // 발송은 순서대로 — 텔레그램이 같은 방에 초당 한 통을 권고한다(TelegramClient가 간격을 지킨다)
        for (Section section : sections) {
            send(section, delivered, failed);
        }
    }

    /**
     * <b>한 번만 계산하고 그 값을 다시 준다</b> — 통 하나의 글과 차트가 같은 조회를 나눠 쓴다.
     *
     * <p><b>캐시가 아니다.</b> 브리핑 한 번보다 오래 살지 않고, 키도 만료도 없다. 캐시로
     * 만들려면 이름과 타입과 수명을 정해야 하는데(이 저장소는 「캐시 이름 하나에 타입 하나」다)
     * 여기서 필요한 것은 <b>한 통 안에서 두 번 묻지 않는 것</b>뿐이다.
     *
     * <p><b>동기화하지 않는다.</b> {@code section(...)}이 글을 먼저 모으고 그 다음 차트를
     * 부르므로 둘이 <b>같은 스레드</b>에서 차례로 돈다. 통끼리는 겹쳐 돌지만 통마다 제 것을
     * 들고 있어 공유되지 않는다.
     *
     * <p><b>실패는 기억하지 않는다.</b> 기억할 필요가 없어서다 — {@code message}가 던지면
     * {@code section}이 거기서 통을 접고 차트를 아예 안 부른다. 그래서 {@code get()}이 두 번째로
     * 불리는 경우는 <b>첫 번째가 성공했을 때뿐</b>이다.
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
     * <p><b>본문이 목록이다.</b> 뉴스는 기사마다 한 통으로 나가고(텔레그램이 미리보기 카드를
     * 메시지 맨 아래에 하나만 붙여서, 묶어 보내면 첫 기사 카드가 마지막 기사 것처럼 보인다)
     * 나머지 셋은 한 통짜리 목록일 뿐이다. 성격이 다른 게 아니라 개수가 다를 뿐이라
     * 뉴스만 다른 경로로 보내지 않는다.
     *
     * @param texts   보낼 본문들. 비어 있으면 {@code failure}에 이유가 있다
     * @param failure 실패 사유. 성공이면 {@code null}
     * @param preview 링크 미리보기를 띄울지. 기사를 담은 통만 참이다
     * @param charts  통에 딸린 차트들 — <b>종목마다 한 장</b>이고 글 다음에 순차로 나간다.
     *                텍스트 통은 그대로 둔다: 「무리 하나가 통 하나처럼 끝맺는다」가 출처·기준을
     *                한 번만 적기 위해 있는 규칙이라, 쪼개면 「국내」·「미국」과 출처 줄이
     *                종목마다 되풀이된다. 글이 요약을 맡고 사진이 차트를 맡는다
     */
    private record Section(String name, List<String> texts, String failure, boolean preview,
                           List<ChartImage> charts) {

        static Section of(String name, List<String> texts, boolean preview) {
            return new Section(name, texts, null, preview, List.of());
        }
    }

    /**
     * 수집을 <b>예외 없이</b> 끝낸다.
     *
     * <p>동시에 도는 자리라 예외가 그대로 올라가면 <b>다른 통까지 함께 죽는다</b> —
     * "넷 중 하나가 실패해도 나머지는 나간다"가 여기서 깨진다. 사유를 값으로 바꿔 들고 간다.
     */
    private Supplier<Section> section(String name, Supplier<List<String>> message) {
        return section(name, message, false);
    }

    private Supplier<Section> section(String name, Supplier<List<String>> message,
                                      boolean preview) {
        return section(name, message, preview, List::of);
    }

    /**
     * @param charts 통에 딸릴 차트들. <b>여기서 실패해도 통은 나간다</b> — 차트는 보충이지
     *               답이 아니다. 그래서 글을 모으는 {@code message}와 달리 이 공급자의 실패는
     *               통을 죽이지 않는다
     */
    private Supplier<Section> section(String name, Supplier<List<String>> message,
                                      boolean preview, Supplier<List<ChartImage>> charts) {
        return () -> {
            try {
                List<String> texts = message.get();
                if (texts.isEmpty()) {
                    log.info("[digest] {} 통에 보낼 내용이 없습니다", name);
                    return new Section(name, List.of(), "보낼 내용이 없습니다", preview, List.of());
                }
                return new Section(name, texts, null, preview, chartsOrNone(name, charts));
            } catch (RuntimeException e) {
                log.error("[digest] {} 통 수집 실패: {}", name, e.toString());
                return new Section(name, List.of(), DigestResult.Failure.of(name, e).reason(),
                        preview, List.of());
            }
        };
    }

    /**
     * 브리핑 증시 통의 차트 — <b>지수와 국내 종목마다 한 장.</b>
     *
     * <p><b>순서가 통의 글과 같다</b>(국내 지수 → 국내 종목 → 미국 지수). 사진이 글 뒤에
     * 줄줄이 나가므로 순서가 어긋나면 어느 값의 그림인지 세어 봐야 한다.
     *
     * <p>⚠️ <b>지수는 단위가 없다</b>({@code null}). 코스피에 {@code KRW}를 붙이면 「6,869 KRW」가
     * 되어 값이 아닌 것에 통화를 붙이는 셈이다 — {@code StockQuote.Money.NONE}과 같은 판단이고
     * {@code ChartCaption}이 이미 그 경우를 다룬다.
     *
     * <p><b>이제 빠지는 것이 없다.</b> 미국 종목이 마지막 구멍이었는데 지수용으로 만든 경로가
     * 종목 심볼도 받는 것을 실측으로 확인해(2026-08-21 {@code AAPL}·{@code NVDA}·{@code ORCL})
     * 같은 메서드로 붙었다. 그래도 <b>실패하면 그 한 장만 빠진다</b> — 보충이지 폴백이 아니다.
     *
     * <p>⚠️ <b>KIS 호출이 통마다 늘어난다.</b> 호출 사이 1초를 지키므로 그림 수만큼 늦어진다
     * (설정 그대로면 지수 넷 + 국내 종목 + 미국 종목 둘). 발송 창이 두 시간이라 문제가 되지
     * 않고, 12시간 캐시가 그것을 하루 한 번으로 눌러 준다.
     */
    private List<ChartImage> stockCharts(List<StockService.Answer> domestic,
                                        List<StockService.Answer> american) {
        List<ChartImage> charts = new ArrayList<>();
        for (Index index : indexNames) {
            charts.addAll(chartOf(index.name(), null,
                    StockService.Series.domesticIndex(index.name())));
        }
        // 글이 쓴 그 답을 그대로 받는다 — 다시 조회하면 캡션이 다른 조회의 값을 말할 수 있다
        charts.addAll(chartsOf(domestic));
        charts.addAll(chartsOf(american));
        return charts;
    }

    /**
     * 답마다 차트 한 장 — <b>열쇠가 없는 것만 빠진다.</b>
     *
     * <p>단위는 시세가 든 통화를 그대로 쓴다. 지수는 {@code Money.NONE}이라 {@code null}이 되고
     * caption이 숫자만 적는다 — 「6,869.83 KRW」라고 적을 근거가 없다.
     */
    private List<ChartImage> chartsOf(List<StockService.Answer> answers) {
        List<ChartImage> charts = new ArrayList<>();
        for (StockService.Answer answer : answers) {
            if (answer.series() == null) {
                continue;
            }
            charts.addAll(chartOf(answer.quote().name(), Charts.unitOf(answer.quote()), answer.series()));
        }
        return charts;
    }

    /** 브리핑 코인 통의 차트 — 코인마다 한 장. 업비트는 키가 없고 한 호출로 열나흘을 준다. */
    private List<ChartImage> cryptoCharts(List<CryptoQuote> quotes) {
        List<ChartImage> charts = new ArrayList<>();
        for (CryptoQuote quote : quotes) {
            if (quote.market() == null) {
                continue;
            }
            charts.addAll(chartOf(quote.name(), "KRW",
                    () -> cryptoService.dailyBars(quote.market())));
        }
        return charts;
    }

    /** 차트 수집은 실패해도 삼킨다 — 값은 이미 통에 담겼다. */
    private List<ChartImage> chartsOrNone(String name, Supplier<List<ChartImage>> charts) {
        try {
            return charts.get();
        } catch (RuntimeException e) {
            log.info("[digest] {} 통의 차트를 빼고 보냅니다: {}", name, FailureReason.of(e));
            return List.of();
        }
    }

    /**
     * 일봉 하나를 사진으로 — <b>못 그리면 빈 값</b>이라 그 종목만 빠진다.
     *
     * <p>세 도메인이 같은 규칙을 쓰므로 한 자리에 둔다.
     */
    private List<ChartImage> chartOf(String subject, String unit,
                                     StockService.Series series) {
        return chartOf(subject, unit, () -> stockService.dailyBarsOf(series));
    }

    /** 검색 경로와 같은 규칙·같은 로그다 — {@link Charts}가 한 곳에 든다. */
    private static List<ChartImage> chartOf(String subject, String unit, Supplier<List<DailyBar>> bars) {
        return Charts.of("digest", subject, unit, bars).map(List::of).orElse(List.of());
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
        try {
            // 같은 방에 초당 한 통은 TelegramClient가 방마다 지킨다 — 여기서는 순서만 정한다.
            // 예전에는 통마다 1초를 잤고 그것이 HTTP 시간과 합산돼 브리핑 24통에 18초를 더 태웠다
            for (String text : section.texts()) {
                telegram.send(text, section.preview());
                // 통 단위가 아니라 이름 단위로 센다. 뉴스 세 통이 '뉴스'로 한 번만 남아야
                // 결과가 "무엇이 나갔나"로 읽힌다
                if (!delivered.contains(section.name())) {
                    delivered.add(section.name());
                }
            }
            // 사진은 글 다음에 종목마다 한 장씩
            for (ChartImage chart : section.charts()) {
                telegram.sendPhoto(chart.png(), chart.caption());
            }
        } catch (RuntimeException e) {
            log.error("[digest] {} 통 발송 실패: {}", section.name(), e.toString());
            failed.add(DigestResult.Failure.of(section.name(), e));
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
    private List<String> stockMessage(FxRate fx, List<StockService.Answer> domestic,
                                      List<StockService.Answer> american) {
        List<StockQuote> quotes = new ArrayList<>(stockService.indicesOf(indexNames));
        // 종목에만 전망이 붙는다 — 지수에는 목표주가를 낼 주체가 없어 StockService가 걸러낸다.
        // 국내와 미국을 한 지도에 담는다: 화면은 무리로 가르지만 전망은 종목마다 붙는다
        Map<StockQuote, StockOutlook> outlooks = new java.util.HashMap<>();
        collect(domestic, quotes, outlooks);
        collect(american, quotes, outlooks);
        return quotes.isEmpty() ? List.of() : List.of(StockFormatter.format(quotes, fx, outlooks));
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
    private List<String> cryptoMessage(FxRate fx, List<CryptoQuote> quotes) {
        return quotes.isEmpty() ? List.of() : List.of(CryptoFormatter.format(quotes, fx));
    }

    private List<String> newsMessages() {
        List<NewsItem> items = facade.digest();
        return items.isEmpty() ? List.of() : NewsFormatter.formatAll(items);
    }
}
