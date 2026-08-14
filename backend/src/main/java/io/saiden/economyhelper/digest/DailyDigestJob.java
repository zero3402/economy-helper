package io.saiden.economyhelper.digest;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.telegram.MessageFormatter;
import io.saiden.economyhelper.telegram.TelegramClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 오전 9시(KST) 아침 브리핑을 보낸다 — 환율·증시·코인·뉴스 <b>네 통</b>이다.
 *
 * <p>한 통에 다 담지 않는 이유는 성격이 다르기 때문이다. 시세는 한눈에 훑고 뉴스는 읽는다.
 * 게다가 넷을 합치면 텔레그램 한 통 상한(4,096자)에 닿을 수 있다.
 *
 * <p><b>부분 실패를 허용한다.</b> 예전에는 뉴스가 비면 슬롯을 놓아주고 아무것도 안 보냈지만,
 * 이제 넷 중 하나가 죽어도 나머지는 나간다 — 환율이 안 된다고 뉴스까지 막을 이유가 없다.
 * <b>전부 실패했을 때만</b> 슬롯을 되돌려 다음 시도가 가능하게 한다.
 *
 * <p>중복 발송은 <b>두 겹</b>으로 막는다. {@link SchedulerLock}이 인스턴스 간 동시 실행을 막아
 * 수집·번역을 두 번 하지 않게 하고, {@link SendHistory}가 슬롯 단위로 발송 자체를 한 번으로 묶는다.
 */
