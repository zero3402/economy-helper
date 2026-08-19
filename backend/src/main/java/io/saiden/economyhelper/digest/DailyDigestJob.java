package io.saiden.economyhelper.digest;

import io.saiden.economyhelper.config.EconomyHelperProperties;
import io.saiden.economyhelper.config.EconomyHelperProperties.Index;
import io.saiden.economyhelper.config.EconomyHelperProperties.UsSymbol;
import io.saiden.economyhelper.market.CryptoQuote;
import io.saiden.economyhelper.market.CryptoService;
import io.saiden.economyhelper.market.FxRate;
import io.saiden.economyhelper.market.FxService;
import io.saiden.economyhelper.market.StockQuote;
import io.saiden.economyhelper.market.StockService;
import io.saiden.economyhelper.news.NewsFacade;
import io.saiden.economyhelper.news.NewsItem;
import io.saiden.economyhelper.support.Concurrently;
import io.saiden.economyhelper.telegram.MessageFormatter;
import io.saiden.economyhelper.telegram.TelegramClient;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>뉴스 갈래는 기사마다 한 통이라 실제 발송은 최대 여섯 통이다.</b> 텔레그램이 미리보기
 * 카드를 메시지 맨 아래에 하나만 붙여, 세 건을 묶으면 첫 기사 카드가 셋째 기사 것처럼 보인다.
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

        // 네 통의 수집을 겹친다. 서로 무관한 외부 호출인데 줄줄이 기다렸고, 그중 뉴스 하나가
        // (피드 5 + Gemini 10) 대부분을 차지했다.
        List<Section> sections = Concurrently.map(List.of(
                section("환율", () -> fx == null ? List.of() : List.of(MessageFormatter.formatFx(fx))),
                section("증시", () -> stockMessage(fx)),
                section("코인", () -> cryptoMessage(fx)),
                // 뉴스 통만 미리보기를 켠다 — 링크가 있는 통이 여기뿐이다.
                // 기사마다 통을 쪼개므로 통마다 그 기사의 카드가 붙는다
                section("뉴스", this::newsMessages, true)), Supplier::get);

        // 발송은 순서대로 — 텔레그램이 같은 방에 초당 한 통을 권고한다(BETWEEN_MESSAGES)
        for (Section section : sections) {
            send(section, delivered, failed);
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
     */
    private record Section(String name, List<String> texts, String failure, boolean preview) {}

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
        return () -> {
            try {
                List<String> texts = message.get();
                if (texts.isEmpty()) {
                    log.info("[digest] {} 통에 보낼 내용이 없습니다", name);
                    return new Section(name, List.of(), "보낼 내용이 없습니다", preview);
                }
                return new Section(name, texts, null, preview);
            } catch (RuntimeException e) {
                log.error("[digest] {} 통 수집 실패: {}", name, e.toString());
                return new Section(name, List.of(), DigestResult.Failure.of(name, e).reason(),
                        preview);
            }
        };
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
            // 통이 여럿이어도 간격은 하나의 규칙이다 — 앞서 보낸 것이 있으면 쉬고 보낸다
            for (String text : section.texts()) {
                if (!delivered.isEmpty()) {
                    TelegramClient.pause();
                }
                telegram.send(text, section.preview());
                // 통 단위가 아니라 이름 단위로 센다. 뉴스 세 통이 '뉴스'로 한 번만 남아야
                // 결과가 "무엇이 나갔나"로 읽힌다
                if (!delivered.contains(section.name())) {
                    delivered.add(section.name());
                }
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
    private List<String> stockMessage(FxRate fx) {
        List<StockQuote> quotes = new ArrayList<>(stockService.indicesOf(indexNames));
        quotes.addAll(stockService.quotesOf(stockCodes));
        quotes.addAll(stockService.usQuotesOf(usSymbols));
        return quotes.isEmpty() ? List.of() : List.of(MessageFormatter.formatStock(quotes, fx));
    }

    /**
     * @param fx 바이낸스 값의 원화 환산과 김프에 쓴다. {@code null}이면 둘 다 빠지고
     *           USDT/USD 값만 나간다 — 환산을 못 한다고 시세를 빼는 것은 과하다
     */
    private List<String> cryptoMessage(FxRate fx) {
        List<CryptoQuote> quotes = cryptoService.quotesOf(cryptoMarkets);
        return quotes.isEmpty() ? List.of() : List.of(MessageFormatter.formatCrypto(quotes, fx));
    }

    private List<String> newsMessages() {
        List<NewsItem> items = facade.digest();
        return items.isEmpty() ? List.of() : MessageFormatter.formatNews(items);
    }
}
