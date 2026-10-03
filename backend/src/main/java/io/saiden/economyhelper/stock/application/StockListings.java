package io.saiden.economyhelper.stock.application;

import io.saiden.economyhelper.shared.support.FailureReason;
import io.saiden.economyhelper.shared.support.QueryNormalizer;
import io.saiden.economyhelper.stock.application.port.out.ListingSource;
import io.saiden.economyhelper.stock.domain.Listing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「이름 → 종목코드」 색인 — {@link ListingSource}(KIS 종목 마스터)가 준 목록 위의 <b>순수 계산</b>이다.
 *
 * <p>규칙은 둘이고, 둘 다 저장소가 다른 곳에서 이미 쓰는 것이다.
 *
 * <ol>
 *   <li>정규화한 이름이 <b>정확히 같은</b> 상장이 있으면 그것 — 여럿이면 시가총액 1위
 *   <li>아니면 질의를 문자 종류(숫자·영문·한글) 단위 토큰으로 쪼개 <b>토큰 전부를 품는</b> 상장 중
 *       <b>시가총액 1위</b> — 공공데이터포털 이름 검색({@code DataGoStockClient.best})과 코인
 *       ({@code 24시간 거래대금 1위})이 동명 후보를 가르는 그 규칙이다
 * </ol>
 *
 * <p><b>LLM 없이 {@code 타임나스닥100}은 빈손이다</b> — {@code 타임}과 {@code TIME}은 다른 글자다.
 * 그 소리를 맞추는 것이 LLM 해석기({@code StockResolver})의 몫이고({@code 제피 → JEPI}와 같은 자리),
 * 여기는 그 답({@code TIME 미국나스닥100액티브})을 코드로 바꾸는 일만 한다. 브랜드 대응표를
 * 두지 않는 이유는 ADR-0003에 있다.
 *
 * <p>{@link #agrees}는 LLM이 준 코드와 이름이 <b>서로 다른 종목을 가리키는지</b> 보는 데 쓴다 —
 * ETF는 이름이 비슷한 코드가 수십 개라 <b>존재하는 틀린 코드</b>가 흔하고, 그러면 KIS가 멀쩡히
 * 답해 다른 ETF가 나간다. 틀린 값이 빈손보다 나쁘다.
 *
 * <h2>프로세스 사본 — Redis 값을 부를 때마다 다시 풀지 않는다</h2>
 *
 * <p>목록은 Redis에 6시간 캐시되지만(인스턴스 간 공유·다운로드 절약) 그 값이 <b>292KB·약 4천 행</b>이다.
 * {@code find} 한 번이 그것을 GET하고 JSON으로 풀고 4천 이름을 전부 정규화했고, {@code /stock} 한 번에
 * 그 일을 최대 네 번 했다. 그래서 정규화한 이름·코드 맵까지 만든 색인을 <b>프로세스에 같은 수명으로</b>
 * 들고 있는다 — {@code KisTokenStore}가 토큰 사본을 두는 그 모양이다.
 *
 * <p>⚠️ 대가: {@code /actuator/evict?name=kr-listings}가 이 사본은 비우지 못한다 — 수명(6시간)이 지나야
 * 다시 읽는다. 상장·폐지가 그 안에 화면에 닿아야 할 일은 없다.
 */
@Component
public class StockListings {

    private static final Logger log = LoggerFactory.getLogger(StockListings.class);

    /** 숫자·영문·한글 무리 하나가 토큰 하나다. 정규화 뒤라 다른 문자는 없다. */
    private static final Pattern TOKEN = Pattern.compile("[0-9]+|[a-z]+|[가-힣]+");

    /** 부분일치를 받는 가장 짧은 질의(정규화 뒤 글자 수). 이보다 짧으면 이름이 딱 맞는 상장만 답한다. */
    private static final int MIN_PARTIAL_LENGTH = 2;

    private final ListingSource source;
    /** 사본을 얼마나 들고 있는가. {@code Duration.ZERO}면 부를 때마다 다시 읽는다 — 테스트가 그렇게 쓴다. */
    private final Duration keepFor;
    private final Clock clock;
    private volatile Index index;

    @Autowired
    public StockListings(ListingSource source,
                         @Value("${economy-helper.cache-ttl.kr-listings:6h}") Duration keepFor,
                         Clock clock) {
        this.source = source;
        this.keepFor = keepFor;
        this.clock = clock;
    }

    /** 목록을 직접 주고 사본은 두지 않는다 — 테스트와, 마스터 없이 돌아야 하는 자리가 쓴다. */
    public StockListings(ListingSource source) {
        this(source, Duration.ZERO, Clock.systemUTC());
    }

    /**
     * @return 이름으로 찾은 상장 하나. 없으면 빈손 — 「없다」는 장애가 아니다
     * @throws RuntimeException 마스터를 못 받았을 때(브레이커 열림 포함). 부르는 쪽이 삼킨다
     */
    public Optional<Listing> find(String query) {
        return index().find(query);
    }

    /**
     * 정규화한 이름이 <b>똑같은</b> 상장만 — 부분일치를 받으면 틀린 값이 나가는 자리가 쓴다.
     *
     * @throws RuntimeException 마스터를 못 받았을 때. 부르는 쪽이 삼킨다
     */
    public Optional<Listing> exact(String name) {
        return index().exact(name);
    }

    /**
     * 사본을 미리 만든다 — 기동 직후 첫 {@code /stock}이 마스터 두 파일(찬 캐시 ~3초)을 기다리지 않게.
     *
     * @throws RuntimeException 마스터를 못 받았을 때. 부르는 쪽이 삼킨다
     */
    public void preload() {
        index();
    }

    /** 코드로 찾는다 — 대소문자를 가리지 않는다(정규화가 소문자로 내린다). */
    public Optional<Listing> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(index().byCode().get(code.toUpperCase(Locale.ROOT)));
    }

    /**
     * 사본이 없거나 낡았으면 다시 만든다. 경쟁하면 둘이 만들 수 있는데 같은 값이라 해롭지 않다.
     *
     * <p>⚠️ <b>다시 받기가 실패하면 낡은 사본을 그대로 쓴다.</b> 상장·폐지는 하루에 몇 건이고 이름 → 코드는
     * 그사이에 거의 안 바뀐다 — 버리면 마스터가 잠깐 죽은 동안 이름 검색이 전부 던진다. 사본이 처음부터
     * 없을 때만 던진다. 다음 호출이 다시 받아 본다.
     */
    private Index index() {
        Index current = index;
        Instant now = clock.instant();
        if (current == null || keepFor.isZero() || current.loadedAt().plus(keepFor).isBefore(now)) {
            try {
                current = Index.of(source.listings(), now);
            } catch (RuntimeException e) {
                if (current == null) {
                    throw e;
                }
                log.warn("[stock] 종목 마스터를 다시 받지 못해 {}에 만든 사본을 씁니다: {}",
                        current.loadedAt(), FailureReason.of(e));
                return current;
            }
            index = current;
        }
        return current;
    }

    /**
     * LLM이 준 코드({@code listed})와 이름이 <b>같은 종목</b>을 가리키는가.
     *
     * <p>⚠️ {@link #agrees}만으로는 모자란다 — 더 긴 이웃({@code 삼성전자우}·{@code …채권혼합50액티브})이
     * 이름의 토큰을 다 품어 통과한다. 그래서 <b>이름이 딱 맞는 상장이 있으면 그것과 코드가 같아야</b> 하고,
     * 없을 때만(넓은 이름 {@code TIME 나스닥100}) 품는지로 본다.
     *
     * @throws RuntimeException 마스터를 못 받았을 때. 부르는 쪽이 삼킨다
     */
    public boolean sameListing(Listing listed, String name) {
        return index().exact(name)
                .map(exact -> exact.code().equalsIgnoreCase(listed.code()))
                .orElseGet(() -> agrees(listed, name));
    }

    /** {@code name}의 토큰이 전부 상장명에 들어 있는가 — {@code 삼성전자}↔{@code 삼성전자}는 참, {@code 네이버}↔{@code NAVER}는 거짓. */
    public static boolean agrees(Listing listing, String name) {
        String wanted = QueryNormalizer.normalize(name);
        return !wanted.isEmpty() && containsAll(QueryNormalizer.normalize(listing.name()), tokens(wanted));
    }

    private static boolean containsAll(String name, List<String> tokens) {
        return tokens.stream().allMatch(name::contains);
    }

    private static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /** 이름을 한 번 정규화해 둔 상장 — 조회마다 4천 번 정규화하지 않기 위해서다. */
    private record Entry(Listing listing, String normalizedName) {}

    /** 목록 한 판의 색인. 만든 시각을 들고 있어 수명을 잰다. */
    private record Index(Instant loadedAt, List<Entry> entries, Map<String, Listing> byCode) {

        static Index of(List<Listing> listings, Instant at) {
            List<Entry> entries = new ArrayList<>(listings.size());
            Map<String, Listing> byCode = new HashMap<>(listings.size() * 2);
            for (Listing listing : listings) {
                entries.add(new Entry(listing, QueryNormalizer.normalize(listing.name())));
                byCode.putIfAbsent(listing.code().toUpperCase(Locale.ROOT), listing);
            }
            return new Index(at, List.copyOf(entries), Map.copyOf(byCode));
        }

        /** 정규화한 이름이 <b>똑같은</b> 상장 중 시가총액 1위. */
        Optional<Listing> exact(String query) {
            String wanted = QueryNormalizer.normalize(query);
            if (wanted.isEmpty()) {
                return Optional.empty();
            }
            return entries.stream()
                    .filter(entry -> entry.normalizedName().equals(wanted))
                    .map(Entry::listing)
                    .max(Comparator.comparingLong(Listing::marketCap));
        }

        Optional<Listing> find(String query) {
            String wanted = QueryNormalizer.normalize(query);
            if (wanted.isEmpty()) {
                return Optional.empty();
            }
            // ⚠️ 한 글자는 부분일치로 받지 않는다 — 「1」·「c」·「가」(그리고 「ishares」에서 접미사를 뗀 「i」)를
            //    품은 상장은 수백 개라, 시총 1위가 물은 것과 상관없는 ETF가 된다. 그 이름의 상장이 딱 있을 때만 답한다
            if (wanted.length() < MIN_PARTIAL_LENGTH) {
                return exact(query);
            }
            List<Listing> exact = new ArrayList<>();
            List<Listing> containing = new ArrayList<>();
            List<String> tokens = tokens(wanted);
            for (Entry entry : entries) {
                if (entry.normalizedName().equals(wanted)) {
                    exact.add(entry.listing());
                } else if (containsAll(entry.normalizedName(), tokens)) {
                    containing.add(entry.listing());
                }
            }
            return (exact.isEmpty() ? containing : exact).stream()
                    .max(Comparator.comparingLong(Listing::marketCap));
        }
    }
}