@Component
public class DailyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);

    /**
     * 슬롯 = <b>KST 날짜</b>. 요구사항이 "하루 한 번"이므로 키도 하루 단위여야 한다.
     *
     * <p>예전에는 시각까지 넣었다({@code 2026-08-13T09}). 09시와 21시에 보내던 때의 흔적인데,
     * 21시가 빠진 뒤로는 <b>재시도를 막는 족쇄</b>가 됐다 — 09시에 못 보내고 09:10에 보내면
     * 다른 슬롯이 되어 같은 브리핑이 두 번 나간다. 날짜로 바꾸면 창 안에서 몇 번을 재시도해도
     * 한 번만 나가고, "정확히 09시에 깨어 있어야 한다"는 요구 자체가 사라진다.
     */
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 통 사이 간격.
     *
     * <p>텔레그램은 같은 채팅방에 <b>초당 한 통</b>을 권고한다. 네 통을 붙여 쏘면 429와
     * {@code retry_after}를 맞을 수 있는데, 하루 한 번 도는 잡이라 그냥 쉬어 가는 편이
     * 재시도 로직을 얹는 것보다 단순하고 확실하다.
     */
    private static final Duration BETWEEN_MESSAGES = Duration.ofSeconds(1);

    /** 브리핑 설정에 대개 들어 있는 코인. 들어 있으면 USDT 원화값을 따로 물을 필요가 없다. */
    private static final String USDT_MARKET = "KRW-USDT";

    private final NewsFacade facade;
    private final FxService fxService;
    private final StockService stockService;
    private final CryptoService cryptoService;
    private final TelegramClient telegram;
    private final SendHistory history;
    private final Clock clock;
    private final ZoneId zone;
    private final List<String> indexNames;
    private final List<String> stockCodes;
    private final List<String> cryptoMarkets;
    private final List<String> usSymbols;

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
        this.history = history;
        this.clock = clock;
        this.zone = ZoneId.of(properties.digest().zone());
        this.indexNames = orEmpty(properties.digest().indices());
        this.stockCodes = orEmpty(properties.digest().stocks());
        this.cryptoMarkets = orEmpty(properties.digest().cryptos());
        this.usSymbols = orEmpty(properties.digest().usSymbols());
    }

    private static List<String> orEmpty(List<String> values) {
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
        run(false);
    }

    /**
     * @param force 이미 보낸 슬롯이어도 다시 보낸다. 수동 트리거로 같은 시간대를 반복
     *              점검할 때만 쓴다
     */
    public DigestResult run(boolean force) {
        String slot = currentSlot();

        boolean claimed = history.claim(slot);
        if (!claimed && !force) {
            // 발송 창 안에서 10분마다 도는 구조라 이 분기가 하루에 열 번 넘게 지나간다.
            // info로 두면 정상 동작이 로그를 덮는다
            log.debug("[digest] {} 슬롯은 이미 발송됐습니다 — 건너뜁니다", slot);
            return DigestResult.skipped(slot, "오늘은 이미 발송했습니다");
        }

        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // 환율을 여기서 한 번만 조회해 증시 통까지 들고 간다. 포매터가 스스로 조회하면
        // 환율 통에 찍힌 값과 미국 종목의 원화 환산이 서로 다를 수 있다 — 같은 발송 안에서
        // 두 숫자가 어긋나면 어느 쪽도 못 믿는다.
        Optional<FxRate> fx = currentFx();

        send("환율", () -> fx.map(MessageFormatter::formatFx), delivered, failed);
        send("증시", () -> stockMessage(fx.orElse(null)), delivered, failed);
        send("코인", this::cryptoMessage, delivered, failed);
        send("뉴스", this::newsMessage, delivered, failed);

        if (delivered.isEmpty()) {
            // 넷 다 실패했다. "보냈다"로 남기면 이 시간대는 복구 후에도 영영 비어 있다
            releaseIfClaimed(claimed, slot);
            log.warn("[digest] {} 슬롯에 보낼 내용이 하나도 없습니다 — 발송하지 않습니다", slot);
            return DigestResult.allFailed(slot, failed);
        }

        log.info("[digest] {} 슬롯 발송 완료 — 성공 {} / 실패 {}", slot, delivered, failed);
        return DigestResult.completed(slot, delivered, failed);
    }

    /**
     * 통 하나를 만들어 보낸다. <b>실패해도 예외를 밖으로 내보내지 않는다</b> —
     * 다음 통이 계속 나가야 하기 때문이다.
     */
    private void send(String section, Supplier<Optional<String>> message,
                      List<String> delivered, List<String> failed) {
        try {
            Optional<String> text = message.get();
            if (text.isEmpty()) {
                log.info("[digest] {} 통에 보낼 내용이 없습니다", section);
                failed.add(section);
                return;
            }
            if (!delivered.isEmpty()) {
                pause();
            }
            telegram.send(text.get());
            delivered.add(section);
        } catch (RuntimeException e) {
            log.error("[digest] {} 통 발송 실패: {}", section, e.toString());
            failed.add(section);
        }
    }

    /** 환율 조회가 실패해도 나머지 통은 나가야 한다 — 예외를 밖으로 내보내지 않는다. */
    private Optional<FxRate> currentFx() {
        try {
            return fxService.usdToKrw();
        } catch (RuntimeException e) {
            log.error("[digest] 환율 조회 실패 — 원화 환산 없이 보냅니다: {}", e.toString());
            return Optional.empty();
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
    private Optional<String> stockMessage(FxRate fx) {
        List<StockQuote> quotes = new ArrayList<>(stockService.indicesOf(indexNames));
        quotes.addAll(stockService.quotesOf(stockCodes));
        quotes.addAll(stockService.usQuotesOf(usSymbols));
        return quotes.isEmpty() ? Optional.empty()
                : Optional.of(MessageFormatter.formatStockDigest(quotes, fx));
    }

    private Optional<String> cryptoMessage() {
        List<CryptoQuote> quotes = cryptoService.quotesOf(cryptoMarkets);
        if (quotes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MessageFormatter.formatCryptoDigest(quotes, usdtKrw(quotes)));
    }

    /**
     * 바이낸스 USDT 값을 원화로 옮길 기준.
     *
     * <p><b>부르지 않아도 되면 부르지 않는다.</b> 두 가지다. 하나, 바이낸스가 하나도 안 붙었으면
     * 환산할 대상이 없다. 둘, 브리핑 설정에 {@code KRW-USDT}가 들어 있으면 그 값은 <b>방금 받아
     * 온 목록 안에</b> 있다 — {@code crypto-price} 캐시는 마켓 목록 단위로 키를 잡아
     * {@code [KRW-USDT]} 단건 조회는 캐시에 걸리지 않고 그대로 업비트 호출로 나간다.
     *
     * @return 원화값. 구할 수 없거나 쓸 일이 없으면 {@code null}
     */
    private BigDecimal usdtKrw(List<CryptoQuote> quotes) {
        if (quotes.stream().noneMatch(quote -> quote.binance().hasPrice())) {
            return null;
        }
        return quotes.stream()
                .filter(quote -> USDT_MARKET.equals(quote.market()) && quote.upbit().hasPrice())
                .map(quote -> quote.upbit().price())
                .findFirst()
                .orElseGet(() -> cryptoService.usdtKrw().orElse(null));
    }

    private Optional<String> newsMessage() {
        List<NewsItem> items = facade.digest();
        return items.isEmpty() ? Optional.empty() : Optional.of(MessageFormatter.formatDigest(items));
    }

    private static <T> Optional<String> quotesOrEmpty(List<T> quotes,
                                                      java.util.function.Function<List<T>, String> format) {
        return quotes.isEmpty() ? Optional.empty() : Optional.of(format.apply(quotes));
    }

    private static void pause() {
        try {
            Thread.sleep(BETWEEN_MESSAGES.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseIfClaimed(boolean claimed, String slot) {
        // force로 들어와 남의 선점을 지나쳤을 수 있다. 내가 잡은 것만 되돌린다
        if (claimed) {
            history.release(slot);
        }
    }

    private String currentSlot() {
        return clock.instant().atZone(zone).format(SLOT_FORMAT);
    }
}
